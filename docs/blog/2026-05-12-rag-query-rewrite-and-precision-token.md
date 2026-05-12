# Query Rewrite 不是越智能越好：RAG 检索的精确词保护与动态召回

> 项目地址：[interview-agent](https://github.com/Kiyra-gjx/interview-agent)
> 技术栈：Java 21 / Spring Boot 4.0 / Spring AI 2.0 / pgvector / DashScope (qwen-plus)

## 问题：改写把关键词改没了

RAG 系统里，用户的问题往往不适合直接做向量搜索。"Redis 怎么用"太泛，"帮我看看这个"缺少上下文。Query Rewrite 用 LLM 把问题改写成更精确的检索词，是 RAG 的标准操作。

但改写有一个致命的副作用：**它会把用户的关键术语改掉**。

用户问"Redis"，rewrite 可能输出"缓存数据库的使用方法"。向量搜索用改写后的 query 去匹配，召回的文档全是泛泛的缓存概念，没有一篇提到 Redis。模型拿到这些文档后，要么胡说，要么生成一大段"知识库中未找到相关信息"——白白浪费一次 LLM 调用。

这不是假设。上线第一天就遇到了：

1. 用户问 "JVM GC"，rewrite 成 "Java 虚拟机垃圾回收机制详解"，召回了 GC 概述但没有 GC 参数调优的具体内容
2. 用户问 "Spring @Transactional"，rewrite 成 "Spring 事务管理"，漏掉了 AOP 代理相关的精确文档
3. 用户问 "Redis"（就一个词），rewrite 把它扩展成一句话，反而把精确匹配搞丢了

问题的根因：**rewrite 对语义理解有帮助，但对精确术语匹配有害**。需要一套机制，在两者之间找到平衡。

## 整体检索流程

```
用户问题
    │
    ▼
┌─────────────────────────┐
│ 1. 归一化（trim/空白）    │
└─────────┬───────────────┘
          │
          ▼
┌─────────────────────────┐
│ 2. 提取精确词（Precision │
│    Tokens）              │
└─────────┬───────────────┘
          │
          ▼
┌─────────────────────────┐     单术语精确问法？
│ 3. Query Rewrite（LLM） │──── 是 ──▶ 跳过 rewrite
└─────────┬───────────────┘     │
          │ 否                   │
          ▼                      │
┌─────────────────────────┐     │
│ 4. 候选队列：             │◀───┘
│    [改写query, 原始query] │
└─────────┬───────────────┘
          │ 逐个尝试
          ▼
┌─────────────────────────┐
│ 5. 动态 topK/minScore   │
│    向量搜索              │
└─────────┬───────────────┘
          │
          ▼
┌─────────────────────────┐
│ 6. 精确词命中校验         │
│    所有 token 都出现？    │──── 否 ──▶ 尝试下一个候选
└─────────┬───────────────┘
          │ 是
          ▼
┌─────────────────────────┐
│ 7. 生成回答              │
└─────────────────────────┘
```

六个环节，每个都有独立的防护逻辑。

## 精确词提取：什么才算"精确词"

不是所有词都需要保护。"的"、"怎么"、"是什么"这些停用词不需要保护。需要保护的是那些**用户明确提到的、不能被改写丢失的技术术语和标识符**。

```java
private static final Pattern PRECISION_TOKEN_PATTERN = Pattern.compile(
    "(?<![A-Za-z0-9_-])[A-Za-z0-9][A-Za-z0-9_-]{1,31}(?![A-Za-z0-9_-])"
);
```

这个正则的规则：

| 条件 | 含义 |
| --- | --- |
| 2-32 个字符 | 太短（1 字符）没有区分度，太长不是术语 |
| 字母数字开头 | 排除标点开头的片段 |
| 前后不能是字母数字下划线 | 保证是完整词，不是长标识符的子串 |

实际效果：

| 用户问题 | 提取的精确词 |
| --- | --- |
| "Redis 怎么用" | `["Redis"]` |
| "JVM GC 参数调优" | `["JVM", "GC"]` |
| "Spring @Transactional 失效" | `["Spring", "Transactional"]` |
| "帮我看看这个" | `[]`（无精确词） |

## Rewrite 跳过逻辑：什么时候不该改写

```java
private boolean shouldSkipRewrite(String question, List<String> precisionTokens) {
    if (question == null || question.isBlank()) {
        return true;
    }
    return precisionTokens.size() == 1 && question.equals(precisionTokens.getFirst());
}
```

跳过条件：**问题本身就是单个精确词**。比如用户就输入了 "Redis"，rewrite 会把它扩展成"Redis 缓存数据库的使用和配置"，但向量搜索用原始的 "Redis" 反而能精确命中。

这不是"rewrite 不好"，而是"rewrite 在这个场景下没有收益"。单术语问题的语义已经足够明确，改写只会引入噪声。

## 动态检索参数：短问题和长问题不能用同一套

```java
private SearchParams resolveSearchParams(String question) {
    int compactLength = question.replaceAll("\\s+", "").length();
    if (compactLength <= shortQueryLength) {        // <= 4 字符
        return new SearchParams(topkShort, minScoreShort);   // topK=20, minScore=0.18
    }
    if (compactLength <= 12) {                       // 5-12 字符
        return new SearchParams(topkMedium, minScoreDefault); // topK=12, minScore=0.28
    }
    return new SearchParams(topkLong, minScoreDefault);       // topK=8, minScore=0.28
}
```

| 问题长度 | topK | minScore | 策略 |
| --- | --- | --- | --- |
| ≤ 4 字符 | 20 | 0.18 | 宽召回、低门槛——短问题语义模糊，需要更多候选 |
| 5-12 字符 | 12 | 0.28 | 中等召回 |
| > 12 字符 | 8 | 0.28 | 精准召回——长问题语义明确，少而精 |

为什么短问题要 topK=20？因为 "Redis" 这种查询，向量搜索会返回很多关于缓存、NoSQL、数据结构的文档，真正相关的可能排在第 10 位以后。topK 太小会漏掉。

为什么短问题要 minScore=0.18？因为单术语的 embedding 向量比较泛，和具体文档的相似度天然偏低。阈值设太高会过滤掉相关文档。

## 多候选检索：改写失败还有兜底

```java
private RetrievalResult retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
    for (String candidateQuery : queryContext.candidateQueries()) {
        List<Document> docs = vectorService.similaritySearch(
            candidateQuery, knowledgeBaseIds,
            queryContext.searchParams().topK(),
            queryContext.searchParams().minScore()
        );
        HitEvaluation hitEvaluation = evaluateHit(
            queryContext.originalQuestion(), queryContext.precisionTokens(), docs
        );
        attempts.add(new RetrievalAttempt(candidateQuery, docs, ...));
        if (hitEvaluation.effectiveHit()) {
            return new RetrievalResult(candidateQuery, docs, true, attempts);
        }
    }
    return RetrievalResult.empty(attempts);
}
```

候选队列是 `[改写query, 原始query]`，按顺序尝试。**第一个产生有效命中的候选胜出**。

这个设计的关键是：改写 query 优先，但不是唯一选择。如果改写后的 query 召回的文档不满足精确词校验，会自动降级到原始 query。用户不会感知到 rewrite 的存在——要么改写后更好，要么静默回退。

## 精确词命中校验：召回不等于可回答

这是整个流程中最关键的防线。向量搜索返回了文档，分数也够高，但**文档里可能根本没有用户提到的术语**。

```java
private HitEvaluation evaluateHit(String originalQuestion, List<String> precisionTokens,
                                    List<Document> docs) {
    if (docs == null || docs.isEmpty()) {
        return new HitEvaluation(false, "no_hits");
    }
    if (precisionTokens == null || precisionTokens.isEmpty()) {
        return new HitEvaluation(true, null);  // 无精确词，有结果就算命中
    }

    List<String> missingTokens = new ArrayList<>();
    for (String precisionToken : precisionTokens) {
        if (!containsToken(docs, precisionToken)) {
            missingTokens.add(precisionToken);
        }
    }

    if (missingTokens.isEmpty()) {
        return new HitEvaluation(true, null);
    }
    return new HitEvaluation(false, "missing_precision_tokens:" + String.join(",", missingTokens));
}
```

校验规则：**所有精确词必须至少在一篇召回文档中出现**（不区分大小写）。

实际案例：

| 用户问题 | 精确词 | 召回文档包含 | 结果 |
| --- | --- | --- | --- |
| "Redis 缓存穿透" | `["Redis"]` | 文档提到 Redis | 有效命中 |
| "JVM GC 调优" | `["JVM", "GC"]` | 文档提到 JVM 但没提 GC | 命中失败，尝试下一候选 |
| "Spring 事务" | `["Spring"]` | 文档提到 Spring | 有效命中 |
| "帮我看看" | `[]` | 有任何文档 | 有效命中（无精确词时跳过校验） |

这个校验的意义：**宁可返回"未检索到相关信息"，也不要把弱相关文档交给模型生成误导性回答**。Stage 8 RAG 评测中的 S8-018 失败案例（Correctness 高但 Faithfulness 低）就是因为弱相关文档导致模型"合理地胡说"。

## 向量搜索降级：过滤失败时的兜底

向量搜索本身也可能失败。pgvector 的 filter expression（`metadata.kbId in ['1', '2']`）在某些边界情况下会报错。这时候需要降级：

```java
public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds,
                                        int topK, double minScore) {
    try {
        // 主路径：filter expression 下推到 pgvector
        SearchRequest.Builder builder = SearchRequest.builder()
            .query(query).topK(Math.max(topK, 1));
        if (minScore > 0) builder.similarityThreshold(minScore);
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
        }
        return vectorStore.similaritySearch(builder.build());
    } catch (Exception e) {
        log.warn("向量搜索前置过滤失败，回退到本地过滤 {}", e.getMessage());
        return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
    }
}
```

降级策略：**取 3 倍 topK 的无过滤结果，Java 侧按 metadata.kbId 本地过滤**。

```java
private List<Document> similaritySearchFallback(...) {
    // 1. 不带 filter，取 3x 结果
    List<Document> allResults = vectorStore.similaritySearch(builder.topK(topK * 3).build());
    // 2. 本地过滤
    allResults = allResults.stream()
        .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
        .collect(Collectors.toList());
    // 3. 截取实际需要的数量
    return allResults.stream().limit(topK).toList();
}
```

为什么取 3 倍？因为无过滤的结果可能包含大量其他知识库的文档，3 倍是一个经验值——足够在本地过滤后保留足够的目标知识库文档。如果 3 倍还不够，说明目标知识库的内容占比很低，这种情况返回少量结果比返回错误知识库的结果更好。

## 流式输出的探测窗口

SSE 流式场景下，还有一个特殊问题：模型可能输出一大段"未检索到相关信息"的解释，用户要等好几秒才能看到结果。

```java
private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
    return Flux.create(sink -> {
        StringBuilder probeBuffer = new StringBuilder();
        AtomicBoolean passthrough = new AtomicBoolean(false);

        disposableRef[0] = rawFlux.subscribe(
            chunk -> {
                if (passthrough.get()) {
                    sink.next(chunk);  // 透传模式：直接转发
                    return;
                }
                // 探测模式：缓存前 120 字符
                probeBuffer.append(chunk);
                if (isNoResultLike(probeBuffer.toString())) {
                    // 检测到"无结果"模板，立即输出固定文案
                    sink.next(NO_RESULT_RESPONSE);
                    sink.complete();
                    return;
                }
                if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                    // 前 120 字符没有"无结果"标记，切换到透传
                    passthrough.set(true);
                    sink.next(probeBuffer.toString());
                }
            }
        );
    });
}
```

逻辑分三个阶段：

| 阶段 | 行为 | 条件 |
| --- | --- | --- |
| 探测 | 缓存前 120 字符 | 流刚开始 |
| 截断 | 输出固定"未检索到"文案，结束流 | 检测到"没有找到"/"未检索到"/"信息不足"等关键词 |
| 透传 | 直接转发后续 chunk | 前 120 字符不含"无结果"标记 |

120 字符的选择：足够覆盖模型"无结果"回复的开头（通常是"抱歉"或"根据"开头的一句话），但不会延迟正常回答的首字输出时间。

## 设计哲学

### 1. 改写是增强，不是替换

Query Rewrite 的结果是候选之一，不是唯一选择。原始 query 始终在候选队列里兜底。这保证了 rewrite 最差不会让结果变坏——它要么改善，要么被跳过。

### 2. 宁可漏召回，不可错召回

精确词校验会拒绝一些"向量相似度高但术语不匹配"的文档。这可能导致某些本可以回答的问题返回"未检索到"。但比起把弱相关文档交给模型生成误导性回答，漏召回是更安全的失败模式。Stage 8 评测的结论：**Faithfulness 比 Recall 更重要**。

### 3. 短问题和长问题是两种生物

4 字符以下的问题（"Redis"、"JVM"）语义极度模糊，需要宽召回、低门槛。12 字符以上的问题（"Spring 事务传播机制有哪几种"）语义明确，需要精准召回、高门槛。用同一套参数处理两种问题，必然有一方受损。

### 4. 降级要安静

向量搜索过滤失败时，本地过滤降级对调用方完全透明。Query Rewrite 失败时，回退到原始 query。流式输出检测到"无结果"时，截断为固定文案。这些降级路径在日志里有记录，但对用户不可见——系统应该尽力给出最佳结果，而不是把内部错误暴露给用户。

### 5. 本地修复优先于 LLM 修复

精确词校验是纯 Java 字符串匹配，不调 LLM。流式探测窗口是纯字符串检测，不调 LLM。向量搜索降级是纯 Java 过滤，不调 LLM。整个检索流程只有 Query Rewrite 一个环节调了 LLM。能用本地逻辑解决的问题，不要引入额外的 LLM 调用——成本、延迟、可靠性都不划算。

## 局限性

- **精确词匹配是子串匹配，不是语义匹配**。文档里出现 "Redis" 会命中，但如果文档用的是 "RDB" 或 "AOF" 而没有出现 "Redis" 这个词，即使内容完全相关也会被拒绝。
- **Rewrite 的质量依赖 LLM**。当前用的是 qwen-plus，对于复杂问题的改写质量不稳定。如果改写后的 query 质量很差，会白白浪费一次向量搜索。
- **动态参数是经验值**。topK=20 / minScore=0.18 对短问题的效果不错，但没有系统化的调参流程。换一个 embedding 模型或换一批知识库文档，最优参数可能不同。
- **流式探测窗口有 120 字符的延迟**。正常回答的前 120 字符会被缓存再释放，虽然通常在 1 秒内，但对极端延迟敏感的场景可能有感知。
- **没有 query 分类**。当前所有问题走同一条 rewrite → retrieve → validate 流程。事实型问题（"Redis 默认端口是多少"）和分析型问题（"Redis 和 Memcached 怎么选"）可能需要不同的检索策略。

## 结语

Query Rewrite 是 RAG 的好工具，但不是万能的。它会把"Redis"改成"缓存数据库"，把"JVM GC"改成"Java 垃圾回收"——语义上没错，但精确匹配全丢了。

解决方案不是不用 rewrite，而是给它加护栏：提取精确词作为保护对象，跳过单术语改写，用多候选队列兜底，用精确词校验过滤弱召回。整套逻辑不增加 LLM 调用，全是本地规则。

如果你在做 RAG，建议从一开始就建精确词保护机制。等上线后发现模型在"合理地胡说"再补，用户已经受过伤了。

---

*本文代码来自 Interview Agent 项目 `modules/knowledgebase/service/` 和 `resources/prompts/` 目录，关键文件：`KnowledgeBaseQueryService.java`、`KnowledgeBaseVectorService.java`、`knowledgebase-query-rewrite.st`。*
