# S3-01 Interview Context Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent 增加 3 个轻量分析型 interview domain 工具：`get_interview_history_summary`、`analyze_interview_gaps`、`suggest_follow_up_questions`，并保持现有 Tool 契约、trace、memory、prompt 与主链路兼容。

**Architecture:** 保持 `agent` 编排 `interview` 的现有边界，在 `modules/agent/tool/interview/` 中增加共享上下文读取层与纯规则分析层，再由 3 个 Tool 负责输入标准化和 `AgentToolResult` 组装；同时补齐 memory phase、prompt 描述和 orchestrator 回归测试，确保新增能力不越过单轮单工具边界。

**Tech Stack:** Spring Boot、JUnit 5、Mockito、AssertJ、Gradle、PowerShell、Markdown

**Execution Status (2026-04-24):** 已完成实现与回归验证。首轮 review 修复了全量历史统计、`sessionId/resumeId` 缺参前置拦截与易变 `confirmedFacts` 污染 memory；后续 review 又补齐了“最近一次有效结论”输出，并去掉了显式 `sessionId` 路径对 LAZY `resume` 关联的隐式依赖。

---

## 文件结构与职责

### 新建

- `app/src/main/java/interview/guide/modules/agent/tool/interview/InterviewToolContextService.java`
  统一解析 `resumeId` / `sessionId`、选择目标面试会话、处理 fallback、输出工具侧稳定快照。
- `app/src/main/java/interview/guide/modules/agent/tool/interview/InterviewGapAnalyzer.java`
  基于 `InterviewDetailDTO` 做低分维度、重复改进项、知识缺口标签和练习优先级归纳。
- `app/src/main/java/interview/guide/modules/agent/tool/interview/FollowUpQuestionPlanner.java`
  基于面试详情和短板分析结果生成具体追问建议。
- `app/src/main/java/interview/guide/modules/agent/tool/InterviewHistorySummaryTool.java`
  面向简历维度输出最近面试概况与趋势结论。
- `app/src/main/java/interview/guide/modules/agent/tool/InterviewGapAnalysisTool.java`
  面向指定或自动选择的面试会话输出短板分析。
- `app/src/main/java/interview/guide/modules/agent/tool/FollowUpQuestionSuggestionTool.java`
  面向指定或自动选择的面试会话输出后续追问建议。
- `app/src/test/java/interview/guide/modules/agent/tool/interview/InterviewToolContextServiceTest.java`
  校验目标会话解析、fallback 和归属校验。
- `app/src/test/java/interview/guide/modules/agent/tool/interview/InterviewGapAnalyzerTest.java`
  校验低分维度排序、重复改进项归纳、知识缺口标签与空结果语义。
- `app/src/test/java/interview/guide/modules/agent/tool/interview/FollowUpQuestionPlannerTest.java`
  校验追问建议数量、去空泛化和聚焦分类逻辑。
- `app/src/test/java/interview/guide/modules/agent/tool/InterviewHistorySummaryToolTest.java`
  校验历史概况 Tool 的输入 fallback、空结果稳定返回和 payload 结构。
- `app/src/test/java/interview/guide/modules/agent/tool/InterviewGapAnalysisToolTest.java`
  校验短板分析 Tool 的会话选择、归属校验和输出结构。
- `app/src/test/java/interview/guide/modules/agent/tool/FollowUpQuestionSuggestionToolTest.java`
  校验追问建议 Tool 的会话选择、聚焦分类和输出结构。
- `app/src/test/java/interview/guide/modules/agent/service/AgentMemoryServiceTest.java`
  校验新增 phase 映射和 confirmed facts 行为。

### 修改

- `app/src/main/java/interview/guide/modules/agent/service/AgentMemoryService.java`
  为新增工具补齐 phase 映射。
- `app/src/main/resources/prompts/agent-system.st`
  在决策提示词中补充 3 个新增工具的用途说明。
- `app/src/test/java/interview/guide/modules/agent/service/AgentOrchestratorTest.java`
  增加新增工具执行路径的主链路回归测试。

### 依赖但不修改

- `app/src/main/java/interview/guide/modules/interview/service/InterviewHistoryService.java`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java`
- `app/src/main/java/interview/guide/modules/agent/tool/ToolRegistry.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentPromptService.java`

### 目标目录结构

```text
app/src/main/java/interview/guide/modules/agent/tool/
  InterviewHistorySummaryTool.java
  InterviewGapAnalysisTool.java
  FollowUpQuestionSuggestionTool.java
  interview/
    InterviewToolContextService.java
    InterviewGapAnalyzer.java
    FollowUpQuestionPlanner.java

app/src/test/java/interview/guide/modules/agent/tool/
  InterviewHistorySummaryToolTest.java
  InterviewGapAnalysisToolTest.java
  FollowUpQuestionSuggestionToolTest.java
  interview/
    InterviewToolContextServiceTest.java
    InterviewGapAnalyzerTest.java
    FollowUpQuestionPlannerTest.java

app/src/test/java/interview/guide/modules/agent/service/
  AgentMemoryServiceTest.java
  AgentOrchestratorTest.java
```

### 设计约束

- 新工具全部是 `READ_ONLY`
- Tool 内禁止二次调用模型
- Tool 内禁止写数据库、删除资源或触发评估
- 正常空数据优先返回稳定结果，只有输入缺失、归属冲突、类型非法才抛异常
- `confirmedFacts` 只能写入硬事实，不把建议性结论写成事实
- 仍然保持“一轮最多一个工具”

## Task 1: 建立 Interview 工具上下文解析层

**Files:**
- Create: `app/src/main/java/interview/guide/modules/agent/tool/interview/InterviewToolContextService.java`
- Test: `app/src/test/java/interview/guide/modules/agent/tool/interview/InterviewToolContextServiceTest.java`

- [ ] **Step 1: 写出失败测试，覆盖 resumeId fallback、最近一次已评估会话选择和归属冲突**

```java
@Test
@DisplayName("should fall back to the bound resume id when history summary input omits resumeId")
void shouldFallBackToBoundResumeIdWhenHistorySummaryInputOmitsResumeId() {
    AgentToolContext context = new AgentToolContext("session-1", 42L, List.of(), null, "总结最近面试");
    InterviewSessionEntity latest = session("session-a", 42L, InterviewSessionEntity.SessionStatus.EVALUATED, 78);

    when(interviewPersistenceService.findByResumeId(42L)).thenReturn(List.of(latest));

    InterviewToolContextService.HistorySummarySource source = service.loadHistorySummarySource(Map.of(), context);

    assertThat(source.resumeId()).isEqualTo(42L);
    assertThat(source.sessions()).containsExactly(latest);
    assertThat(source.usedFallback()).isTrue();
}

@Test
@DisplayName("should resolve the latest evaluated session when gap analysis does not provide a session id")
void shouldResolveLatestEvaluatedSessionWhenGapAnalysisOmitsSessionId() {
    AgentToolContext context = new AgentToolContext("session-1", 42L, List.of(), null, "我的短板是什么");
    InterviewSessionEntity inProgress = session("session-a", 42L, InterviewSessionEntity.SessionStatus.IN_PROGRESS, null);
    InterviewSessionEntity evaluated = session("session-b", 42L, InterviewSessionEntity.SessionStatus.EVALUATED, 71);
    InterviewDetailDTO detail = detail("session-b", 71, List.of("回答不够具体"));

    when(interviewPersistenceService.findByResumeId(42L)).thenReturn(List.of(inProgress, evaluated));
    when(interviewHistoryService.getInterviewDetail("session-b")).thenReturn(detail);

    InterviewToolContextService.AnalysisSource source = service.loadGapAnalysisSource(Map.of(), context);

    assertThat(source.sessionId()).isEqualTo("session-b");
    assertThat(source.detail().overallScore()).isEqualTo(71);
    assertThat(source.usedFallback()).isTrue();
}

@Test
@DisplayName("should reject mismatched session and resume ownership")
void shouldRejectMismatchedSessionAndResumeOwnership() {
    AgentToolContext context = new AgentToolContext("session-1", 42L, List.of(), null, "分析这场面试");
    InterviewSessionEntity foreign = session("session-x", 99L, InterviewSessionEntity.SessionStatus.EVALUATED, 80);

    when(interviewPersistenceService.findBySessionId("session-x")).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() -> service.loadGapAnalysisSource(
        Map.of("sessionId", "session-x", "resumeId", 42L),
        context
    )).isInstanceOf(BusinessException.class)
      .hasMessageContaining("sessionId 不属于 resumeId");
}
```

- [ ] **Step 2: 运行上下文层测试，确认当前缺少实现而失败**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.tool.interview.InterviewToolContextServiceTest"`
Expected: FAIL，提示 `InterviewToolContextService` 不存在或相关方法未实现

- [ ] **Step 3: 实现最小上下文解析服务，统一输入解析、会话选择和 fallback 语义**

```java
@Service
@RequiredArgsConstructor
public class InterviewToolContextService {

    private final InterviewPersistenceService interviewPersistenceService;
    private final InterviewHistoryService interviewHistoryService;

    public HistorySummarySource loadHistorySummarySource(Map<String, Object> input, AgentToolContext context) {
        Long resumeId = resolveResumeId(input, context, "get_interview_history_summary");
        int limit = clamp(readInt(input.get("limit")), 5, 1, 10);
        List<InterviewSessionEntity> sessions = interviewPersistenceService.findByResumeId(resumeId).stream()
            .limit(limit)
            .toList();
        return new HistorySummarySource(resumeId, sessions, !input.containsKey("resumeId"), describeHistoryFallback(input, context));
    }

    public AnalysisSource loadGapAnalysisSource(Map<String, Object> input, AgentToolContext context) {
        return loadAnalysisSource(input, context, true, false);
    }

    public AnalysisSource loadFollowUpSource(Map<String, Object> input, AgentToolContext context) {
        return loadAnalysisSource(input, context, true, true);
    }

    private AnalysisSource loadAnalysisSource(
        Map<String, Object> input,
        AgentToolContext context,
        boolean requireEvaluated,
        boolean allowQuestionOnlyFallback
    ) {
        String sessionId = readString(input.get("sessionId"));
        Long explicitResumeId = readLong(input.get("resumeId"));

        if (!sessionId.isBlank()) {
            InterviewSessionEntity session = interviewPersistenceService.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
            validateOwnership(session, explicitResumeId);
            validateAvailability(session, requireEvaluated, allowQuestionOnlyFallback);
            return new AnalysisSource(
                session.getResume().getId(),
                session.getSessionId(),
                session,
                interviewHistoryService.getInterviewDetail(session.getSessionId()),
                false,
                "explicit_session"
            );
        }

        Long resumeId = explicitResumeId != null ? explicitResumeId : context.resumeId();
        if (resumeId == null) {
            throw new BusinessException(ErrorCode.AGENT_INVALID_INPUT, "缺少 resumeId 或 sessionId");
        }

        List<InterviewSessionEntity> sessions = interviewPersistenceService.findByResumeId(resumeId);
        InterviewSessionEntity resolved = pickSession(sessions, requireEvaluated, allowQuestionOnlyFallback);
        if (resolved == null) {
            return new AnalysisSource(resumeId, null, null, null, true, "no_available_session");
        }

        return new AnalysisSource(
            resumeId,
            resolved.getSessionId(),
            resolved,
            interviewHistoryService.getInterviewDetail(resolved.getSessionId()),
            true,
            describeAnalysisFallback(requireEvaluated, allowQuestionOnlyFallback)
        );
    }

    // readLong / readInt / readString / clamp / validateOwnership / validateAvailability / pickSession

    public record HistorySummarySource(
        Long resumeId,
        List<InterviewSessionEntity> sessions,
        boolean usedFallback,
        String fallbackReason
    ) {
    }

    public record AnalysisSource(
        Long resumeId,
        String sessionId,
        InterviewSessionEntity session,
        InterviewDetailDTO detail,
        boolean usedFallback,
        String fallbackReason
    ) {
    }
}
```

- [ ] **Step 4: 重新运行上下文层测试，确认会话选择与归属校验通过**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.tool.interview.InterviewToolContextServiceTest"`
Expected: PASS，输出 `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/interview/guide/modules/agent/tool/interview/InterviewToolContextService.java app/src/test/java/interview/guide/modules/agent/tool/interview/InterviewToolContextServiceTest.java
git commit -m "feat: 增加面试工具上下文解析层"
```

## Task 2: 建立纯规则短板分析与追问规划层

**Files:**
- Create: `app/src/main/java/interview/guide/modules/agent/tool/interview/InterviewGapAnalyzer.java`
- Create: `app/src/main/java/interview/guide/modules/agent/tool/interview/FollowUpQuestionPlanner.java`
- Test: `app/src/test/java/interview/guide/modules/agent/tool/interview/InterviewGapAnalyzerTest.java`
- Test: `app/src/test/java/interview/guide/modules/agent/tool/interview/FollowUpQuestionPlannerTest.java`

- [ ] **Step 1: 写出失败测试，固定低分维度排序、重复改进项归纳和知识缺口标签**

```java
@Test
@DisplayName("should rank low score categories before mapping knowledge gap tags")
void shouldRankLowScoreCategoriesBeforeMappingKnowledgeGapTags() {
    InterviewDetailDTO detail = detail(
        "session-gap",
        68,
        List.of("数据库回答过浅", "项目细节不够具体", "数据库回答过浅"),
        answer(0, "数据库", 55, "表设计说明不完整"),
        answer(1, "项目经验", 62, "缺少量化结果"),
        answer(2, "Java基础", 83, "基础较稳")
    );

    InterviewGapAnalyzer.InterviewGapAnalysis analysis = analyzer.analyze(detail);

    assertThat(analysis.lowCategories()).extracting(InterviewGapAnalyzer.CategoryInsight::category)
        .containsExactly("数据库", "项目经验");
    assertThat(analysis.repeatedImprovements()).containsExactly("数据库回答过浅", "项目细节不够具体");
    assertThat(analysis.knowledgeGapTags()).contains("数据库基础", "项目量化表达");
}

@Test
@DisplayName("should return an explicit unavailable analysis when no evaluated signal exists")
void shouldReturnExplicitUnavailableAnalysisWhenNoEvaluatedSignalExists() {
    InterviewDetailDTO detail = detail("session-gap", null, List.of());

    InterviewGapAnalyzer.InterviewGapAnalysis analysis = analyzer.analyze(detail);

    assertThat(analysis.available()).isFalse();
    assertThat(analysis.summary()).contains("暂不输出短板分析");
}
```

- [ ] **Step 2: 运行规则分析测试，确认当前缺少分析器而失败**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.tool.interview.InterviewGapAnalyzerTest" --tests "interview.guide.modules.agent.tool.interview.FollowUpQuestionPlannerTest"`
Expected: FAIL，提示 `InterviewGapAnalyzer` / `FollowUpQuestionPlanner` 不存在

- [ ] **Step 3: 实现最小短板分析器，先输出稳定 summary、低分维度、重复改进项和标签**

```java
@Component
public class InterviewGapAnalyzer {

    public InterviewGapAnalysis analyze(InterviewDetailDTO detail) {
        if (detail == null || detail.overallScore() == null) {
            return InterviewGapAnalysis.unavailable("评估未完成，暂不输出短板分析");
        }

        List<CategoryInsight> lowCategories = safeAnswers(detail).stream()
            .filter(answer -> answer.score() != null)
            .collect(Collectors.groupingBy(
                InterviewDetailDTO.AnswerDetailDTO::category,
                LinkedHashMap::new,
                Collectors.averagingInt(answer -> answer.score() == null ? 0 : answer.score())
            ))
            .entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(3)
            .map(entry -> new CategoryInsight(entry.getKey(), (int) Math.round(entry.getValue())))
            .toList();

        List<String> repeatedImprovements = safeList(detail.improvements()).stream()
            .filter(text -> text != null && !text.isBlank())
            .map(String::trim)
            .distinct()
            .limit(5)
            .toList();

        List<String> gapTags = mapGapTags(lowCategories, repeatedImprovements);
        List<String> priorities = buildPriorities(lowCategories, repeatedImprovements);

        String summary = detail.overallScore() < 70
            ? "当前面试表现的主要问题集中在低分维度和重复改进项，建议优先补基础表达与案例细节。"
            : "当前面试基础可用，但仍存在可持续强化的薄弱点。";

        return new InterviewGapAnalysis(true, summary, lowCategories, repeatedImprovements, gapTags, priorities);
    }

    public record InterviewGapAnalysis(
        boolean available,
        String summary,
        List<CategoryInsight> lowCategories,
        List<String> repeatedImprovements,
        List<String> knowledgeGapTags,
        List<String> practicePriorities
    ) {
        static InterviewGapAnalysis unavailable(String summary) {
            return new InterviewGapAnalysis(false, summary, List.of(), List.of(), List.of(), List.of());
        }
    }

    public record CategoryInsight(String category, int score) {
    }
}
```

- [ ] **Step 4: 实现最小追问规划器，确保建议具体、带能力点并支持 focusCategory**

```java
@Component
public class FollowUpQuestionPlanner {

    public List<FollowUpSuggestion> plan(
        InterviewDetailDTO detail,
        InterviewGapAnalyzer.InterviewGapAnalysis analysis,
        String focusCategory,
        int maxCount
    ) {
        List<String> prioritizedCategories = new ArrayList<>();
        if (focusCategory != null && !focusCategory.isBlank()) {
            prioritizedCategories.add(focusCategory.trim());
        }
        analysis.lowCategories().stream()
            .map(InterviewGapAnalyzer.CategoryInsight::category)
            .filter(category -> !prioritizedCategories.contains(category))
            .forEach(prioritizedCategories::add);

        if (prioritizedCategories.isEmpty()) {
            prioritizedCategories.addAll(
                safeAnswers(detail).stream()
                    .map(InterviewDetailDTO.AnswerDetailDTO::category)
                    .filter(category -> category != null && !category.isBlank())
                    .distinct()
                    .limit(maxCount)
                    .toList()
            );
        }

        return prioritizedCategories.stream()
            .limit(maxCount)
            .map(category -> new FollowUpSuggestion(
                "请你结合一个具体案例，系统说明你在“" + category + "”上的关键判断和取舍。",
                category,
                "该维度在最近一次面试中暴露出补强空间",
                "回答时补充背景、动作、结果和复盘"
            ))
            .toList();
    }

    public record FollowUpSuggestion(
        String question,
        String focusArea,
        String reason,
        String coachingTip
    ) {
    }
}
```

- [ ] **Step 5: 重新运行规则分析测试，确认短板分析与追问规划通过**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.tool.interview.InterviewGapAnalyzerTest" --tests "interview.guide.modules.agent.tool.interview.FollowUpQuestionPlannerTest"`
Expected: PASS，输出 `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/interview/guide/modules/agent/tool/interview/InterviewGapAnalyzer.java app/src/main/java/interview/guide/modules/agent/tool/interview/FollowUpQuestionPlanner.java app/src/test/java/interview/guide/modules/agent/tool/interview/InterviewGapAnalyzerTest.java app/src/test/java/interview/guide/modules/agent/tool/interview/FollowUpQuestionPlannerTest.java
git commit -m "feat: 增加面试短板分析与追问规划"
```

## Task 3: 实现 3 个新增 Interview Tool

**Files:**
- Create: `app/src/main/java/interview/guide/modules/agent/tool/InterviewHistorySummaryTool.java`
- Create: `app/src/main/java/interview/guide/modules/agent/tool/InterviewGapAnalysisTool.java`
- Create: `app/src/main/java/interview/guide/modules/agent/tool/FollowUpQuestionSuggestionTool.java`
- Test: `app/src/test/java/interview/guide/modules/agent/tool/InterviewHistorySummaryToolTest.java`
- Test: `app/src/test/java/interview/guide/modules/agent/tool/InterviewGapAnalysisToolTest.java`
- Test: `app/src/test/java/interview/guide/modules/agent/tool/FollowUpQuestionSuggestionToolTest.java`

- [ ] **Step 1: 写出失败测试，固定 3 个 Tool 的输入 fallback 和输出载荷结构**

```java
@Test
@DisplayName("should return a stable empty history summary when the resume has no interviews")
void shouldReturnStableEmptyHistorySummaryWhenResumeHasNoInterviews() {
    AgentToolContext context = new AgentToolContext("session-1", 42L, List.of(), null, "最近面试怎么样");
    when(interviewToolContextService.loadHistorySummarySource(Map.of(), context))
        .thenReturn(new InterviewToolContextService.HistorySummarySource(42L, List.of(), true, "context_resume"));

    AgentToolResult result = tool.execute(Map.of(), context);

    assertThat(result.summary()).contains("暂无面试记录");
    assertThat(result.answerPayload()).containsEntry("totalInterviews", 0);
    assertThat(result.debugPayload()).containsEntry("fallbackReason", "context_resume");
    assertThat(result.confirmedFacts()).containsExactly("当前简历暂无面试记录");
}

@Test
@DisplayName("should expose unavailable gap analysis instead of throwing when the selected session is not evaluated")
void shouldExposeUnavailableGapAnalysisInsteadOfThrowingWhenTheSelectedSessionIsNotEvaluated() {
    AgentToolContext context = new AgentToolContext("session-1", 42L, List.of(), null, "我的短板是什么");
    InterviewToolContextService.AnalysisSource source = new InterviewToolContextService.AnalysisSource(
        42L, "session-a", session("session-a", 42L, InterviewSessionEntity.SessionStatus.IN_PROGRESS, null), null, true, "latest_question_only"
    );
    when(interviewToolContextService.loadGapAnalysisSource(Map.of(), context)).thenReturn(source);

    AgentToolResult result = tool.execute(Map.of(), context);

    assertThat(result.summary()).contains("暂不输出短板分析");
    assertThat(result.answerPayload()).containsEntry("available", false);
    assertThat(result.debugPayload()).containsEntry("selectedSessionId", "session-a");
}
```

- [ ] **Step 2: 运行 Tool 测试，确认当前缺少 Tool 实现而失败**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.tool.InterviewHistorySummaryToolTest" --tests "interview.guide.modules.agent.tool.InterviewGapAnalysisToolTest" --tests "interview.guide.modules.agent.tool.FollowUpQuestionSuggestionToolTest"`
Expected: FAIL，提示 3 个 Tool 类不存在

- [ ] **Step 3: 实现 `InterviewHistorySummaryTool`，先完成稳定空结果、趋势摘要和硬事实输出**

```java
@Component
@RequiredArgsConstructor
public class InterviewHistorySummaryTool implements AgentTool {

    private final InterviewToolContextService interviewToolContextService;

    @Override
    public String name() {
        return "get_interview_history_summary";
    }

    @Override
    public String description() {
        return "汇总简历最近几次面试的状态、分数、趋势和未完成情况。输入: { resumeId, limit }";
    }

    @Override
    public List<String> allowedInputs() {
        return List.of("resumeId", "limit");
    }

    @Override
    public AgentToolRiskLevel riskLevel() {
        return AgentToolRiskLevel.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        InterviewToolContextService.HistorySummarySource source = interviewToolContextService.loadHistorySummarySource(input, context);
        if (source.sessions().isEmpty()) {
            return new AgentToolResult(
                "当前简历暂无面试记录，暂时不能判断趋势。",
                Map.of("resumeId", source.resumeId(), "totalInterviews", 0, "evaluatedInterviews", 0, "unfinishedInterviews", 0, "recentSessions", List.of()),
                Map.of("fallbackReason", source.fallbackReason(), "usedFallback", source.usedFallback()),
                List.of("当前简历暂无面试记录")
            );
        }

        List<Map<String, Object>> recentSessions = source.sessions().stream()
            .map(session -> Map.of(
                "sessionId", session.getSessionId(),
                "status", session.getStatus().name(),
                "overallScore", session.getOverallScore(),
                "createdAt", session.getCreatedAt()
            ))
            .toList();

        long unfinished = source.sessions().stream()
            .filter(session -> session.getStatus() == InterviewSessionEntity.SessionStatus.CREATED
                || session.getStatus() == InterviewSessionEntity.SessionStatus.IN_PROGRESS)
            .count();

        return new AgentToolResult(
            "已汇总最近面试概况，可用于判断最近趋势和未完成情况。",
            Map.of(
                "resumeId", source.resumeId(),
                "totalInterviews", source.sessions().size(),
                "evaluatedInterviews", source.sessions().stream().filter(session -> session.getOverallScore() != null).count(),
                "unfinishedInterviews", unfinished,
                "recentSessions", recentSessions
            ),
            Map.of("fallbackReason", source.fallbackReason(), "usedFallback", source.usedFallback()),
            List.of("最近统计的面试数量: " + source.sessions().size(), "未完成面试数量: " + unfinished)
        );
    }
}
```

- [ ] **Step 4: 实现 `InterviewGapAnalysisTool` 和 `FollowUpQuestionSuggestionTool`，复用上下文层与分析层**

```java
@Component
@RequiredArgsConstructor
public class InterviewGapAnalysisTool implements AgentTool {

    private final InterviewToolContextService interviewToolContextService;
    private final InterviewGapAnalyzer interviewGapAnalyzer;

    @Override
    public String name() {
        return "analyze_interview_gaps";
    }

    @Override
    public String description() {
        return "分析指定或最近一次已评估面试的低分维度、重复改进项和练习优先级。输入: { sessionId, resumeId }";
    }

    @Override
    public List<String> allowedInputs() {
        return List.of("sessionId", "resumeId");
    }

    @Override
    public AgentToolRiskLevel riskLevel() {
        return AgentToolRiskLevel.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        InterviewToolContextService.AnalysisSource source = interviewToolContextService.loadGapAnalysisSource(input, context);
        if (source.detail() == null) {
            return new AgentToolResult(
                "当前还没有可分析的面试报告，暂不输出短板结论。",
                Map.of("available", false, "resumeId", source.resumeId(), "selectedSessionId", source.sessionId()),
                Map.of("selectedSessionId", source.sessionId(), "fallbackReason", source.fallbackReason(), "usedFallback", source.usedFallback()),
                List.of("当前暂无可分析的已评估面试")
            );
        }

        InterviewGapAnalyzer.InterviewGapAnalysis analysis = interviewGapAnalyzer.analyze(source.detail());
        return new AgentToolResult(
            analysis.summary(),
            Map.of(
                "available", analysis.available(),
                "resumeId", source.resumeId(),
                "selectedSessionId", source.sessionId(),
                "lowCategories", analysis.lowCategories(),
                "repeatedImprovements", analysis.repeatedImprovements(),
                "knowledgeGapTags", analysis.knowledgeGapTags(),
                "practicePriorities", analysis.practicePriorities()
            ),
            Map.of("selectedSessionId", source.sessionId(), "fallbackReason", source.fallbackReason(), "usedFallback", source.usedFallback()),
            buildConfirmedFacts(source, analysis)
        );
    }
}

@Component
@RequiredArgsConstructor
public class FollowUpQuestionSuggestionTool implements AgentTool {

    private final InterviewToolContextService interviewToolContextService;
    private final InterviewGapAnalyzer interviewGapAnalyzer;
    private final FollowUpQuestionPlanner followUpQuestionPlanner;

    @Override
    public String name() {
        return "suggest_follow_up_questions";
    }

    @Override
    public String description() {
        return "根据最近面试表现生成 1 到 5 个具体追问建议。输入: { sessionId, resumeId, focusCategory, maxCount }";
    }

    @Override
    public List<String> allowedInputs() {
        return List.of("sessionId", "resumeId", "focusCategory", "maxCount");
    }

    @Override
    public AgentToolRiskLevel riskLevel() {
        return AgentToolRiskLevel.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        InterviewToolContextService.AnalysisSource source = interviewToolContextService.loadFollowUpSource(input, context);
        int maxCount = clamp(readInt(input.get("maxCount")), 3, 1, 5);
        String focusCategory = readString(input.get("focusCategory"));

        if (source.detail() == null) {
            return new AgentToolResult(
                "当前上下文不足，暂时无法生成追问建议。",
                Map.of("available", false, "resumeId", source.resumeId(), "selectedSessionId", source.sessionId(), "suggestions", List.of()),
                Map.of("selectedSessionId", source.sessionId(), "fallbackReason", source.fallbackReason(), "usedFallback", source.usedFallback()),
                List.of("当前暂无足够上下文生成追问建议")
            );
        }

        InterviewGapAnalyzer.InterviewGapAnalysis analysis = interviewGapAnalyzer.analyze(source.detail());
        List<FollowUpQuestionPlanner.FollowUpSuggestion> suggestions = followUpQuestionPlanner.plan(
            source.detail(),
            analysis,
            focusCategory,
            maxCount
        );

        return new AgentToolResult(
            "已生成可继续练习的追问建议。",
            Map.of("available", true, "resumeId", source.resumeId(), "selectedSessionId", source.sessionId(), "suggestions", suggestions),
            Map.of("selectedSessionId", source.sessionId(), "fallbackReason", source.fallbackReason(), "usedFallback", source.usedFallback(), "focusCategory", focusCategory),
            List.of("追问建议数量: " + suggestions.size(), "目标面试会话: " + source.sessionId())
        );
    }
}
```

- [ ] **Step 5: 重新运行 Tool 测试，确认 3 个 Tool 的主要路径通过**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.tool.InterviewHistorySummaryToolTest" --tests "interview.guide.modules.agent.tool.InterviewGapAnalysisToolTest" --tests "interview.guide.modules.agent.tool.FollowUpQuestionSuggestionToolTest"`
Expected: PASS，输出 `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/interview/guide/modules/agent/tool/InterviewHistorySummaryTool.java app/src/main/java/interview/guide/modules/agent/tool/InterviewGapAnalysisTool.java app/src/main/java/interview/guide/modules/agent/tool/FollowUpQuestionSuggestionTool.java app/src/test/java/interview/guide/modules/agent/tool/InterviewHistorySummaryToolTest.java app/src/test/java/interview/guide/modules/agent/tool/InterviewGapAnalysisToolTest.java app/src/test/java/interview/guide/modules/agent/tool/FollowUpQuestionSuggestionToolTest.java
git commit -m "feat: 增加面试领域核心工具"
```

## Task 4: 补齐 memory phase、决策提示词和主链路回归

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/agent/service/AgentMemoryService.java`
- Modify: `app/src/main/resources/prompts/agent-system.st`
- Modify: `app/src/test/java/interview/guide/modules/agent/service/AgentOrchestratorTest.java`
- Create: `app/src/test/java/interview/guide/modules/agent/service/AgentMemoryServiceTest.java`

- [ ] **Step 1: 写出失败测试，固定新增 phase 映射和 confirmed facts 去重行为**

```java
@Test
@DisplayName("should map interview tools to dedicated memory phases")
void shouldMapInterviewToolsToDedicatedMemoryPhases() {
    AgentMemorySnapshot current = new AgentMemorySnapshot("准备面试", "goal_received", List.of("fact-1"), List.of("get_resume_profile"), "next");
    AgentToolResult result = new AgentToolResult("summary", Map.of(), Map.of(), List.of("fact-2"));

    assertThat(memoryService.updateAfterTool(current, "get_interview_history_summary", result).currentPhase())
        .isEqualTo("interview_history_ready");
    assertThat(memoryService.updateAfterTool(current, "analyze_interview_gaps", result).currentPhase())
        .isEqualTo("interview_gap_ready");
    assertThat(memoryService.updateAfterTool(current, "suggest_follow_up_questions", result).currentPhase())
        .isEqualTo("follow_up_ready");
}
```

- [ ] **Step 2: 运行 memory 测试，确认当前 phase 映射未覆盖新增工具**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.service.AgentMemoryServiceTest"`
Expected: FAIL，断言当前 phase 仍为 `context_ready`

- [ ] **Step 3: 修改 `AgentMemoryService` 和 `agent-system.st`，补齐新工具 phase 与决策提示**

```java
private String resolvePhase(String toolName) {
    return switch (toolName) {
        case "get_resume_profile" -> "resume_context_ready";
        case "search_knowledge_base" -> "knowledge_context_ready";
        case "get_interview_history_summary" -> "interview_history_ready";
        case "analyze_interview_gaps" -> "interview_gap_ready";
        case "suggest_follow_up_questions" -> "follow_up_ready";
        default -> "context_ready";
    };
}
```

```text
- `get_interview_history_summary`: 当用户想了解最近面试概况、趋势、未完成情况时使用
- `analyze_interview_gaps`: 当用户想知道短板、薄弱点、最该补的方向时使用
- `suggest_follow_up_questions`: 当用户想继续练习、生成针对性追问时使用
```

- [ ] **Step 4: 写出并运行 orchestrator 回归测试，覆盖新增工具执行后的 trace / memory 语义**

```java
@Test
@DisplayName("should execute analyze_interview_gaps and persist the dedicated memory phase")
void shouldExecuteAnalyzeInterviewGapsAndPersistDedicatedMemoryPhase() {
    AgentChatRequest request = new AgentChatRequest("我最近面试的短板是什么");
    AgentSessionEntity session = createSession("session-gap", "准备 Java 面试", 42L);
    AgentMemorySnapshot memory = createMemory();
    AgentMemorySnapshot updatedMemory = new AgentMemorySnapshot(
        "准备 Java 面试",
        "interview_gap_ready",
        List.of("最近一次已评估分数: 68", "低分维度: 数据库"),
        List.of("analyze_interview_gaps"),
        "已提炼主要短板和练习优先级"
    );
    AgentStepTraceEntity stepTrace = new AgentStepTraceEntity();
    AgentToolResult toolResult = new AgentToolResult(
        "已提炼主要短板和练习优先级",
        Map.of("available", true, "selectedSessionId", "interview-session-1"),
        Map.of("fallbackReason", "latest_evaluated_session"),
        List.of("最近一次已评估分数: 68", "低分维度: 数据库")
    );

    when(toolRegistry.describeTools()).thenReturn("- analyze_interview_gaps");
    when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new AgentDecisionDTO(true, "analyze_interview_gaps", Map.of(), "need interview gap analysis", null));
    when(toolRegistry.findTool("analyze_interview_gaps")).thenReturn(Optional.of(tool));
    when(tool.name()).thenReturn("analyze_interview_gaps");
    when(traceService.startToolStep(anyString(), anyString(), anyString(), anyMap(), eq(memory))).thenReturn(stepTrace);
    when(tool.execute(anyMap(), any())).thenReturn(toolResult);
    when(memoryService.updateAfterTool(memory, "analyze_interview_gaps", toolResult)).thenReturn(updatedMemory);

    orchestrator.chat(session.getSessionId(), request);

    verify(traceService).completeToolStep(eq(stepTrace), eq(toolResult), eq(updatedMemory), anyString(), any());
    verify(sessionService).completeTurn(anyString(), anyString(), eq(updatedMemory), eq(AgentCompletionMode.SUCCESS));
}
```

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.service.AgentMemoryServiceTest" --tests "interview.guide.modules.agent.service.AgentOrchestratorTest"`
Expected: PASS，输出 `BUILD SUCCESSFUL`

- [ ] **Step 5: 直接检查决策提示词里是否出现 3 个新工具名称**

Run: `rg -n "get_interview_history_summary|analyze_interview_gaps|suggest_follow_up_questions" app/src/main/resources/prompts/agent-system.st`
Expected: 3 行命中，工具用途说明完整

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/interview/guide/modules/agent/service/AgentMemoryService.java app/src/main/resources/prompts/agent-system.st app/src/test/java/interview/guide/modules/agent/service/AgentMemoryServiceTest.java app/src/test/java/interview/guide/modules/agent/service/AgentOrchestratorTest.java
git commit -m "feat: 接入面试工具的主链路兼容"
```

## Task 5: 完整验证与回归检查

**Files:**
- Test: `app/src/test/java/interview/guide/modules/agent/tool/interview/*.java`
- Test: `app/src/test/java/interview/guide/modules/agent/tool/*.java`
- Test: `app/src/test/java/interview/guide/modules/agent/service/*.java`
- Test: `app/src/main/resources/prompts/agent-system.st`

- [ ] **Step 1: 运行新增工具与分析器的聚合测试**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.tool.interview.*" --tests "interview.guide.modules.agent.tool.Interview*ToolTest"`
Expected: PASS，输出 `BUILD SUCCESSFUL`

- [ ] **Step 2: 运行 agent 服务层关键回归测试**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.service.AgentMemoryServiceTest" --tests "interview.guide.modules.agent.service.AgentOrchestratorTest" --tests "interview.guide.modules.agent.service.AgentPromptServiceTest"`
Expected: PASS，输出 `BUILD SUCCESSFUL`

- [ ] **Step 3: 运行最小 Stage 2 回归，确认新增工具未破坏既有 Agent 评测入口**

Run: `.\gradlew.bat :app:test --tests "interview.guide.modules.agent.eval.AgentStage2RegressionEvalTest"`
Expected: PASS，输出 `BUILD SUCCESSFUL`

- [ ] **Step 4: 检查 git diff，确认改动只落在 S3-01 设计范围内**

Run: `git diff --stat`
Expected: 只包含新的 interview tool / analyzer / test / prompt / memory 相关改动，不包含 Stage 4/5 或多步执行代码

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "test: 完成面试上下文工具回归验证"
```

## 自检

### 1. Spec 覆盖检查

- `get_interview_history_summary`、`analyze_interview_gaps`、`suggest_follow_up_questions`：由 Task 3 覆盖
- 共享上下文解析层：由 Task 1 覆盖
- 纯规则短板分析与追问规划：由 Task 2 覆盖
- trace / memory / prompt 兼容：由 Task 4 覆盖
- TDD、多层验证和至少一个主链路回归场景：由 Task 1-5 联合覆盖

未发现 spec 要求未映射到任务。

### 2. 占位符扫描

本计划未使用常见占位词或模糊交接表述，所有任务都给出了明确文件、代码片段、命令与预期结果。

### 3. 类型与命名一致性检查

统一使用以下名称：

- Tool 名：
  - `get_interview_history_summary`
  - `analyze_interview_gaps`
  - `suggest_follow_up_questions`
- Memory phase：
  - `interview_history_ready`
  - `interview_gap_ready`
  - `follow_up_ready`
- 共享服务与分析器：
  - `InterviewToolContextService`
  - `InterviewGapAnalyzer`
  - `FollowUpQuestionPlanner`

未发现前后命名冲突。
