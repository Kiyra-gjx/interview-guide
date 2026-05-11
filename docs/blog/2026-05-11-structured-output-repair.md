# LLM 的 JSON 不靠谱：结构化输出的重试与修复实战

> 项目地址：[interview-agent](https://github.com/Kiyra-gjx/interview-agent)
> 技术栈：Java 21 / Spring Boot 4.0 / Spring AI 2.0 / DashScope (Qwen)

## 问题：你让 LLM 返回 JSON，它返回了什么？

让 LLM 返回结构化 JSON 是 Agent 工程的基础需求。Agent 需要根据 JSON 中的 `shouldUseTool` 字段决定是否调用工具，简历分析需要从 JSON 中提取 `overallScore` 和 `strengths`，面试评估需要解析 `questionEvaluations` 数组。

但 LLM 的 JSON 输出远没有你想象的可靠。以下是我实际遇到过的情况：

**情况 1：Markdown 代码块包裹**
```json
```json
{"overallScore": 78, "summary": "..."}
```
```
LLM 在 JSON 前后加了 ` ```json ` 和 ` ``` `。`BeanOutputConverter` 直接解析失败。

**情况 2：JSON 前后有解释文字**
```
根据简历内容，分析结果如下：
{"overallScore": 78, "summary": "..."}
以上是初步分析，如需详细说明请告诉我。
```
LLM 在 JSON 前后加了自然语言说明。

**情况 3：字符串内有字面换行**
```json
{"summary": "该候选人具备以下技能：
1. Java 后端开发
2. Spring Boot 框架"}
```
JSON 字符串里不能有字面换行符，必须用 `\n` 转义。但 LLM 经常忘记这一点。

**情况 4：完全跑偏**
LLM 返回了一段自然语言回答，完全没有 JSON 结构。这种情况比较少见，但在 prompt 不够清晰时会发生。

这篇文章记录了 Interview Agent 项目怎么用一个 258 行的 `StructuredOutputInvoker` 来系统性地解决这些问题。

## 整体策略：三层防御

```
LLM 返回原始文本
      │
      ▼
┌─────────────────┐
│ 第 1 层：直接解析 │  ← BeanOutputConverter.convert(rawContent)
└──────┬──────────┘
       │ 失败
       ▼
┌─────────────────┐
│ 第 2 层：JSON 修复│  ← 去代码块、提取 JSON 体、转义控制字符
└──────┬──────────┘
       │ 修复后重试解析
       │ 失败
       ▼
┌─────────────────┐
│ 第 3 层：LLM 重试 │  ← 注入上次错误，让模型自我修正
└─────────────────┘
```

第 1 层是正常路径——大部分情况下 LLM 返回的 JSON 能直接解析。第 2 层是本地修复——不调用 LLM，纯字符串处理。第 3 层是重新调用 LLM——把上次的错误告诉它，让它修正。

## 第 2 层：三步 JSON 修复

当 `BeanOutputConverter.convert(rawContent)` 失败时，进入修复流程：

```java
private String repairJson(String rawContent) {
    String candidate = rawContent.trim();
    if (candidate.startsWith("```")) {
        candidate = stripCodeFence(candidate);      // 第 1 步
    }
    candidate = extractJsonBody(candidate);           // 第 2 步
    return escapeControlCharsInJsonStrings(candidate); // 第 3 步
}
```

### 第 1 步：去代码块

```java
private String stripCodeFence(String text) {
    int firstNewline = text.indexOf('\n');
    if (firstNewline < 0) return text;

    String body = text.substring(firstNewline + 1);
    int fenceEnd = body.lastIndexOf("```");
    if (fenceEnd >= 0) {
        return body.substring(0, fenceEnd).trim();
    }
    return text;
}
```

去掉开头的 ` ```json ` 行和结尾的 ` ``` `。用 `lastIndexOf("```")` 找闭合 fence，避免内容中出现 ` ``` ` 时误截断。

### 第 2 步：提取 JSON 体

```java
private String extractJsonBody(String text) {
    int objStart = text.indexOf('{');
    int objEnd = text.lastIndexOf('}');
    if (objStart >= 0 && objEnd > objStart) {
        return text.substring(objStart, objEnd + 1);
    }

    int arrStart = text.indexOf('[');
    int arrEnd = text.lastIndexOf(']');
    if (arrStart >= 0 && arrEnd > arrStart) {
        return text.substring(arrStart, arrEnd + 1);
    }
    return text;
}
```

找到第一个 `{` 和最后一个 `}`，提取中间的内容。这能处理"JSON 前后有解释文字"的情况。同时也支持数组（`[` 和 `]`）。

这个方法假设 JSON 只有一层——如果 LLM 返回了多个独立的 JSON 对象，它只会取第一个到最后一个 `}` 之间的内容。对于这个项目的场景（每次只期望一个 JSON 对象），这够用了。

### 第 3 步：转义控制字符

这是最关键也最精巧的一步。LLM 经常在 JSON 字符串值里输出字面换行符：

```json
{"summary": "第一行
第二行"}
```

这不是合法的 JSON——字符串里的换行必须写成 `\n`。但直接全局替换 `\n` 为 `\n` 会破坏 JSON 结构本身（键值之间的换行是合法的空白）。

解决方案是一个 **逐字符状态机**，追踪当前是否在 JSON 字符串内部：

```java
private String escapeControlCharsInJsonStrings(String text) {
    StringBuilder out = new StringBuilder(text.length() + 16);
    boolean inString = false;
    boolean escaped = false;

    for (int i = 0; i < text.length(); i++) {
        char ch = text.charAt(i);

        // 不在字符串内：原样输出，遇到引号进入字符串模式
        if (!inString) {
            out.append(ch);
            if (ch == '"') inString = true;
            continue;
        }

        // 上一个字符是反斜杠：当前字符是转义序列的一部分，原样输出
        if (escaped) {
            out.append(ch);
            escaped = false;
            continue;
        }

        // 当前字符是反斜杠：标记下一个字符是转义的
        if (ch == '\\') {
            out.append(ch);
            escaped = true;
            continue;
        }

        // 遇到引号：字符串结束
        if (ch == '"') {
            out.append(ch);
            inString = false;
            continue;
        }

        // 在字符串内的控制字符：转义
        if (ch == '\n') { out.append("\\n"); continue; }
        if (ch == '\r') { out.append("\\r"); continue; }
        if (ch == '\t') { out.append("\\t"); continue; }
        if (ch < 0x20)  { out.append(String.format("\\u%04x", (int) ch)); continue; }

        out.append(ch);
    }
    return out.toString();
}
```

状态机有三个状态变量：
- `inString`：当前是否在 JSON 字符串内
- `escaped`：上一个字符是否是 `\`（处理 `\"`、`\\` 等转义序列）
- `ch < 0x20`：所有 ASCII 控制字符（包括换行、回车、制表符）

只有在 `inString && !escaped` 时才替换控制字符。这保证了 JSON 结构的键值分隔、缩进换行不受影响。

### 修复后的重试

```java
private <T> T parseWithRepair(BeanOutputConverter<T> outputConverter, String rawContent, ...) {
    try {
        return outputConverter.convert(rawContent);  // 先尝试直接解析
    } catch (Exception originalError) {
        String repaired = repairJson(rawContent);
        if (repaired == null || repaired.equals(rawContent)) {
            throw originalError;  // 没有修复空间，抛原始错误
        }
        try {
            T value = outputConverter.convert(repaired);  // 修复后重试
            log.info("{}结构化输出兜底修复后解析成功", logContext);
            return value;
        } catch (Exception repairedError) {
            originalError.addSuppressed(repairedError);  // 修复也失败，抛原始错误
            throw originalError;
        }
    }
}
```

注意：修复失败时抛的是 `originalError`（原始错误），不是 `repairedError`（修复后的错误）。原始错误更能反映 LLM 输出的真实问题。修复错误作为 suppressed exception 附加，需要时可以追溯。

## 第 3 层：LLM 重试与错误注入

如果本地修复也失败了，进入 LLM 重试。

### 重试 Prompt 构建

```java
private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
    StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
        .append("\n\n")
        .append(STRICT_JSON_INSTRUCTION)
        .append("\n上次输出解析失败，请仅返回合法 JSON。");

    if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
        prompt.append("\n上次解析错误：")
            .append(sanitizeErrorMessage(lastError.getMessage()));
    }
    return prompt.toString();
}
```

重试时的 system prompt 包含三部分：
1. 原始 system prompt（含 JSON schema 格式说明）
2. `STRICT_JSON_INSTRUCTION`（4 条严格 JSON 规则）
3. 错误注入："上次输出解析失败，请仅返回合法 JSON。\n上次解析错误：{sanitized error}"

**错误注入是关键**。它让 LLM 知道自己上次错在哪里。比如错误信息是 `"Cannot deserialize instance of 'int' from String"`，LLM 就知道某个字段应该返回数字而不是字符串。

错误信息经过 sanitize：单行化、截断到 200 字符。这避免了过长的错误信息占用 token 额度，也避免了错误信息中包含的特殊字符干扰 prompt。

### 重试判断

```java
private boolean shouldRetry(Exception e, int attempt) {
    return attempt < maxAttempts && isStructuredOutputError(e);
}

private boolean isStructuredOutputError(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
        if (current instanceof StructuredOutputException) {
            return true;
        }
        String message = current.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase();
            if (normalized.contains("illegal unquoted character")
                || normalized.contains("cannot deserialize")
                || normalized.contains("unexpected character")
                || normalized.contains("unrecognized token")
                || normalized.contains("json parse")
                || normalized.contains("jsonmappingexception")) {
                return true;
            }
        }
        current = current.getCause();
    }
    return false;
}
```

两个条件：
1. 还有重试次数（默认最多 2 次调用）
2. 错误是结构化输出相关的

`isStructuredOutputError` 遍历整个异常链（`getCause()` 递归），检查是否包含 JSON 解析相关的关键词。这比只检查顶层异常更可靠——有时候 JSON 解析错误被包了好几层 `RuntimeException`。

不是所有错误都值得重试。网络超时、API key 无效、限流——这些不是 JSON 格式的问题，重试也不会改善。只有确实是 JSON 解析失败时才重试。

## 三层 Prompt 的叠加

完整的 system prompt 实际上是三层叠加的：

```
┌─────────────────────────────────────────────┐
│ 第 1 层：模板文件内容                          │
│ （resume-analysis-system.st / agent-system.st）│
│ 包含业务规则、评分标准、输出格式说明              │
├─────────────────────────────────────────────┤
│ 第 2 层：BeanOutputConverter.getFormat()       │
│ 机器可读的 JSON Schema 格式指令                │
├─────────────────────────────────────────────┤
│ 第 3 层：STRICT_JSON_INSTRUCTION               │
│ 4 条严格 JSON 规则                            │
├─────────────────────────────────────────────┤
│ （仅重试时）上次输出解析失败 + 错误信息          │
└─────────────────────────────────────────────┘
```

模板文件用自然语言描述 JSON 结构（"overallScore: 整数，总分 0-100"），`BeanOutputConverter` 用 JSON Schema 描述（机器可读），`STRICT_JSON_INSTRUCTION` 用规则约束格式。三层互补，覆盖了 LLM 可能"跑偏"的各种方向。

## 不同模块的失败策略

`StructuredOutputInvoker` 是一个通用组件，但不同调用方对失败的处理不同：

| 模块 | 失败时的行为 | 为什么 |
| --- | --- | --- |
| Agent 决策 | 降级为直接文本回复 | Agent 不能因为决策失败就卡住，降级回复比报错好 |
| 面试问题生成 | 使用默认问题集 | 用户正在面试，不能因为 LLM 返回格式错误就中断 |
| 评估摘要聚合 | 使用批次聚合结果 | 摘要是锦上添花，批次结果本身已经够用 |
| 简历分析 | 抛异常，异步任务标记 FAILED | 简历分析没有降级路径，必须拿到结构化结果 |
| 面试评估（批次） | 抛异常 | 评估必须拿到分数，不能用假数据 |

有降级路径的场景（Agent 决策、问题生成、评估摘要）直接 catch 异常，返回兜底数据。没有降级路径的场景（简历分析、面试评估）让异常冒泡，由上层（通常是异步任务框架）标记为 FAILED，用户可以在界面上看到错误并手动重试。

## 错误分类与翻译

当 `StructuredOutputInvoker` 抛出异常后，调用方通常需要把它翻译成统一的错误码。`AiErrorTranslator` 做这件事：

```java
public AiErrorDescriptor translate(Throwable throwable) {
    // 关键词匹配：API key、quota、rate limit、timeout、network、structured output
    // 返回对应的 ErrorCode + userMessage + retryable
}
```

它通过关键词匹配（遍历异常链的 message）把原始异常分类。结构化输出错误会被分类为 `AI_RESPONSE_FORMAT_INVALID(7007)` + `retryable=true`。

这个分类直接影响了异步任务的重试行为——上一篇博客讲的 `AnalyzeStreamConsumer` 就是根据 `retryable` 标记决定是否在 Stream 层重试。

## 配置

```yaml
app:
  ai:
    structured-max-attempts: 2          # LLM 调用次数（含首次）
    structured-include-last-error: true  # 重试时是否注入上次错误
```

`structured-max-attempts: 2` 意味着最多调用 2 次 LLM。第 1 次失败后，本地修复尝试一次；修复也失败，第 2 次调用 LLM（带错误注入）。2 次都失败就抛异常。

为什么默认是 2 而不是更多？因为结构化输出失败通常是 prompt 或模型的问题，不是网络抖动。多调几次大概率还是失败，白白浪费 token。2 次足够覆盖"偶发格式偏差"的情况，更多次的重试收益递减。

`structured-include-last-error: true` 让 LLM 看到具体的错误信息。实测中这显著提高了重试成功率——LLM 看到 `"Cannot deserialize 'int' from String 'good'"` 后，第二次通常会返回数字而不是字符串。

## 实际效果

在这个项目中，`StructuredOutputInvoker` 被 7 个调用点使用，覆盖了 Agent 决策、简历分析、领域分类、面试问题生成、面试评估（批次+摘要）、只读委派。

实际运行中，第 1 次直接解析的成功率大约在 85-90%。剩下的 10-15% 中，大部分被 JSON 修复（去代码块 + 提取 JSON 体 + 控制字符转义）救回来了。真正走到 LLM 重试的大约只有 2-5%。重试后的成功率在 90% 以上。

总体而言，三层防御把结构化输出的有效率从 ~90% 提升到了 ~99.5%。剩下 ~0.5% 的失败主要集中在模型能力不足（比如返回了完全不相关的 JSON 结构）或 prompt 设计问题上，这些靠重试解决不了，需要改 prompt 或换模型。

## 设计哲学

### 1. 本地修复优先于 LLM 重试

JSON 修复是纯字符串操作，不调用 LLM，不消耗 token，不增加延迟。能本地修好的问题（代码块包裹、前后缀文字、控制字符）就不要花钱重试。LLM 重试是最后手段。

### 2. 错误注入让重试有意义

不带错误信息的重试是盲目的——LLM 不知道上次错在哪里，大概率会犯同样的错。注入上次的错误信息（截断到 200 字符）让重试变成了"有指导的自我修正"。

### 3. 不是所有错误都值得重试

`isStructuredOutputError` 的关键词匹配确保了只有 JSON 格式问题才触发重试。网络超时、API key 无效、限流——这些不会因为重试而改善，直接抛出。

### 4. 修复失败时抛原始错误

`parseWithRepair` 在修复也失败时抛 `originalError` 而不是 `repairedError`。原始错误更能反映 LLM 输出的真实问题，调试时更有价值。

### 5. 调用方决定失败策略

`StructuredOutputInvoker` 不决定失败后怎么办——它只负责重试和修复。降级、报错、使用默认值，这些策略由调用方根据业务场景决定。

## 局限性

- **关键词匹配的异常分类是脆弱的**。`isStructuredOutputError` 靠匹配 `"cannot deserialize"`、`"unrecognized token"` 等关键词判断是否是 JSON 错误。如果 LLM SDK 更新了错误消息的措辞，匹配可能失效。更健壮的做法是检查特定的异常类型，但 Spring AI 的异常层次不够细。
- **JSON 修复不处理嵌套结构问题**。如果 LLM 返回了嵌套错误的 JSON（比如少了闭合的 `}`），`extractJsonBody` 可能截取出错误的片段。这种情况只能靠 LLM 重试解决。
- **没有 schema 级别的修复**。如果 LLM 返回的 JSON 结构正确但字段类型错误（比如 `overallScore` 返回了字符串 `"seventy-eight"`），本地修复无能为力，只能靠 LLM 重试。
- **重试 prompt 的 token 成本**。重试时把整个 system prompt（含 JSON schema）再发一遍，token 成本是首次调用的 ~1.5 倍（多了错误注入部分）。对于长 system prompt 的场景（比如简历分析），这个成本不可忽视。

## 结语

LLM 的结构化输出不可靠，这是当前所有 LLM 应用都要面对的现实。三层防御（直接解析 → JSON 修复 → LLM 重试）是一个务实的工程方案：本地修复处理常见的格式问题，错误注入让重试有针对性，调用方根据业务场景决定失败策略。

不需要追求 100% 的结构化输出成功率——那需要完美的 prompt 和完美的模型，两者都不存在。把成功率从 90% 提升到 99.5%，剩下的 0.5% 交给降级和手动重试，这已经是生产可用的水平了。

---

*本文代码来自 Interview Agent 项目 `common/ai/` 目录，关键文件：`StructuredOutputInvoker.java`、`AiErrorTranslator.java`、`StructuredOutputException.java`。*
