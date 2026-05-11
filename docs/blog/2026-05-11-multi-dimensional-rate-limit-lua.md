# 限流不是加个计数器就行：用 Lua 脚本实现多维度原子限流

> 项目地址：[interview-agent](https://github.com/Kiyra-gjx/interview-agent)
> 技术栈：Java 21 / Spring Boot 4.0 / Redis 7 (Redisson) / PostgreSQL

## 问题：单维度限流挡不住真实场景

简历上传接口，你加了一个"每分钟 10 次"的限流。上线第一天就出事了：

1. **正常用户被误杀**。某个 IP 后面有 20 个用户共享出口 IP（公司 NAT），一个人疯狂上传，其他人全部 429。
2. **恶意用户绕过限流**。攻击者切换 IP 继续上传，全局限流形同虚设——每个 IP 都没超，但总量已经把 LLM 配额打爆了。
3. **部分扣减导致数据不一致**。你用两段式代码先检查 IP 限流再检查全局限流，IP 通过了、全局没通过，但 IP 的计数器已经加了。等全局恢复后，IP 的限流凭空少了一个名额。

这三个问题指向同一个根因：**单维度、非原子的限流方案，在多维度组合场景下必然出错**。

这篇文章记录了 Interview Agent 项目怎么用一个 Lua 脚本 + 一个 AOP 切面，实现声明式、多维度、原子性的限流。

## 整体方案

```
┌─────────────────────────────────────────────────────┐
│  @RateLimit(dimensions = {GLOBAL, IP}, count = 5)   │  ← 声明式注解
└─────────────┬───────────────────────────────────────┘
              │ AOP 拦截
              ▼
┌─────────────────────────────────────────────────────┐
│  RateLimitAspect                                    │
│  1. 计算时间窗口（毫秒）                              │
│  2. 按维度生成 Redis Key 列表                         │
│  3. 调用 Lua 脚本（evalSha）                          │
│  4. 结果=1 → 放行  结果=0 → 降级/拒绝                 │
└─────────────┬───────────────────────────────────────┘
              │ evalSha（原子执行）
              ▼
┌─────────────────────────────────────────────────────┐
│  Lua 脚本（Redis 服务端执行）                         │
│  ┌───────────────┐    ┌───────────────────┐          │
│  │ 第一阶段：预检查 │───▶│ 第二阶段：扣减令牌  │          │
│  │ 所有维度都够？   │    │ 所有维度一起扣      │          │
│  └───────────────┘    └───────────────────┘          │
└─────────────────────────────────────────────────────┘
```

三个组件，各司其职：

| 组件 | 职责 | 位置 |
| --- | --- | --- |
| `@RateLimit` 注解 | 声明维度、阈值、降级方法 | Controller 方法上 |
| `RateLimitAspect` | AOP 拦截、Key 生成、结果处理 | `common/aspect/` |
| `rate_limit.lua` | 原子检查 + 扣减 | `resources/scripts/` |

## 注解设计：声明式限流

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    enum Dimension { GLOBAL, IP, USER }

    Dimension[] dimensions() default {Dimension.GLOBAL};
    double count();                          // 窗口内最大请求数
    long interval() default 1;               // 时间窗口大小
    TimeUnit timeUnit() default TimeUnit.SECONDS;
    long timeout() default 0;                // 等待令牌超时（0=不等待）
    String fallback() default "";            // 降级方法名
}
```

使用时只需要一行注解：

```java
@PostMapping("/api/resumes/upload")
@RateLimit(dimensions = {GLOBAL, IP}, count = 5)
public Result<Map<String, Object>> uploadAndAnalyze(@RequestParam("file") MultipartFile file) {
    // ...
}
```

这行注解的语义是：**全局每秒最多 5 次，同时每个 IP 每秒最多 5 次，两个维度都满足才放行**。

项目里还有更精细的配置：

```java
// 知识库查询：全局 10/min，单 IP 10/min
@RateLimit(dimensions = {GLOBAL, IP}, count = 10)

// 简历重新分析：全局 2/min，单 IP 2/min（LLM 调用昂贵）
@RateLimit(dimensions = {GLOBAL, IP}, count = 2)

// 面试答题：仅全局限流（用户已经登录，不需要 IP 维度）
@RateLimit(dimensions = {GLOBAL}, count = 10)
```

## Lua 脚本：两阶段原子执行

这是整个方案的核心。为什么用 Lua？因为 Redis 的单线程模型保证了 Lua 脚本的原子性——脚本执行期间不会有其他命令插入。

### 数据结构

每个维度在 Redis 里有两个 Key：

```
ratelimit:{ClassName:methodName}:global:value     → 当前可用令牌数（String）
ratelimit:{ClassName:methodName}:global:permits    → 已分配令牌记录（Sorted Set）
```

`permits` 是一个 Sorted Set，member 是 `requestId:permits`，score 是分配时间戳（毫秒）。这个结构支持精确的过期回收。

### 第一阶段：预检查

```lua
for i, key in ipairs(KEYS) do
    local value_key = key .. ":value"
    local permits_key = key .. ":permits"

    -- 初始化（首次访问时令牌满额）
    if redis.call("exists", value_key) == 0 then
        redis.call("set", value_key, max_tokens)
    end

    -- 回收过期令牌
    local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
    if #expired_values > 0 then
        local expired_count = 0
        for _, v in ipairs(expired_values) do
            local p = tonumber(string.match(v, ":(%d+)$"))
            if p then expired_count = expired_count + p end
        end
        redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)
        local next_v = math.min(max_tokens, curr_v + expired_count)
        redis.call("set", value_key, next_v)
    end

    -- 核心：令牌够不够？
    if current_val < permits then
        return 0  -- 任何一个维度不够，直接返回失败
    end
end
```

关键点：**过期回收在检查前执行**。这意味着每次请求都会顺带清理过期令牌，不需要定时任务。`math.min(max_tokens, ...)` 防止回收后令牌超过上限——Sorted Set 里可能有重复或过期的记录。

### 第二阶段：扣减

```lua
for i, key in ipairs(KEYS) do
    local value_key = key .. ":value"
    local permits_key = key .. ":permits"

    -- 记录本次分配
    redis.call("zadd", permits_key, now_ms, request_id .. ":" .. permits)
    -- 扣减令牌
    redis.call("set", value_key, current_v - permits)
    -- 设置过期时间（窗口的 2 倍，至少 1 秒）
    redis.call("expire", value_key, expire_time)
    redis.call("expire", permits_key, expire_time)
end

return 1
```

**只有第一阶段所有维度都通过，才会进入第二阶段**。这解决了前面提到的"部分扣减"问题——不存在"IP 扣了但全局没扣"的中间状态。

## Key 生成：Redis Cluster 兼容

```java
private List<String> generateKeys(String className, String methodName,
                                   RateLimit.Dimension[] dimensions) {
    String hashTag = "{" + className + ":" + methodName + "}";
    String keyPrefix = "ratelimit:" + hashTag;

    for (RateLimit.Dimension dimension : dimensions) {
        switch (dimension) {
            case GLOBAL -> keys.add(keyPrefix + ":global");
            case IP    -> keys.add(keyPrefix + ":ip:" + getClientIp());
            case USER  -> keys.add(keyPrefix + ":user:" + getCurrentUserId());
        }
    }
    return keys;
}
```

`{ClassName:methodName}` 是 Redis Cluster 的 Hash Tag。在 Cluster 模式下，Redis 只对 `{}` 内的部分做 slot 计算。这意味着同一个方法的所有维度 Key（global、ip:1.2.3.4、user:42）都会落在同一个 slot 上，保证 Lua 脚本可以在单个节点上原子执行。

如果没有这个 Hash Tag，`ratelimit:upload:global` 和 `ratelimit:upload:ip:1.2.3.4` 可能落在不同 slot，Lua 脚本会报 `CROSSSLOT` 错误。

## 降级策略：不是只有拒绝

限流触发时有两种处理方式：

```java
private Object handleRateLimitExceeded(ProceedingJoinPoint joinPoint,
                                        RateLimit rateLimit, List<String> keys) {
    // 1. 有降级方法？调用降级方法
    if (!rateLimit.fallback().isEmpty()) {
        Method fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback());
        if (fallbackMethod != null) {
            return fallbackMethod.invoke(joinPoint.getTarget(), joinPoint.getArgs());
        }
    }
    // 2. 没有降级方法？抛异常
    throw new RateLimitExceededException("请求过于频繁，请稍后再试");
}
```

降级方法的查找规则：**先找同参数签名的，找不到找无参的**。这允许降级方法根据原始请求参数做更精细的处理（比如返回缓存数据），也允许简单的"返回默认值"场景。

```java
// 原方法
@RateLimit(count = 5, fallback = "queryFallback")
public Result<QueryResponse> query(QueryRequest request) { ... }

// 降级方法：返回缓存或默认值
public Result<QueryResponse> queryFallback(QueryRequest request) {
    return Result.success(cachedService.getLastResult(request));
}
```

## Script 预加载

```java
@PostConstruct
public void init() {
    this.luaScriptSha = redissonClient.getScript(StringCodec.INSTANCE)
        .scriptLoad(LUA_SCRIPT);
}
```

`scriptLoad` 把 Lua 脚本上传到 Redis，返回一个 SHA1 摘要。后续调用用 `evalSha` 传 SHA1 而不是完整脚本内容，减少了网络传输量。Redis 会缓存脚本，`evalSha` 的执行效率和原生命令一样。

如果 Redis 重启导致脚本缓存丢失，`evalSha` 会返回 `NOSCRIPT` 错误。目前代码没有处理这个边界情况——在单实例部署下 Redis 重启意味着服务也会重启，`@PostConstruct` 会重新加载脚本。

## 设计哲学

### 1. 原子性不是靠代码顺序，是靠执行环境

Java 代码里的"先检查再扣减"在并发下是不安全的——两个线程可能同时检查通过，然后各扣一个，实际消耗了 2 个令牌但只限制了 1 个。分布式锁可以解决，但性能太差。Lua 脚本在 Redis 单线程里执行，天然原子，不需要额外的锁。

### 2. 多维度是 AND 语义，不是 OR

`dimensions = {GLOBAL, IP}` 的含义是"全局 **且** 每个 IP 都不能超"。如果用 OR 语义（任一维度通过即可），攻击者可以利用维度差异绕过限流。AND 语义下，最严格的维度决定最终结果。

### 3. 声明式优于编程式

限流逻辑不应该和业务代码混在一起。一行注解 `@RateLimit(count = 5)` 比在方法开头写 20 行限流代码更清晰、更不容易出错、更容易统一修改。AOP 的开销（一次方法拦截 + 一次 Redis 调用）相比 LLM 调用的耗时可以忽略。

### 4. 降级优于拒绝

限流不是为了惩罚用户，是为了保护系统。如果能返回缓存数据、默认值、或者排队提示，比直接返回 429 体验好得多。`fallback` 机制让每个接口可以定义自己的降级策略。

### 5. Key 设计要考虑部署拓扑

单机 Redis 随便怎么写 Key 都行。但一旦上了 Cluster，Key 的 slot 分配就变成了正确性问题——Lua 脚本要求所有 Key 在同一个 slot。`{hashTag}` 是 Redis Cluster 的标准做法，应该从一开始就用，而不是等迁移到 Cluster 时再改。

## 局限性

- **没有令牌桶/漏桶算法**。当前实现是固定窗口计数器，窗口边界处可能出现突发流量（窗口末尾 5 次 + 下个窗口开头 5 次 = 1 秒内 10 次）。令牌桶可以平滑流量，但 Lua 脚本复杂度会显著增加。
- **IP 获取依赖 HTTP 头**。`X-Forwarded-For` 和 `X-Real-IP` 可以被客户端伪造。在没有反向代理覆盖这些头的场景下，IP 限流不可靠。
- **`timeout` 参数未实现**。注解里声明了 `timeout` 字段（等待令牌的超时时间），但当前 Lua 脚本没有实现阻塞等待逻辑——令牌不够就直接返回失败。
- **没有分布式限流的监控**。限流触发次数、各维度的令牌消耗率、降级方法的调用频率——这些指标目前没有采集。线上只能靠日志排查限流问题。
- **Script 重加载未处理**。Redis 重启后 SHA1 失效，`evalSha` 会失败。需要 catch `NOSCRIPT` 错误并自动重新加载脚本。

## 结语

限流看起来简单——加个计数器，超了就拒绝。但在多维度、分布式、需要原子性的场景下，"简单"的方案会暴露出各种竞态和一致性问题。

Lua 脚本把检查和扣减放在 Redis 服务端一次性完成，AOP 把限流逻辑从业务代码里抽离出来，注解把配置变成了声明。三层分离，各解决一个问题。

如果你的项目已经有 Redis，需要给几个关键接口加上限流，不需要引入 Sentinel 或 Resilience4j 这样的重量级框架——一个 Lua 脚本 + 一个切面就够了。

---

*本文代码来自 Interview Agent 项目 `common/annotation/`、`common/aspect/` 和 `resources/scripts/` 目录，关键文件：`RateLimit.java`、`RateLimitAspect.java`、`rate_limit.lua`。*
