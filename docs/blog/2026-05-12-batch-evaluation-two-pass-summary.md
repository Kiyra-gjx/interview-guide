# 一次 LLM 调用评估不完 15 道题：分批评估与两轮汇总的面试评分架构

> 项目地址：[interview-agent](https://github.com/Kiyra-gjx/interview-agent)
> 技术栈：Java 21 / Spring Boot 4.0 / Spring AI 2.0 / DashScope (qwen-plus)

## 问题：题目多了，一次调用扛不住

模拟面试场景下，一场面试可能有 8-15 道题。每道题包含题目、分类、用户回答，加上简历摘要和评估指令，一次 LLM 调用的 prompt 长度轻松超过 8000 token。

试过一次调用评估全部题目，遇到了三个问题：

1. **Token 超限**。15 道题的问答记录 + 简历 + 系统指令 + 输出 JSON，总 token 数逼近模型上下文窗口。尾部题目被截断，评估结果不完整。
2. **评估质量随题目增多而下降**。LLM 对前几道题的评估很详细，后面的题目开始敷衍——反馈变短、分数趋同、参考答案缺失。这是典型的"注意力衰减"。
3. **总分不可信**。模型自报的 `overallScore` 经常和逐题分数的平均值不一致。有时候 10 道题平均 72 分，模型却报了个 80。

解决方案：**分批评估 + 两轮汇总**。先按批次独立评估，再做一次全局总结。

## 整体流程

```
15 道面试题
    │
    ▼
┌─────────────────────────────────┐
│ 第一轮：分批评估                  │
│ 批次1 (Q1-Q8)  ──▶ LLM ──▶ 评估结果1 │
│ 批次2 (Q9-Q15) ──▶ LLM ──▶ 评估结果2 │
└─────────┬───────────────────────┘
          │ 合并
          ▼
┌─────────────────────────────────┐
│ 本地聚合                        │
│ 逐题合并、分类统计、兜底反馈      │
└─────────┬───────────────────────┘
          │
          ▼
┌─────────────────────────────────┐
│ 第二轮：全局总结                  │
│ 分类摘要 + 题目亮点 ──▶ LLM ──▶ 总结 │
└─────────┬───────────────────────┘
          │ 失败？
          ▼
┌─────────────────────────────────┐
│ 降级：使用本地聚合结果            │
└─────────────────────────────────┘
```

两次 LLM 调用，中间一次本地聚合，最后一次降级兜底。

## 第一轮：分批评估

```java
private List<BatchEvaluationResult> evaluateInBatches(
    String sessionId, String resumeSummary, List<InterviewQuestionDTO> questions
) {
    List<BatchEvaluationResult> results = new ArrayList<>();
    for (int start = 0; start < questions.size(); start += evaluationBatchSize) {
        int end = Math.min(start + evaluationBatchSize, questions.size());
        List<InterviewQuestionDTO> batchQuestions = questions.subList(start, end);
        EvaluationReportDTO report = evaluateBatch(sessionId, resumeSummary, batchQuestions, start, end);
        results.add(new BatchEvaluationResult(start, end, report));
    }
    return results;
}
```

默认每批 8 道题（`evaluationBatchSize=8`）。15 道题分两批：Q1-Q8、Q9-Q15。

为什么是 8？经验数字。8 道题的问答记录大约 3000-4000 token，加上简历和系统指令，总 prompt 在 6000 token 左右，留够了输出空间。超过 8 道，评估质量开始下降。

每批独立调用 LLM，互不依赖。每批的输出是一份完整的评估 JSON：

```java
private record EvaluationReportDTO(
    int overallScore,                    // 批次总分（后面不用）
    String overallFeedback,              // 批次综合反馈
    List<String> strengths,              // 批次优点
    List<String> improvements,           // 批次改进
    List<QuestionEvaluationDTO> questionEvaluations  // 逐题评估
) {}
```

**关键设计：每批都包含 `overallScore`、`strengths`、`improvements`，但这些字段在后续会被第二轮覆盖**。保留它们是为了降级兜底——如果第二轮失败，至少有每批的原始反馈可以用。

## 本地聚合：合并批次结果

分批评估完成后，先做一次本地聚合：

```java
List<QuestionEvaluationDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
String fallbackOverallFeedback = mergeOverallFeedback(batchResults);
List<String> fallbackStrengths = mergeListItems(batchResults, true);
List<String> fallbackImprovements = mergeListItems(batchResults, false);
```

### 逐题合并

```java
private List<QuestionEvaluationDTO> mergeQuestionEvaluations(List<BatchEvaluationResult> batchResults) {
    List<QuestionEvaluationDTO> merged = new ArrayList<>();
    for (BatchEvaluationResult result : batchResults) {
        int expectedSize = result.endIndex() - result.startIndex();
        List<QuestionEvaluationDTO> current = result.report().questionEvaluations();
        for (int i = 0; i < expectedSize; i++) {
            if (i < current.size() && current.get(i) != null) {
                merged.add(current.get(i));
            } else {
                // 缺失项补 0 分兜底
                merged.add(new QuestionEvaluationDTO(
                    result.startIndex() + i, 0,
                    "该题未成功生成评估结果，系统按 0 分处理。",
                    "", List.of()
                ));
            }
        }
    }
    return merged;
}
```

防御性编程：如果某批的 `questionEvaluations` 比预期少（LLM 输出被截断），缺失的题目会被补一个 0 分兜底对象。这保证了最终报告的题目数量和原始题目数量一致——不会出现"第 15 题的评估结果丢了"的情况。

### 反馈聚合

```java
private List<String> mergeListItems(List<BatchEvaluationResult> batchResults, boolean strengthsMode) {
    Set<String> merged = new LinkedHashSet<>();
    for (BatchEvaluationResult result : batchResults) {
        List<String> items = strengthsMode ? result.report().strengths() : result.report().improvements();
        items.stream().filter(item -> item != null && !item.isBlank())
            .map(String::trim).forEach(merged::add);
    }
    return merged.stream().limit(8).toList();
}
```

用 `LinkedHashSet` 去重保序，最多保留 8 条。这是降级兜底用的——正常流程会被第二轮覆盖。

## 第二轮：全局总结

本地聚合只是机械合并。两批的 `strengths` 可能重复（两批都提到"基础知识扎实"），`overallFeedback` 拼在一起不连贯。需要一次 LLM 调用来做"总结的总结"。

```java
private FinalSummaryDTO summarizeBatchResults(
    String sessionId, String resumeSummary,
    List<InterviewQuestionDTO> questions,
    List<QuestionEvaluationDTO> evaluations,
    String fallbackOverallFeedback,
    List<String> fallbackStrengths,
    List<String> fallbackImprovements
) {
    // 1. 把 15 道题的评估结果压缩成两个摘要
    String categorySummary = buildCategorySummary(questions, evaluations);
    String questionHighlights = buildQuestionHighlights(questions, evaluations);

    // 2. 构造总结 prompt，把压缩摘要 + 批次兜底反馈一起传给 LLM
    Map<String, Object> variables = new HashMap<>();
    variables.put("resumeText", resumeSummary);
    variables.put("categorySummary", categorySummary);
    variables.put("questionHighlights", questionHighlights);
    variables.put("fallbackOverallFeedback", fallbackOverallFeedback);
    variables.put("fallbackStrengths", String.join("\n", fallbackStrengths));
    variables.put("fallbackImprovements", String.join("\n", fallbackImprovements));

    // 3. LLM 生成总结
    FinalSummaryDTO dto = structuredOutputInvoker.invoke(...);

    // 4. 本地清洗：空字段回退到批次聚合结果
    return new FinalSummaryDTO(
        dto.overallFeedback() != null ? dto.overallFeedback() : fallbackOverallFeedback,
        sanitizeSummaryItems(dto.strengths(), fallbackStrengths),
        sanitizeSummaryItems(dto.improvements(), fallbackImprovements)
    );
}
```

### 压缩输入：分类摘要 + 题目亮点

第二轮的输入不是原始的 15 道题，而是两份压缩摘要：

**分类摘要**（按技术类别聚合）：

```
- Java基础: 平均分 78, 题数 4
- Spring: 平均分 65, 题数 3
- 并发: 平均分 82, 题数 2
- 数据库: 平均分 55, 题数 3
```

**题目亮点**（每题一行，限 50 字）：

```
- Q1 | HashMap 和 ConcurrentHashMap 的区别 | 分数:85 | 反馈:回答准确，提到了分段锁...
- Q2 | Spring AOP 的实现原理 | 分数:70 | 反馈:提到 JDK 动态代理但没有深入...
- Q3 | ...
```

```java
private String buildQuestionHighlights(List<InterviewQuestionDTO> questions,
                                        List<QuestionEvaluationDTO> evaluations) {
    List<String> highlights = new ArrayList<>();
    for (int i = 0; i < questions.size(); i++) {
        String shortQuestion = questionText.length() > 50
            ? questionText.substring(0, 50) + "..." : questionText;
        String shortFeedback = feedback.length() > 80
            ? feedback.substring(0, 80) + "..." : feedback;
        highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s",
            q.questionIndex() + 1, shortQuestion, score, shortFeedback));
    }
    return highlights.stream().limit(20).collect(Collectors.joining("\n"));
}
```

为什么要压缩？因为第二轮的输入需要包含**全局信息**（所有题目的分数和反馈），但不能把 15 道题的完整评估再塞一遍——那就退化成了一次调用评估全部题。压缩后的摘要大约 500-800 token，加上系统指令和输出格式，总 prompt 在 2000 token 以内。

### 总结 prompt 设计

```
你是一位资深技术面试评审专家，负责对"分批评估结果"进行二次汇总。

基于输入的类别得分概览、题目高亮信息以及分批初始结论，输出更一致、更聚焦的综合评估结论。

constraints:
- 只能使用输入信息，不要编造不存在的能力或经历
- strengths 与 improvements 各输出 3-6 条，避免重复
```

关键约束：**"只能使用输入信息"**。第二轮 LLM 看到的是压缩摘要，不是原始回答。如果让它自由发挥，可能会"合理推断"出候选人没说过的东西。限制输入就是限制幻觉。

## 分数重算：永远不信任模型的总分

```java
// 总分只信任最终落地的题目分数，不直接使用模型返回的 overallScore
int overallScore;
if (answeredCount == 0) {
    overallScore = 0;
} else {
    overallScore = (int) questionDetails.stream()
        .mapToInt(QuestionEvaluation::score)
        .average().orElse(0);
}
```

**总分从逐题分数的平均值计算，不使用模型返回的 `overallScore`**。

为什么？因为模型的 `overallScore` 和逐题分数经常不一致。有时候 10 道题平均 72 分，模型报 80——它倾向于"给人面子"。有时候题目难、平均 45 分，模型报 55——它倾向于"往上调一点"。

平均值是确定性计算，不受模型主观倾向影响。

## 降级策略：第二轮失败时的兜底

```java
try {
    FinalSummaryDTO dto = summarizeBatchResults(...);
    // 正常路径
} catch (Exception e) {
    log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
    return new FinalSummaryDTO(
        fallbackOverallFeedback,   // 批次反馈的简单拼接
        fallbackStrengths,         // 批次优点的去重合并
        fallbackImprovements       // 批次改进的去重合并
    );
}
```

第二轮失败时，用本地聚合的结果兜底。用户体验上：`overallFeedback` 会是两段独立的批次反馈拼在一起，不够连贯但信息完整；`strengths` 和 `improvements` 是去重后的合并结果，可能有重复但不缺项。

**降级是无感的**——前端不会知道第二轮失败了，只是报告的综合评语质量稍差。

## 未回答的题目：强制 0 分

```java
boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
int score = hasAnswer && eval != null ? eval.score() : 0;
```

如果用户跳过了某道题，无论模型给了多少分，最终分数都是 0。

模型有时候会对"未回答"的题目给一个安慰分（30-40 分），理由是"虽然没有回答，但从整体表现来看应该具备这方面能力"。这不合理——没回答就是 0 分，不存在"应该会"的说法。

## 设计哲学

### 1. 分批是手段，不是目的

分批的唯一原因是单次调用的上下文窗口不够。如果模型支持 128K token 且长上下文评估质量不下降，一次调用是最优解——没有批次边界、没有合并逻辑、没有第二轮调用。分批引入了额外的复杂度和一次 LLM 调用的延迟，是被上下文限制逼出来的。

### 2. 两轮分工不同

第一轮的任务是**精确评估**——逐题打分、逐题写反馈、逐题给参考答案。每批独立，不需要看其他批次的结果。

第二轮的任务是**全局综合**——分析整体优势和不足、写连贯的综合评语、去重合并优点和改进。它不需要再看每道题的详细评估，只需要压缩后的分类摘要和题目亮点。

两轮的输入不同、输出不同、prompt 不同。把它们混在一轮里，要么上下文太长质量下降，要么压缩太狠丢失信息。

### 3. 降级要保完整，不要保质量

第二轮失败时，降级方案保证的是**字段完整**（overallFeedback、strengths、improvements 都有值），不是**质量最优**。简单拼接的反馈不够连贯，但信息不丢。用户至少能看到每批的评估结果，而不是一个空白报告。

### 4. 总分用算术，不用模型

模型的 `overallScore` 是主观判断，逐题分数的平均值是客观计算。在两者冲突时，信任计算值。这不是不信任模型——模型在逐题评估上的专业性远超算术平均。但在总分这种"需要对齐"的场景下，确定性比主观性更可靠。

### 5. 兜底对象要防 null 链

```java
QuestionEvaluationDTO eval = i < evaluationsSize ? evaluations.get(i) : null;
String feedback = eval != null && eval.feedback() != null
    ? eval.feedback() : "该题未成功生成评估反馈。";
int score = hasAnswer && eval != null ? eval.score() : 0;
```

从 LLM 输出到最终报告，中间经历了 JSON 解析、批次合并、第二轮总结三个环节，任何一个都可能产生 null。如果不在最后一步做防御性兜底，前端会收到 null feedback 或 null score，渲染时崩掉。所以每个字段都有 fallback 值——宁可展示"该题未成功生成评估结果"，也不要展示空白。

## 局限性

- **批次边界处可能有评分不一致**。如果 Q8 和 Q9 是同一个知识点的追问（比如 Q8 问 HashMap 原理，Q9 问 ConcurrentHashMap），分到不同批次后，模型看不到前一批的上下文，可能对 Q9 的评估独立打分，忽略了和 Q8 的关联。
- **第二轮的压缩摘要有信息损失**。题目亮点限制 50 字、反馈限制 80 字，长反馈会被截断。如果关键信息在截断部分，第二轮的总结可能遗漏。
- **批次大小是固定值**。8 道题的 batch size 对大多数场景合适，但如果题目特别长（比如包含代码片段的编程题），8 道题的 token 数可能超限。当前没有按 token 数动态切分批次。
- **总分计算不区分题目权重**。每道题的权重相同（简单平均），但实际面试中系统设计题应该比基础概念题权重更高。当前没有题目权重机制。
- **第二轮增加了一次 LLM 调用的延迟**。qwen-plus 的响应时间通常 2-4 秒，第二轮总结增加了整体评估时间。对于异步任务（Redis Stream）来说可以接受，但如果未来要做实时评估，这个延迟需要优化。

## 结语

LLM 评估不是一次调用就能搞定的。题目多了要分批，分批了要合并，合并了要总结，总结了要兜底。这四步看起来复杂，但每一步都有明确的问题驱动——不是过度设计，是被实际场景逼出来的。

如果你的项目也需要 LLM 评估超过 5 道题的场景，建议从一开始就考虑分批策略。等发现长上下文评估质量下降再改，评估结果已经不准了，用户已经看到了有问题的报告。

---

*本文代码来自 Interview Agent 项目 `modules/interview/service/` 和 `resources/prompts/` 目录，关键文件：`AnswerEvaluationService.java`、`StructuredOutputInvoker.java`、`interview-evaluation-*.st`。*
