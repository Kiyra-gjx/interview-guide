# 异步任务不用 Kafka 也行：用 Redis Stream 搭一套轻量级 Producer/Consumer 框架

> 项目地址：[interview-agent](https://github.com/Kiyra-gjx/interview-agent)
> 技术栈：Java 21 / Spring Boot 4.0 / Redis 7 (Redisson) / PostgreSQL

## 问题：LLM 调用太慢，同步扛不住

简历分析、知识库向量化、面试评估——这三个操作有一个共同点：**都要调 LLM，都要等好几秒**。

如果用同步方式处理，用户上传一份简历后要盯着 loading 转 10 秒；上传一份知识库文档后要等 embedding 跑完才能做其他事。这体验太差了。

解决方案很直觉：**异步化**。先写一条"待处理"记录，告诉用户"已提交"，后台慢慢跑。但异步化引出了一串新问题：

- 任务放哪里？数据库轮询太重，Kafka/RabbitMQ 对这个项目来说太重
- 失败了怎么办？LLM 调用会超时、会返回格式错误、会限流
- 用户删了简历，正在跑的分析任务怎么处理？
- 多实例部署时，怎么保证同一个任务不被重复消费？

这篇文章记录了 Interview Agent 项目怎么用 Redis Stream + 模板方法模式解决这些问题，搭了一套只有两个基类、三条流水线的轻量异步框架。

## 为什么是 Redis Stream 而不是 Kafka

选型的理由很简单：

| | Kafka | RabbitMQ | Redis Stream |
| --- | --- | --- | --- |
| 部署复杂度 | 高（ZooKeeper/KRaft） | 中 | 低（已有 Redis） |
| 消费者组支持 | 有 | 有 | 有 |
| 消息持久化 | 磁盘 | 磁盘 | 内存 + AOF |
| 延迟 | 毫秒级 | 毫秒级 | 毫秒级 |
| 运维成本 | 高 | 中 | 低（复用现有 Redis） |

项目已经有 Redis（用于缓存和分布式锁），不需要额外引入消息中间件。Redis Stream 的消费者组（Consumer Group）功能够用——支持多消费者、ACK 机制、pending entry list。对于一个单体应用的异步任务队列，它刚好够。

## 两个基类搞定三条流水线

整个异步框架只有两个抽象类：`AbstractStreamProducer<T>` 和 `AbstractStreamConsumer<T>`。三条流水线（简历分析、知识库向量化、面试评估）各有一对 Producer/Consumer 实现。

### Producer：发送就完了

Producer 的核心逻辑只有 30 行：

```java
public abstract class AbstractStreamProducer<T> {

    private final RedisService redisService;

    protected void sendTask(T payload) {
        try {
            String messageId = redisService.streamAdd(
                streamKey(),
                buildMessage(payload),
                AsyncTaskStreamConstants.STREAM_MAX_LEN  // 1000
            );
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
        } catch (Exception e) {
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
        }
    }

    // 子类实现这 5 个方法就够了
    protected abstract String taskDisplayName();
    protected abstract String streamKey();
    protected abstract Map<String, String> buildMessage(T payload);
    protected abstract String payloadIdentifier(T payload);
    protected abstract void onSendFailed(T payload, String error);
}
```

几个设计要点：

**发送失败不抛异常**。`sendTask()` catch 了所有异常，转交给 `onSendFailed()` 让子类更新数据库状态。调用方不需要关心 Redis 是否可用——如果 Redis 挂了，任务状态会变成 FAILED，用户可以在界面上看到并手动重试。

**`STREAM_MAX_LEN = 1000` 自动裁剪**。Redis Stream 会用 `XADD ... MAXLEN ~ 1000` 自动丢弃最老的消息。这防止了 Stream 无限增长——如果消费者挂了很久没消费，老消息会被自动清理。对于异步任务来说，超过 1000 条未处理的消息意味着系统已经出了大问题，老消息也没有处理价值了。

**Payload 用 `Map<String, String>` 序列化**。不走 JSON 序列化框架，直接用 `Map.of("resumeId", id.toString(), "content", text)` 构造消息体。简单、无依赖、可读。

### Consumer：模板方法的经典应用

Consumer 是整个框架最复杂的部分。它管理了完整的生命周期：初始化、消费循环、消息处理、ACK、重试、优雅关闭。

#### 初始化

```java
@PostConstruct
public void init() {
    this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);

    try {
        redisService.createStreamGroup(streamKey(), groupName());
    } catch (Exception e) {
        log.warn("创建消费者组时发生异常（可能已存在）: {}", e.getMessage());
    }

    this.executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, threadName());
        t.setDaemon(true);
        return t;
    });

    running.set(true);
    executorService.submit(this::consumeLoop);
}
```

**消费者名用 UUID 后缀**。每个应用实例的消费者都有唯一名称（如 `analyze-consumer-a3b2c1d0`），但它们在同一个消费者组里。Redis 会自动在组内分配消息，实现水平扩展。

**消费者组创建幂等**。Redis 返回 `BUSYGROUP` 错误时静默忽略。不管是第一次启动还是重启，代码都不需要特殊处理。

**守护线程**。`t.setDaemon(true)` 保证 Spring 关闭时线程不会阻止 JVM 退出。

#### 消费循环

```java
private void consumeLoop() {
    while (running.get()) {
        try {
            redisService.streamConsumeMessages(
                streamKey(), groupName(), consumerName,
                AsyncTaskStreamConstants.BATCH_SIZE,       // 10
                AsyncTaskStreamConstants.POLL_INTERVAL_MS,  // 1000ms
                this::processMessage
            );
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            log.error("消费消息时发生错误: {}", e.getMessage(), e);
        }
    }
}
```

消费循环用的是 Redis 的 **阻塞读**（`XREADGROUP` with timeout），不是客户端轮询。服务端会阻塞最多 1000ms 等待新消息，期间不消耗客户端 CPU。每批最多读 10 条消息。

这里有一个 Redisson 的 bug workaround：

```java
// RedisService.readStreamMessages()
try {
    return stream.readGroup(groupName, consumerName, args);
} catch (ClassCastException e) {
    if (isBlockingReadTimeoutDecodeBug(e, blockTimeoutMs)) {
        return Map.of();  // 超时时 Redisson 返回了错误类型，当空结果处理
    }
    throw e;
}
```

Redisson 在阻塞读超时时会抛 `ClassCastException`（把 `Collections$EmptyList` 当 `Map` 返回）。这个 bug 在超时时触发，不影响正常消息消费，但需要 catch 掉。

#### 消息处理与重试

```java
private void processMessage(StreamMessageId messageId, Map<String, String> data) {
    T payload = parsePayload(messageId, data);
    if (payload == null) {
        ackMessage(messageId);  // 格式错误的消息直接 ACK 跳过
        return;
    }

    int retryCount = parseRetryCount(data);
    try {
        markProcessing(payload);
        processBusiness(payload);
        markCompleted(payload);
        ackMessage(messageId);
    } catch (Exception e) {
        if (shouldRetry(e, retryCount)) {
            retryMessage(payload, retryCount + 1);
        } else {
            markFailed(payload, truncateError(...));
        }
        ackMessage(messageId);  // 失败也 ACK
    }
}
```

这段代码最关键的设计决策是：**Always ACK**。

不管是成功、失败还是重试，原消息都会被 ACK。重试不是靠 Redis 的 PEL（Pending Entry List）机制，而是发一条新消息到 Stream，带上递增的 `retryCount`。

为什么不走 PEL？因为 PEL 的管理很复杂——需要 `XCLAIM` 转移所有权、需要处理消费者崩溃后的消息恢复、需要监控 pending list 长度。对于这个项目的需求，ACK + 新消息的方案更简单、更可控、更易调试。Stream 里能看到每条重试消息的 `retryCount`，排查问题时一目了然。

重试决策逻辑：

```java
private boolean shouldRetry(Exception e, int retryCount) {
    if (retryCount >= AsyncTaskStreamConstants.MAX_RETRY_COUNT) {  // 3
        return false;
    }
    if (e instanceof AiServiceException aiServiceException) {
        return aiServiceException.isRetryable();
    }
    return true;  // 非 AI 异常默认重试
}
```

最多重试 3 次。`AiServiceException` 带有 `retryable` 标记，可以精细控制哪些错误值得重试（比如超时、限流）哪些不值得（比如 API key 无效）。

#### 优雅关闭

```java
@PreDestroy
public void shutdown() {
    running.set(false);
    if (executorService != null) {
        executorService.shutdown();
    }
}
```

`running.set(false)` 让消费循环退出。`executorService.shutdown()` 等待当前消息处理完成。因为线程是守护线程，即使 `shutdown()` 没来得及执行，JVM 也会强制退出。

## 三条流水线的差异化设计

三条流水线共享同一套 Producer/Consumer 基类，但在业务层各有特色。

### 简历分析：两层重试控制

简历分析消费者有一个独特的设计：**结构化输出失败时，抑制 Stream 层重试，但保留用户手动重试的能力**。

```java
private AiServiceException disableAutomaticRetryForStructuredOutputError(AiServiceException e) {
    if (e.getErrorCode() != ErrorCode.AI_RESPONSE_FORMAT_INVALID || !e.isRetryable()) {
        return e;
    }
    // 结构化输出失败已在单次调用内做过重试，这里停止 Stream 自动重入队，
    // 但保留 failureState 中的 retryable=true，允许用户手动重新发起分析。
    return new AiServiceException(e.getErrorCode(), e.getMessage(), false, e);
}
```

为什么要这样做？因为 LLM 的结构化输出（JSON）有两个层次的重试：

1. **AI 调用层**：`StructuredOutputInvoker` 已经做了最多 2 次重试（注入上次错误、自动修复 JSON）
2. **Stream 层**：如果 AI 调用层的重试都失败了，Stream 会再重试 3 次

问题是：结构化输出失败通常是 prompt 或模型的问题，不是网络抖动。Stream 层再重试 3 次大概率还是失败，白白浪费 3 次 LLM 调用。

解决方案：在 `processBusiness()` 的 catch 块里，把 `retryable` 从 `true` 改成 `false`。这样 `shouldRetry()` 会返回 `false`，直接走 `markFailed()`。但数据库里存的 `analyzeRetryable` 仍然是 `true`，前端可以展示"重新分析"按钮让用户手动触发。

**两层重试，语义不同**：AI 层重试是自动的、快速的（同一请求内）；Stream 层重试是兜底的、有间隔的（新消息）；用户手动重试是最终的、有明确意图的。

### 知识库向量化：生命周期感知

知识库有一个简历没有的状态：**正在删除**。用户删除知识库时，可能还有向量化任务在队列里排队。如果消费者还在处理这些任务，会产生竞态条件。

解决方案是 **生命周期感知**——每个生命周期钩子都先检查知识库是否还是 ACTIVE 状态：

```java
private boolean isActiveKnowledgeBase(Long kbId) {
    return knowledgeBaseRepository.findById(kbId)
        .map(kb -> kb.getLifecycleStatus() == KnowledgeBaseLifecycleStatus.ACTIVE)
        .orElse(false);
}

@Override
protected void markProcessing(VectorizePayload payload) {
    if (isActiveKnowledgeBase(payload.kbId())) {
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
    }
}

@Override
protected void processBusiness(VectorizePayload payload) {
    if (!isActiveKnowledgeBase(payload.kbId())) {
        log.info("知识库已非 ACTIVE，跳过向量化任务: kbId={}", payload.kbId());
        return;
    }
    vectorService.vectorizeAndStore(payload.kbId(), payload.content());
}
```

如果知识库已经被标记为 `DELETING`，消费者会静默跳过所有状态更新。这避免了"向量化完成但知识库已经被删了"的脏状态。

注意检查的时机：`processBusiness()` 在执行前检查，`markCompleted()` 和 `markFailed()` 在执行后也检查。因为向量化可能跑好几秒，期间知识库可能被删除。

### 面试评估：最简单的流水线

面试评估的 Producer 只发一个 `sessionId`，Consumer 在处理时才从数据库加载所有需要的数据：

```java
protected void processBusiness(EvaluatePayload payload) {
    InterviewSessionEntity session = sessionRepository
        .findBySessionIdWithResume(sessionId).orElse(null);
    if (session == null) return;

    List<InterviewQuestionDTO> questions = objectMapper.readValue(
        session.getQuestionsJson(), new TypeReference<>() {});

    List<InterviewAnswerEntity> answers = persistenceService
        .findAnswersBySessionId(sessionId);
    for (InterviewAnswerEntity answer : answers) {
        int index = answer.getQuestionIndex();
        if (index >= 0 && index < questions.size()) {
            questions.set(index, questions.get(index).withAnswer(answer.getUserAnswer()));
        }
    }

    InterviewReportDTO report = evaluationService.evaluateInterview(
        sessionId, session.getResume().getResumeText(), questions);
    persistenceService.saveReport(sessionId, report);
}
```

为什么不像简历分析那样把内容也放进消息？因为面试评估的数据来源更多（问题 + 回答 + 简历），消息体太大不合适。只传 `sessionId`，Consumer 按需加载，消息体最小化。

## 前端怎么知道任务完成了

没有 WebSocket。前端用的是 **轮询**。

每个异步任务在数据库实体上有一个状态字段（`analyzeStatus`、`vectorStatus`），取值为 `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED`。Producer 入队时写 `PENDING`，Consumer 开始处理时写 `PROCESSING`，完成后写 `COMPLETED` 或 `FAILED`。

前端每隔几秒查一次这个字段。看到 `COMPLETED` 就刷新数据，看到 `FAILED` 就展示错误信息和重试按钮。

这个方案比 WebSocket 简单得多，对于异步任务的延迟容忍度（几秒）来说完全够用。

## 设计哲学总结

### 1. 模板方法优于框架封装

没有用 Spring 的 `@StreamListener` 或 Spring Integration。原因是：这些框架封装得太深，消费循环、重试逻辑、生命周期管理都藏在框架内部，出了问题很难调试。模板方法模式把控制权留在自己手里——基类定义骨架，子类填充业务，每一层都可读、可调试、可覆盖。

### 2. Always ACK，重试靠新消息

Redis Stream 的 PEL 机制看起来很方便，但实际用起来很麻烦：消费者崩溃后消息卡在 PEL 里、`XCLAIM` 的所有权转移语义不直观、pending list 需要额外监控。ACK + 新消息的方案把重试状态变成了 Stream 里的一条新消息，可见、可追踪、可调试。

### 3. 状态机在数据库里，不在 Redis 里

任务状态（PENDING → PROCESSING → COMPLETED/FAILED）存在 PostgreSQL 的实体字段里，不存在 Redis 里。原因：Redis 是易失的，数据库是持久的。如果 Redis 重启了，Stream 消息可能丢失，但数据库里的状态记录不会丢。前端轮询的也是数据库状态，不是 Stream 状态。

### 4. 防御性检查贯穿始终

简历分析在处理前检查 `existsById`，处理后再检查一次（因为 AI 调用耗时长，期间简历可能被删除）。知识库向量化在每个生命周期钩子里都检查 `isActiveKnowledgeBase`。这些检查看起来多余，但在并发场景下是必要的。

### 5. 两条重试语义分开

AI 层的重试（结构化输出修复）和 Stream 层的重试（网络/服务异常兜底）是两个不同的概念。AI 层重试在同一请求内快速完成，Stream 层重试跨消息有间隔。把它们混在一起会导致不必要的 LLM 调用浪费。

## 局限性

- **单线程消费者**。每个 Consumer 只有一个线程处理消息。如果单条消息处理很慢（比如面试评估要调好几次 LLM），队列会积压。解决方案是水平扩展——多开几个应用实例，利用消费者组自动分配。
- **没有死信队列**。重试 3 次后仍然失败的消息直接标记 FAILED，没有转移到死信队列。对于这个项目来说够用，但如果需要离线分析失败原因，死信队列会更方便。
- **Stream 消息不持久化到磁盘**。Redis 的 AOF 持久化可以保证消息不丢，但如果 AOF 策略是 `everysec`，极端情况下可能丢 1 秒的消息。对于异步任务来说可以接受，但对金融级消息不适用。
- **没有消息去重**。如果 Producer 在 `streamAdd` 之后、`markProcessing` 之前崩溃，用户重试会发一条新消息，导致同一任务被处理两次。目前靠数据库状态幂等（COMPLETED 状态不重复处理）来兜底，但不是严格幂等。

## 结语

异步任务队列不一定要上 Kafka。对于单体应用、几条流水线、可容忍几秒延迟的场景，Redis Stream + 模板方法模式是一个务实的选择：部署零成本（复用现有 Redis）、代码量小（两个基类 ~200 行）、可调试（Stream 里能看到每条消息和重试记录）。

如果你的项目已经有 Redis，需要异步化几个 LLM 调用，不妨先试试 Redis Stream。等到真的需要持久化保证、事务消息、或者跨服务通信时，再上 Kafka 也不迟。

---

*本文代码来自 Interview Agent 项目 `common/async/` 和 `modules/*/listener/` 目录，关键文件：`AbstractStreamProducer.java`、`AbstractStreamConsumer.java`、`AnalyzeStreamConsumer.java`、`VectorizeStreamConsumer.java`。*
