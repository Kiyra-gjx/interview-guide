# 实施方案：统一评估服务（分批评估 + 二次汇总 + 降级兜底）

## 1. 设计目标

面试评估是 Agent 的核心 Tool 之一。当面试题目较多（>5 题）时，单次 LLM 调用容易因 token 限制导致输出截断或质量下降。本方案引入分批评估 + 二次汇总模式：

1. **分批评估** — 按 batchSize 切分问答记录，每批独立评估
2. **二次汇总** — 批次结果合并后再做一次 LLM 总结，生成连贯的整体评价
3. **降级兜底** — 任何一步失败都有 fallback，不会整体失败
4. **与 Agent Tool 集成** — 作为 Agent 的评估 Tool 的底层实现

## 2. 架构设计

```
Agent Tool: InterviewGapAnalysisTool
    ↓ 调用
UnifiedEvaluationService.evaluate(chatClient, sessionId, qaRecords, resumeText)
    ↓
┌─────────────────────────────────────────┐
│  Phase 1: 分批评估                       │
│  qaRecords → [batch1, batch2, batch3]   │
│  每批独立调用 LLM → BatchReportDTO       │
│  失败的批次返回 null（零分兜底）          │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│  Phase 2: 合并批次结果                   │
│  - 合并 questionEvaluations（按序拼接）  │
│  - 合并 strengths / improvements（去重） │
│  - 合并 overallFeedback（拼接）          │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│  Phase 3: 二次汇总                       │
│  将合并结果 + 分类摘要 + 题目亮点        │
│  交给 LLM 生成连贯的最终评语             │
│  失败时降级到 Phase 2 的聚合结果         │
└─────────────────────────────────────────┘
    ↓
EvaluationReport（最终报告）
```

## 3. 改动范围

### 3.1 新增文件

| 文件路径 | 说明 |
|---------|------|
| `common/evaluation/UnifiedEvaluationService.java` | 统一评估服务（核心） |
| `common/evaluation/EvaluationReport.java` | 评估报告 DTO |
| `common/evaluation/QaRecord.java` | 问答记录输入 |
| `common/evaluation/InterviewEvaluationProperties.java` | 评估配置属性 |
| `resources/prompts/evaluation-system.st` | 批次评估 system prompt 模板 |
| `resources/prompts/evaluation-user.st` | 批次评估 user prompt 模板 |
| `resources/prompts/evaluation-summary-system.st` | 汇总 system prompt 模板 |
| `resources/prompts/evaluation-summary-user.st` | 汇总 user prompt 模板 |

### 3.2 修改文件

| 文件路径 | 改动说明 |
|---------|---------|
| `modules/agent/tool/InterviewGapAnalysisTool.java` | 底层改用 UnifiedEvaluationService |
| `modules/interview/service/AnswerEvaluationService.java` | 底层改用 UnifiedEvaluationService |
| `application.yml` | 新增 `app.ai.evaluation.*` 配置段 |

## 4. 接口设计

### 4.1 UnifiedEvaluationService

```java
@Service
public class UnifiedEvaluationService {

    /**
     * 评估面试问答
     */
    public EvaluationReport evaluate(
        ChatClient chatClient,
        String sessionId,
        List<QaRecord> qaRecords,
        String resumeText
    );

    /**
     * 带参考基线的评估（有 Skill references 的场景）
     */
    public EvaluationReport evaluate(
        ChatClient chatClient,
        String sessionId,
        List<QaRecord> qaRecords,
        String resumeText,
        String referenceContext
    );
}
```

### 4.2 QaRecord

```java
public record QaRecord(
    int questionIndex,      // 题目序号（0-based）
    String question,        // 题目内容
    String category,        // 分类 key（如 "JAVA_CORE"）
    String userAnswer       // 用户回答（null 表示未回答）
) {}
```

### 4.3 EvaluationReport

```java
public record EvaluationReport(
    String sessionId,
    int totalQuestions,
    int overallScore,                       // 0-100
    List<CategoryScore> categoryScores,     // 按分类的平均分
    List<QuestionEvaluation> questionEvaluations,
    String overallFeedback,                 // 整体评语
    List<String> strengths,                 // 优势（最多 8 条）
    List<String> improvements,             // 改进建议（最多 8 条）
    List<ReferenceAnswer> referenceAnswers  // 参考答案
) {
    public record CategoryScore(String category, int averageScore, int questionCount) {}

    public record QuestionEvaluation(
        int questionIndex, String question, String category,
        String userAnswer, int score, String feedback
    ) {}

    public record ReferenceAnswer(
        int questionIndex, String question,
        String referenceAnswer, List<String> keyPoints
    ) {}
}
```

## 5. 核心逻辑

### 5.1 分批策略

```java
private List<BatchResult> evaluateInBatches(ChatClient chatClient, String sessionId,
                                             String resumeContext, List<QaRecord> qaRecords,
                                             String referenceContext) {
    List<BatchResult> results = new ArrayList<>();
    for (int start = 0; start < qaRecords.size(); start += batchSize) {
        int end = Math.min(start + batchSize, qaRecords.size());
        List<QaRecord> batch = qaRecords.subList(start, end);
        BatchReportDTO report = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, batch);
        results.add(new BatchResult(start, end, report));
    }
    return results;
}
```

### 5.2 降级兜底

```java
// 批次评估失败 → 返回 null，合并时用零分占位
private BatchReportDTO evaluateBatch(...) {
    try {
        return structuredOutputInvoker.invoke(...);
    } catch (Exception e) {
        log.error("批次评估失败: sessionId={}, batchSize={}", sessionId, batch.size(), e);
        return null;
    }
}

// 二次汇总失败 → 降级到批次聚合结果
private SummaryDTO summarizeBatchResults(...) {
    try {
        return structuredOutputInvoker.invoke(...);
    } catch (Exception e) {
        log.warn("二次汇总失败，降级到批次聚合: sessionId={}", sessionId);
        return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
    }
}
```

### 5.3 合并去重

```java
private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
    Set<String> merged = new LinkedHashSet<>();
    for (BatchResult result : batchResults) {
        if (result.report() == null) continue;
        List<String> items = strengthsMode ? result.report().strengths() : result.report().improvements();
        if (items != null) {
            items.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).forEach(merged::add);
        }
    }
    return merged.stream().limit(8).toList();
}
```

### 5.4 二次汇总输入

汇总 prompt 接收的信息：
- 简历背景（截断到 3000 字符）
- 参考基线（截断到 6000 字符）
- 分类摘要（每个分类的平均分和题数）
- 题目亮点（每题的简短问题 + 分数 + 反馈摘要，最多 20 题）
- 批次聚合的 fallback 结果（作为参考）

## 6. Agent Tool 集成

### 6.1 InterviewGapAnalysisTool 改造

```java
@Override
public AgentToolResult execute(AgentToolContext context) {
    String sessionId = context.getParameter("sessionId");

    // 加载问答记录
    List<QaRecord> qaRecords = loadQaRecords(sessionId);

    // 加载简历摘要
    String resumeText = loadResumeSummary(sessionId);

    // 加载参考基线
    String referenceContext = interviewSkillService
        .buildEvaluationReferenceSectionSafe(getSkillId(sessionId));

    // 调用统一评估
    ChatClient chatClient = llmProviderRegistry.getPlainChatClient(null);
    EvaluationReport report = evaluationService.evaluate(
        chatClient, sessionId, qaRecords, resumeText, referenceContext
    );

    return AgentToolResult.success(formatReport(report));
}
```

## 7. 配置

```yaml
app:
  ai:
    evaluation:
      batch-size: 5
      system-prompt-path: classpath:prompts/evaluation-system.st
      user-prompt-path: classpath:prompts/evaluation-user.st
      summary-system-prompt-path: classpath:prompts/evaluation-summary-system.st
      summary-user-prompt-path: classpath:prompts/evaluation-summary-user.st
```

## 8. 集成步骤

1. 创建 `QaRecord` + `EvaluationReport` 数据类
2. 创建 `InterviewEvaluationProperties`
3. 编写 4 个 prompt 模板文件
4. 实现 `UnifiedEvaluationService`
5. 修改 `InterviewGapAnalysisTool` 使用新服务
6. 修改 `AnswerEvaluationService` 使用新服务
7. 编写单元测试
8. 编写集成测试

## 9. 测试要点

| 场景 | 预期行为 |
|------|---------|
| 3 题（< batchSize） | 不分批，直接评估 |
| 8 题（> batchSize） | 分 2 批评估 + 汇总 |
| 15 题 | 分 3 批评估 + 汇总 |
| 某批次失败 | 该批次零分，其他正常，汇总降级 |
| 汇总失败 | 使用批次聚合结果 |
| 全部失败 | 抛出评估失败异常，交给异步任务重试 / FAILED 链路处理 |
| 未回答的题目 | 自动零分，不影响其他题目 |

## 10. 当前落地说明

已按本方案落地统一评估核心链路，但 Agent Tool 接入做了一个边界收敛：

- `AnswerEvaluationService` 已改为 `UnifiedEvaluationService` 的适配层，面试完成后的正式评估、异步评估和报告落库都统一走新服务。
- `analyze_interview_gaps` 仍保持 `READ_ONLY` 语义。它只消费已落库的评估结果做本地短板分析，不会在工具执行期间临时调用 LLM 或隐式落库。
- 单个批次失败时仍按该批次 0 分兜底；但非空问答下所有批次都失败时会抛出 `INTERVIEW_EVALUATION_FAILED`，避免把全 0 降级结果保存成正常 `EVALUATED` 报告。

已覆盖的测试：

- `UnifiedEvaluationServiceTest`：分批评估、单批失败零分兜底、汇总失败降级、全部批次失败抛异常、未回答题目强制 0 分。
- `AnswerEvaluationServiceTest`：旧业务报告模型对统一评估结果的适配。
- `InterviewToolContextServiceTest`：未评估会话不会进入短板分析详情加载，最近已评估会话仍可作为 fallback。
