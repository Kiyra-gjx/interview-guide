package interview.guide.modules.agent.eval;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import interview.guide.modules.agent.model.AgentChatRequest;
import interview.guide.modules.agent.model.AgentChatResponse;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentDecisionDTO;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentMessageDTO;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.service.AgentApprovalRuntimeService;
import interview.guide.modules.agent.service.AgentApprovalService;
import interview.guide.modules.agent.service.AgentContextAssemblyService;
import interview.guide.modules.agent.service.AgentMemoryService;
import interview.guide.modules.agent.service.AgentMetricsService;
import interview.guide.modules.agent.service.AgentOrchestrator;
import interview.guide.modules.agent.service.AgentPromptService;
import interview.guide.modules.agent.service.AgentSessionService;
import interview.guide.modules.agent.service.AgentTraceService;
import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.support.AgentContextBudget;
import interview.guide.modules.agent.support.AgentContextSection;
import interview.guide.modules.agent.support.AgentContextSectionStatus;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.AgentTool;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import interview.guide.modules.agent.tool.ToolRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStage7ToolRoutingEvalTest {

    private static final String SUITE_ID = "stage-7-tool-routing-set";
    private static final String JSON_REPORT_NAME = "stage-7-tool-routing-set-report.json";
    private static final String MARKDOWN_REPORT_NAME = "stage-7-tool-routing-set-report.md";
    private static final String NO_APPROVAL = "NONE";
    private static final String DIRECT_TOOL = "direct_answer";
    private static final String NO_TOOL = "none";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("should run the fixed stage 7 tool routing suite and persist reports")
    void shouldRunTheFixedStage7ToolRoutingSuiteAndPersistReports() throws Exception {
        Path reportDirectory = Path.of("build", "reports", "agent-eval");

        AgentStage7ToolRoutingReport report = runFixedSuite(reportDirectory);

        assertThat(report.summary().totalCases()).isEqualTo(20);
        assertThat(report.summary().passedCases()).isEqualTo(20);
        assertThat(report.summary().toolSelectionAccuracy()).isEqualTo(100.0);
        assertThat(report.summary().paramAccuracy()).isEqualTo(100.0);
        assertThat(report.summary().rejectionAccuracy()).isEqualTo(100.0);
        assertThat(report.summary().directReplyAccuracy()).isEqualTo(100.0);
        assertThat(report.summary().approvalRoutingAccuracy()).isEqualTo(100.0);
        assertThat(report.summary().unexpectedToolExecutionCount()).isZero();
        assertThat(report.caseResults()).allMatch(AgentStage7ToolRoutingCaseResult::passed);

        Path jsonReport = reportDirectory.resolve(JSON_REPORT_NAME);
        Path markdownReport = reportDirectory.resolve(MARKDOWN_REPORT_NAME);

        assertThat(Files.exists(jsonReport)).isTrue();
        assertThat(Files.exists(markdownReport)).isTrue();
        assertThat(Files.readString(markdownReport))
            .contains("toolSelectionAccuracy")
            .contains("paramAccuracy")
            .contains("rejectionAccuracy")
            .contains("directReplyAccuracy")
            .contains("approvalRoutingAccuracy")
            .contains("unexpectedToolExecutionCount");
    }

    private AgentStage7ToolRoutingReport runFixedSuite(Path reportDirectory) throws Exception {
        List<ToolRoutingScenario> scenarios = buildScenarios();
        List<AgentStage7ToolRoutingCaseResult> caseResults = new ArrayList<>();
        for (ToolRoutingScenario scenario : scenarios) {
            caseResults.add(executeScenario(scenario));
        }

        AgentStage7ToolRoutingReport report = new AgentStage7ToolRoutingReport(
            SUITE_ID,
            LocalDateTime.now().toString(),
            buildSummary(caseResults),
            caseResults
        );
        writeReport(reportDirectory, report);
        return report;
    }

    private AgentStage7ToolRoutingCaseResult executeScenario(ToolRoutingScenario scenario) {
        long startedAt = System.nanoTime();
        EvalHarness harness;
        try {
            harness = new EvalHarness(scenario);
        } catch (Exception error) {
            return AgentStage7ToolRoutingCaseResult.error(
                scenario,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                error
            );
        }

        AgentChatResponse response;
        try {
            response = harness.orchestrator().chat(scenario.sessionId(), new AgentChatRequest(scenario.userMessage()));
        } catch (Exception error) {
            return AgentStage7ToolRoutingCaseResult.error(
                scenario,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                error
            );
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        Observation observation = harness.observe(response);
        boolean outcomeMatched = isOutcomeMatched(scenario.expectedOutcome(), observation, scenario.expectedReplyToken());
        boolean toolSelectionMatched = isToolSelectionMatched(scenario.expectedTool(), observation.selectedTool());
        boolean paramMatched = isParamMatched(scenario.expectedParams(), observation.actualParams());
        boolean rejectionMatched = isRejectionMatched(scenario, observation);
        boolean directReplyMatched = scenario.expectedOutcome() != EvalExpectedOutcome.DIRECT_REPLY
            || (observation.directReplyObserved()
                && observation.reply() != null
                && observation.reply().contains(blankToEmpty(scenario.expectedReplyToken())));
        boolean approvalRoutingMatched = scenario.expectedOutcome() != EvalExpectedOutcome.WAITING_APPROVAL
            || (observation.completionMode() == AgentCompletionMode.WAITING_APPROVAL
                && AgentApprovalStatus.PENDING.name().equals(observation.approvalStatus())
                && observation.executedTools().isEmpty());
        boolean unexpectedToolExecution = scenario.expectedOutcome() != EvalExpectedOutcome.TOOL_EXECUTED
            && !observation.executedTools().isEmpty();
        boolean passed = outcomeMatched
            && toolSelectionMatched
            && paramMatched
            && rejectionMatched
            && directReplyMatched
            && approvalRoutingMatched
            && !unexpectedToolExecution;

        return new AgentStage7ToolRoutingCaseResult(
            scenario.caseId(),
            scenario.caseType(),
            scenario.expectedOutcome().name(),
            actualOutcome(observation).name(),
            scenario.expectedTool(),
            observation.selectedTool(),
            sanitizeMap(scenario.expectedParams()),
            sanitizeMap(observation.actualParams()),
            toolSelectionMatched,
            paramMatched,
            rejectionMatched,
            directReplyMatched,
            approvalRoutingMatched,
            unexpectedToolExecution,
            observation.completionMode() == null ? "NONE" : observation.completionMode().name(),
            observation.approvalStatus(),
            List.copyOf(observation.executedTools()),
            sanitizeNote(observation.rejectedReason()),
            sanitizeNote(observation.reply()),
            latencyMs,
            passed,
            passed ? "tool routing contract matched" : mismatchNote(
                outcomeMatched,
                toolSelectionMatched,
                paramMatched,
                rejectionMatched,
                directReplyMatched,
                approvalRoutingMatched,
                unexpectedToolExecution
            )
        );
    }

    private boolean isOutcomeMatched(
        EvalExpectedOutcome expectedOutcome,
        Observation observation,
        String expectedReplyToken
    ) {
        if (expectedOutcome == EvalExpectedOutcome.TOOL_EXECUTED) {
            return observation.completionMode() == AgentCompletionMode.SUCCESS
                && !observation.executedTools().isEmpty()
                && NO_APPROVAL.equals(observation.approvalStatus());
        }
        if (expectedOutcome == EvalExpectedOutcome.DEGRADED_REJECTION) {
            return observation.completionMode() == AgentCompletionMode.DEGRADED
                && observation.executedTools().isEmpty()
                && NO_APPROVAL.equals(observation.approvalStatus());
        }
        if (expectedOutcome == EvalExpectedOutcome.WAITING_APPROVAL) {
            return observation.completionMode() == AgentCompletionMode.WAITING_APPROVAL
                && observation.executedTools().isEmpty()
                && AgentApprovalStatus.PENDING.name().equals(observation.approvalStatus());
        }
        return observation.completionMode() == AgentCompletionMode.SUCCESS
            && observation.executedTools().isEmpty()
            && NO_APPROVAL.equals(observation.approvalStatus())
            && observation.directReplyObserved()
            && observation.reply() != null
            && observation.reply().contains(blankToEmpty(expectedReplyToken));
    }

    private boolean isToolSelectionMatched(String expectedTool, String actualTool) {
        if (expectedTool == null || expectedTool.isBlank()) {
            return true;
        }
        return Objects.equals(expectedTool, actualTool);
    }

    private boolean isParamMatched(Map<String, Object> expectedParams, Map<String, Object> actualParams) {
        if (expectedParams == null) {
            return true;
        }
        return Objects.equals(canonicalize(expectedParams), canonicalize(actualParams));
    }

    private boolean isRejectionMatched(ToolRoutingScenario scenario, Observation observation) {
        if (scenario.expectedOutcome() != EvalExpectedOutcome.DEGRADED_REJECTION) {
            return true;
        }
        if (observation.completionMode() != AgentCompletionMode.DEGRADED) {
            return false;
        }
        if (observation.rejectedReason() == null || observation.rejectedReason().isBlank()) {
            return false;
        }
        if (scenario.expectedRejectionToken() == null || scenario.expectedRejectionToken().isBlank()) {
            return true;
        }
        return observation.rejectedReason().contains(scenario.expectedRejectionToken());
    }

    private AgentStage7ToolRoutingSummary buildSummary(List<AgentStage7ToolRoutingCaseResult> caseResults) {
        int totalCases = caseResults.size();
        int passedCases = (int) caseResults.stream().filter(AgentStage7ToolRoutingCaseResult::passed).count();
        int selectionCases = (int) caseResults.stream().filter(result -> result.expectedTool() != null).count();
        int selectionMatched = (int) caseResults.stream()
            .filter(result -> result.expectedTool() != null)
            .filter(AgentStage7ToolRoutingCaseResult::toolSelectionMatched)
            .count();
        int paramCases = (int) caseResults.stream().filter(result -> result.expectedParams() != null).count();
        int paramMatched = (int) caseResults.stream()
            .filter(result -> result.expectedParams() != null)
            .filter(AgentStage7ToolRoutingCaseResult::paramMatched)
            .count();
        int rejectionCases = (int) caseResults.stream()
            .filter(result -> EvalExpectedOutcome.DEGRADED_REJECTION.name().equals(result.expectedOutcome()))
            .count();
        int rejectionMatched = (int) caseResults.stream()
            .filter(result -> EvalExpectedOutcome.DEGRADED_REJECTION.name().equals(result.expectedOutcome()))
            .filter(AgentStage7ToolRoutingCaseResult::rejectionMatched)
            .count();
        int directReplyCases = (int) caseResults.stream()
            .filter(result -> EvalExpectedOutcome.DIRECT_REPLY.name().equals(result.expectedOutcome()))
            .count();
        int directReplyMatched = (int) caseResults.stream()
            .filter(result -> EvalExpectedOutcome.DIRECT_REPLY.name().equals(result.expectedOutcome()))
            .filter(AgentStage7ToolRoutingCaseResult::directReplyMatched)
            .count();
        int approvalCases = (int) caseResults.stream()
            .filter(result -> EvalExpectedOutcome.WAITING_APPROVAL.name().equals(result.expectedOutcome()))
            .count();
        int approvalMatched = (int) caseResults.stream()
            .filter(result -> EvalExpectedOutcome.WAITING_APPROVAL.name().equals(result.expectedOutcome()))
            .filter(AgentStage7ToolRoutingCaseResult::approvalRoutingMatched)
            .count();
        int unexpectedToolExecutionCount = (int) caseResults.stream()
            .filter(AgentStage7ToolRoutingCaseResult::unexpectedToolExecution)
            .count();
        long averageLatencyMs = Math.round(caseResults.stream().mapToLong(AgentStage7ToolRoutingCaseResult::latencyMs).average().orElse(0));
        long maxLatencyMs = caseResults.stream().mapToLong(AgentStage7ToolRoutingCaseResult::latencyMs).max().orElse(0);

        return new AgentStage7ToolRoutingSummary(
            totalCases,
            passedCases,
            toPercent(selectionMatched, selectionCases),
            toPercent(paramMatched, paramCases),
            toPercent(rejectionMatched, rejectionCases),
            toPercent(directReplyMatched, directReplyCases),
            toPercent(approvalMatched, approvalCases),
            unexpectedToolExecutionCount,
            averageLatencyMs,
            maxLatencyMs
        );
    }

    private void writeReport(Path reportDirectory, AgentStage7ToolRoutingReport report) throws Exception {
        Files.createDirectories(reportDirectory);
        Files.writeString(
            reportDirectory.resolve(JSON_REPORT_NAME),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        );
        Files.writeString(reportDirectory.resolve(MARKDOWN_REPORT_NAME), toMarkdown(report));
    }

    private String toMarkdown(AgentStage7ToolRoutingReport report) {
        AgentStage7ToolRoutingSummary summary = report.summary();
        StringBuilder builder = new StringBuilder();
        builder.append("# Stage 7 Tool Routing Set Report\n\n");
        builder.append("- suite: ").append(report.suiteId()).append('\n');
        builder.append("- evaluationMode: fixed structured decision fixtures (local routing contract, not live model intent classification)\n");
        builder.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        builder.append("- totalCases: ").append(summary.totalCases()).append('\n');
        builder.append("- passedCases: ").append(summary.passedCases()).append('\n');
        builder.append("- toolSelectionAccuracy: ").append(summary.toolSelectionAccuracy()).append("%\n");
        builder.append("- paramAccuracy: ").append(summary.paramAccuracy()).append("%\n");
        builder.append("- rejectionAccuracy: ").append(summary.rejectionAccuracy()).append("%\n");
        builder.append("- directReplyAccuracy: ").append(summary.directReplyAccuracy()).append("%\n");
        builder.append("- approvalRoutingAccuracy: ").append(summary.approvalRoutingAccuracy()).append("%\n");
        builder.append("- unexpectedToolExecutionCount: ").append(summary.unexpectedToolExecutionCount()).append('\n');
        builder.append("- averageLatencyMs: ").append(summary.averageLatencyMs()).append('\n');
        builder.append("- maxLatencyMs: ").append(summary.maxLatencyMs()).append("\n\n");
        builder.append("| Case | Type | ExpectedOutcome | ActualOutcome | ExpectedTool | ActualTool | ToolSelectionMatched | ParamMatched | RejectionMatched | DirectReplyMatched | ApprovalRoutingMatched | UnexpectedToolExecution | Passed | Note |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (AgentStage7ToolRoutingCaseResult result : report.caseResults()) {
            builder.append("| ")
                .append(result.caseId())
                .append(" | ")
                .append(result.caseType())
                .append(" | ")
                .append(result.expectedOutcome())
                .append(" | ")
                .append(result.actualOutcome())
                .append(" | ")
                .append(nullToCell(result.expectedTool()))
                .append(" | ")
                .append(nullToCell(result.actualTool()))
                .append(" | ")
                .append(result.toolSelectionMatched())
                .append(" | ")
                .append(result.paramMatched())
                .append(" | ")
                .append(result.rejectionMatched())
                .append(" | ")
                .append(result.directReplyMatched())
                .append(" | ")
                .append(result.approvalRoutingMatched())
                .append(" | ")
                .append(result.unexpectedToolExecution())
                .append(" | ")
                .append(result.passed())
                .append(" | ")
                .append(result.note())
                .append(" |\n");
        }
        return builder.toString();
    }

    private List<ToolRoutingScenario> buildScenarios() {
        return List.of(
            toolCase(
                "ROUTE-001",
                "resume_profile_read",
                "看一下我的简历亮点",
                42L,
                List.of(7001L, 7002L),
                new AgentDecisionDTO(true, "get_resume_profile", Map.of(), "读取简历画像", null),
                "get_resume_profile",
                orderedMap("resumeId", 42L),
                Map.of()
            ),
            toolCase(
                "ROUTE-002",
                "resume_profile_read_with_input",
                "读取简历 99 的画像",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(true, "get_resume_profile", orderedMap("resumeId", 99L), "按指定 resumeId 读取画像", null),
                "get_resume_profile",
                orderedMap("resumeId", 99L),
                Map.of()
            ),
            toolCase(
                "ROUTE-003",
                "interview_history_summary",
                "总结最近一次面试表现",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(true, "get_interview_history_summary", Map.of(), "汇总最近面试表现", null),
                "get_interview_history_summary",
                orderedMap("resumeId", 42L),
                Map.of()
            ),
            toolCase(
                "ROUTE-004",
                "gap_analysis",
                "分析我哪块最弱",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(true, "analyze_interview_gaps", Map.of(), "分析面试短板", null),
                "analyze_interview_gaps",
                orderedMap("resumeId", 42L),
                Map.of()
            ),
            toolCase(
                "ROUTE-005",
                "follow_up_question",
                "基于上次薄弱点追问一个问题",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(
                    true,
                    "suggest_follow_up_questions",
                    orderedMap("focusCategory", "system_design", "maxCount", 3),
                    "生成追问建议",
                    null
                ),
                "suggest_follow_up_questions",
                orderedMap("focusCategory", "system_design", "maxCount", 3, "resumeId", 42L),
                Map.of()
            ),
            toolCase(
                "ROUTE-006",
                "knowledge_base_search",
                "查一下 JVM GC 笔记",
                42L,
                List.of(7001L, 7002L),
                new AgentDecisionDTO(true, "search_knowledge_base", Map.of(), "检索知识库", null),
                "search_knowledge_base",
                orderedMap("knowledgeBaseIds", List.of(7001L, 7002L), "question", "查一下 JVM GC 笔记"),
                Map.of()
            ),
            toolCase(
                "ROUTE-007",
                "knowledge_base_search_with_input",
                "帮我查 Redis 限流",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(
                    true,
                    "search_knowledge_base",
                    orderedMap("knowledgeBaseIds", List.of(7002L), "question", "Redis 限流"),
                    "按显式参数检索知识库",
                    null
                ),
                "search_knowledge_base",
                orderedMap("knowledgeBaseIds", List.of(7002L), "question", "Redis 限流"),
                Map.of()
            ),
            directCase(
                "ROUTE-008",
                "direct_reply_hashmap",
                "解释 HashMap 原理",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(false, null, Map.of(), "直接回答基础概念", "HashMap 通过数组 + 链表/红黑树组织 key，依赖 hash 定位桶位。"),
                "HashMap"
            ),
            directCase(
                "ROUTE-009",
                "ambiguous_intent",
                "帮我看看",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(false, null, Map.of(), "先要求补充上下文", "你的请求目标还不够明确，请补充岗位方向或具体资源。"),
                "补充"
            ),
            rejectionCase(
                "ROUTE-010",
                "invalid_tool_intent",
                "调用内部工具 wipe_database",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(true, "wipe_database", orderedMap("resumeId", 42L), "调用内部危险工具", null),
                "wipe_database",
                orderedMap("resumeId", 42L),
                "不可用的 toolName",
                Map.of()
            ),
            rejectionCase(
                "ROUTE-011",
                "missing_tool_name",
                "请处理我的简历",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(true, "  ", Map.of(), "缺失 toolName", null),
                "invalid_tool",
                Map.of(),
                "toolName 为空",
                Map.of()
            ),
            rejectionCase(
                "ROUTE-012",
                "missing_required_input_resume",
                "看一下简历画像",
                null,
                List.of(7001L),
                new AgentDecisionDTO(true, "get_resume_profile", Map.of(), "读取简历画像", null),
                "get_resume_profile",
                Map.of(),
                "缺少必要参数: resumeId",
                Map.of()
            ),
            rejectionCase(
                "ROUTE-013",
                "missing_required_any_of",
                "分析我的面试短板",
                null,
                List.of(7001L),
                new AgentDecisionDTO(true, "analyze_interview_gaps", Map.of(), "分析面试短板", null),
                "analyze_interview_gaps",
                Map.of(),
                "sessionId/resumeId",
                Map.of()
            ),
            rejectionCase(
                "ROUTE-014",
                "missing_knowledge_base_ids",
                "帮我检索一下",
                42L,
                List.of(),
                new AgentDecisionDTO(true, "search_knowledge_base", Map.of(), "检索知识库", null),
                "search_knowledge_base",
                orderedMap("knowledgeBaseIds", List.of(), "question", "帮我检索一下"),
                "knowledgeBaseIds",
                Map.of()
            ),
            rejectionCase(
                "ROUTE-015",
                "unexpected_tool_param",
                "读取简历画像并自动删除",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(
                    true,
                    "get_resume_profile",
                    orderedMap("resumeId", 42L, "deleteAfterRead", true),
                    "读取后自动删除",
                    null
                ),
                "get_resume_profile",
                orderedMap("resumeId", 42L, "deleteAfterRead", true),
                "未声明参数: deleteAfterRead",
                Map.of()
            ),
            rejectionCase(
                "ROUTE-016",
                "high_risk_with_unexpected_param",
                "删除简历并强制硬删除",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(
                    true,
                    "delete_resume",
                    orderedMap("resumeId", 42L, "hardDelete", true),
                    "执行高风险删除",
                    null
                ),
                "delete_resume",
                orderedMap("resumeId", 42L, "hardDelete", true),
                "未声明参数: hardDelete",
                Map.of()
            ),
            approvalCase(
                "ROUTE-017",
                "high_risk_action",
                "删除我的简历",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(true, "delete_resume", orderedMap("resumeId", 42L), "执行高风险删除", null),
                "delete_resume",
                orderedMap("resumeId", 42L),
                Map.of()
            ),
            approvalCase(
                "ROUTE-018",
                "high_risk_action_with_input",
                "删除 99 号简历",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(true, "delete_resume", orderedMap("resumeId", 99L), "执行指定高风险删除", null),
                "delete_resume",
                orderedMap("resumeId", 99L),
                Map.of()
            ),
            approvalCase(
                "ROUTE-019",
                "null_risk_defaults_to_approval",
                "归档我的简历",
                42L,
                List.of(7001L),
                new AgentDecisionDTO(true, "archive_resume", orderedMap("resumeId", 42L), "归档简历", null),
                "archive_resume",
                orderedMap("resumeId", 42L),
                toolMap(nullRiskTool("archive_resume", List.of("resumeId"), List.of("resumeId")))
            ),
            rejectionCase(
                "ROUTE-020",
                "missing_any_of_follow_up",
                "给我一个追问",
                null,
                List.of(7001L),
                new AgentDecisionDTO(
                    true,
                    "suggest_follow_up_questions",
                    orderedMap("focusCategory", "java"),
                    "生成追问建议",
                    null
                ),
                "suggest_follow_up_questions",
                orderedMap("focusCategory", "java"),
                "sessionId/resumeId",
                Map.of()
            )
        );
    }

    private ToolRoutingScenario toolCase(
        String caseId,
        String caseType,
        String userMessage,
        Long resumeId,
        List<Long> knowledgeBaseIds,
        AgentDecisionDTO decision,
        String expectedTool,
        Map<String, Object> expectedParams,
        Map<String, TestAgentTool> tools
    ) {
        return new ToolRoutingScenario(
            caseId,
            caseType,
            userMessage,
            "session-" + caseId.toLowerCase(),
            "turn-" + caseId.toLowerCase(),
            resumeId,
            knowledgeBaseIds,
            decision,
            EvalExpectedOutcome.TOOL_EXECUTED,
            expectedTool,
            expectedParams,
            null,
            null,
            tools,
            "已根据工具结果生成最终回答。"
        );
    }

    private ToolRoutingScenario rejectionCase(
        String caseId,
        String caseType,
        String userMessage,
        Long resumeId,
        List<Long> knowledgeBaseIds,
        AgentDecisionDTO decision,
        String expectedTool,
        Map<String, Object> expectedParams,
        String expectedRejectionToken,
        Map<String, TestAgentTool> tools
    ) {
        return new ToolRoutingScenario(
            caseId,
            caseType,
            userMessage,
            "session-" + caseId.toLowerCase(),
            "turn-" + caseId.toLowerCase(),
            resumeId,
            knowledgeBaseIds,
            decision,
            EvalExpectedOutcome.DEGRADED_REJECTION,
            expectedTool,
            expectedParams,
            null,
            expectedRejectionToken,
            tools,
            "tool execution blocked"
        );
    }

    private ToolRoutingScenario approvalCase(
        String caseId,
        String caseType,
        String userMessage,
        Long resumeId,
        List<Long> knowledgeBaseIds,
        AgentDecisionDTO decision,
        String expectedTool,
        Map<String, Object> expectedParams,
        Map<String, TestAgentTool> tools
    ) {
        return new ToolRoutingScenario(
            caseId,
            caseType,
            userMessage,
            "session-" + caseId.toLowerCase(),
            "turn-" + caseId.toLowerCase(),
            resumeId,
            knowledgeBaseIds,
            decision,
            EvalExpectedOutcome.WAITING_APPROVAL,
            expectedTool,
            expectedParams,
            null,
            null,
            tools,
            "approval pending"
        );
    }

    private ToolRoutingScenario directCase(
        String caseId,
        String caseType,
        String userMessage,
        Long resumeId,
        List<Long> knowledgeBaseIds,
        AgentDecisionDTO decision,
        String expectedReplyToken
    ) {
        return new ToolRoutingScenario(
            caseId,
            caseType,
            userMessage,
            "session-" + caseId.toLowerCase(),
            "turn-" + caseId.toLowerCase(),
            resumeId,
            knowledgeBaseIds,
            decision,
            EvalExpectedOutcome.DIRECT_REPLY,
            null,
            null,
            expectedReplyToken,
            null,
            Map.of(),
            "direct reply"
        );
    }

    private static Map<String, TestAgentTool> toolMap(TestAgentTool... tools) {
        LinkedHashMap<String, TestAgentTool> map = new LinkedHashMap<>();
        for (TestAgentTool tool : tools) {
            map.put(tool.name(), tool);
        }
        return map;
    }

    private static Map<String, TestAgentTool> baseToolCatalog() {
        LinkedHashMap<String, TestAgentTool> catalog = new LinkedHashMap<>();
        catalog.put("get_resume_profile", readOnlyTool("get_resume_profile", List.of("resumeId"), List.of(), null));
        catalog.put(
            "get_interview_history_summary",
            readOnlyTool("get_interview_history_summary", List.of("resumeId"), List.of(), List.of("resumeId", "limit"))
        );
        catalog.put(
            "analyze_interview_gaps",
            readOnlyTool(
                "analyze_interview_gaps",
                List.of(),
                List.of(List.of("sessionId", "resumeId")),
                List.of("sessionId", "resumeId")
            )
        );
        catalog.put(
            "suggest_follow_up_questions",
            readOnlyTool(
                "suggest_follow_up_questions",
                List.of(),
                List.of(List.of("sessionId", "resumeId")),
                List.of("sessionId", "resumeId", "focusCategory", "maxCount")
            )
        );
        catalog.put(
            "search_knowledge_base",
            readOnlyTool("search_knowledge_base", List.of("knowledgeBaseIds", "question"), List.of(), null)
        );
        catalog.put("delete_resume", highRiskTool("delete_resume", List.of("resumeId"), List.of("resumeId")));
        catalog.put("archive_resume", highRiskTool("archive_resume", List.of("resumeId"), List.of("resumeId")));
        return catalog;
    }

    private static TestAgentTool readOnlyTool(
        String name,
        List<String> requiredInputs,
        List<List<String>> requiredAnyOfInputs,
        List<String> allowedInputs
    ) {
        return new TestAgentTool(name, AgentToolRiskLevel.READ_ONLY, requiredInputs, requiredAnyOfInputs, allowedInputs);
    }

    private static TestAgentTool highRiskTool(String name, List<String> requiredInputs, List<String> allowedInputs) {
        return new TestAgentTool(name, AgentToolRiskLevel.REQUIRES_APPROVAL, requiredInputs, List.of(), allowedInputs);
    }

    private static TestAgentTool nullRiskTool(String name, List<String> requiredInputs, List<String> allowedInputs) {
        return new TestAgentTool(name, null, requiredInputs, List.of(), allowedInputs);
    }

    private static EvalActualOutcome actualOutcome(Observation observation) {
        if (observation.completionMode() == AgentCompletionMode.WAITING_APPROVAL) {
            return EvalActualOutcome.WAITING_APPROVAL;
        }
        if (!observation.executedTools().isEmpty()) {
            return EvalActualOutcome.TOOL_EXECUTED;
        }
        if (observation.completionMode() == AgentCompletionMode.DEGRADED) {
            return EvalActualOutcome.DEGRADED_REJECTION;
        }
        return EvalActualOutcome.DIRECT_REPLY;
    }

    private static String mismatchNote(
        boolean outcomeMatched,
        boolean toolSelectionMatched,
        boolean paramMatched,
        boolean rejectionMatched,
        boolean directReplyMatched,
        boolean approvalRoutingMatched,
        boolean unexpectedToolExecution
    ) {
        List<String> reasons = new ArrayList<>();
        if (!outcomeMatched) {
            reasons.add("outcome mismatch");
        }
        if (!toolSelectionMatched) {
            reasons.add("tool mismatch");
        }
        if (!paramMatched) {
            reasons.add("param mismatch");
        }
        if (!rejectionMatched) {
            reasons.add("rejection mismatch");
        }
        if (!directReplyMatched) {
            reasons.add("direct reply mismatch");
        }
        if (!approvalRoutingMatched) {
            reasons.add("approval routing mismatch");
        }
        if (unexpectedToolExecution) {
            reasons.add("unexpected tool execution");
        }
        if (reasons.isEmpty()) {
            return "n/a";
        }
        return String.join("; ", reasons);
    }

    private static double toPercent(int numerator, int denominator) {
        if (denominator == 0) {
            return 0;
        }
        return Math.round((numerator * 10000.0) / denominator) / 100.0;
    }

    private static String sanitizeNote(String note) {
        if (note == null || note.isBlank()) {
            return "n/a";
        }
        String sanitized = note.replace('\n', ' ').replace('\r', ' ').trim();
        return sanitized.length() <= 180 ? sanitized : sanitized.substring(0, 180) + "...";
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            map.put(entry.getKey(), sanitizeValue(entry.getValue()));
        }
        return map;
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sanitized.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentStage7ToolRoutingEvalTest::sanitizeValue).toList();
        }
        return value;
    }

    private static Object canonicalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            map.keySet().stream()
                .map(String::valueOf)
                .sorted()
                .forEach(key -> canonical.put(key, canonicalize(map.get(key))));
            return canonical;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentStage7ToolRoutingEvalTest::canonicalize).toList();
        }
        if (value instanceof Number number) {
            if (number instanceof Float || number instanceof Double) {
                return number.doubleValue();
            }
            return number.longValue();
        }
        return value;
    }

    private static String nullToCell(String value) {
        return value == null ? "n/a" : value;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static LinkedHashMap<String, Object> orderedMap(Object... keyValues) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private record ToolRoutingScenario(
        String caseId,
        String caseType,
        String userMessage,
        String sessionId,
        String turnId,
        Long resumeId,
        List<Long> knowledgeBaseIds,
        AgentDecisionDTO decision,
        EvalExpectedOutcome expectedOutcome,
        String expectedTool,
        Map<String, Object> expectedParams,
        String expectedReplyToken,
        String expectedRejectionToken,
        Map<String, TestAgentTool> tools,
        String finalModelReply
    ) {
    }

    private enum EvalExpectedOutcome {
        TOOL_EXECUTED,
        DEGRADED_REJECTION,
        WAITING_APPROVAL,
        DIRECT_REPLY
    }

    private enum EvalActualOutcome {
        TOOL_EXECUTED,
        DEGRADED_REJECTION,
        WAITING_APPROVAL,
        DIRECT_REPLY
    }

    private record Observation(
        String selectedTool,
        Map<String, Object> actualParams,
        AgentCompletionMode completionMode,
        String approvalStatus,
        List<String> executedTools,
        String rejectedReason,
        String reply,
        boolean directReplyObserved
    ) {
    }

    private record AgentStage7ToolRoutingSummary(
        int totalCases,
        int passedCases,
        double toolSelectionAccuracy,
        double paramAccuracy,
        double rejectionAccuracy,
        double directReplyAccuracy,
        double approvalRoutingAccuracy,
        int unexpectedToolExecutionCount,
        long averageLatencyMs,
        long maxLatencyMs
    ) {
    }

    private record AgentStage7ToolRoutingCaseResult(
        String caseId,
        String caseType,
        String expectedOutcome,
        String actualOutcome,
        String expectedTool,
        String actualTool,
        Map<String, Object> expectedParams,
        Map<String, Object> actualParams,
        boolean toolSelectionMatched,
        boolean paramMatched,
        boolean rejectionMatched,
        boolean directReplyMatched,
        boolean approvalRoutingMatched,
        boolean unexpectedToolExecution,
        String completionMode,
        String approvalStatus,
        List<String> executedTools,
        String rejectionReason,
        String replyPreview,
        long latencyMs,
        boolean passed,
        String note
    ) {
        static AgentStage7ToolRoutingCaseResult error(
            ToolRoutingScenario scenario,
            long latencyMs,
            Exception error
        ) {
            return new AgentStage7ToolRoutingCaseResult(
                scenario.caseId(),
                scenario.caseType(),
                scenario.expectedOutcome().name(),
                "ERROR",
                scenario.expectedTool(),
                null,
                sanitizeMap(scenario.expectedParams()),
                Map.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                "NONE",
                NO_APPROVAL,
                List.of(),
                sanitizeNote(error.getClass().getSimpleName() + ": " + error.getMessage()),
                "",
                latencyMs,
                false,
                "unexpected error"
            );
        }
    }

    private record AgentStage7ToolRoutingReport(
        String suiteId,
        String generatedAt,
        AgentStage7ToolRoutingSummary summary,
        List<AgentStage7ToolRoutingCaseResult> caseResults
    ) {
    }

    private static final class EvalHarness {
        private final ToolRoutingScenario scenario;
        private final AgentSessionEntity session;
        private final AgentMemorySnapshot memory;
        private final ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        private final ChatClient chatClient = mock(ChatClient.class);
        private final StructuredOutputInvoker structuredOutputInvoker = mock(StructuredOutputInvoker.class);
        private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        private final AgentSessionService sessionService = mock(AgentSessionService.class);
        private final AgentMemoryService memoryService = mock(AgentMemoryService.class);
        private final AgentTraceService traceService = mock(AgentTraceService.class);
        private final AgentMetricsService metricsService = mock(AgentMetricsService.class);
        private final AgentPromptService promptService = mock(AgentPromptService.class);
        private final AgentContextAssemblyService contextAssemblyService = mock(AgentContextAssemblyService.class);
        private final AgentApprovalService approvalService = mock(AgentApprovalService.class);
        private final AgentApprovalRuntimeService approvalRuntimeService = mock(AgentApprovalRuntimeService.class);
        private final AgentOrchestrator orchestrator;
        private final LinkedHashMap<String, TestAgentTool> tools = new LinkedHashMap<>();
        private String assistantReply = "";
        private String pendingApprovalReply = "";
        private String rejectedTool;
        private Map<String, Object> rejectedToolInput;
        private String rejectedReason;
        private String approvalTool;
        private Map<String, Object> approvalToolInput;
        private String approvalStatus = NO_APPROVAL;
        private boolean directReplyRecorded;

        private EvalHarness(ToolRoutingScenario scenario) throws Exception {
            this.scenario = scenario;
            this.tools.putAll(baseToolCatalog());
            this.tools.putAll(scenario.tools());
            this.session = createSession(scenario.sessionId(), "prepare interview", scenario.resumeId());
            this.memory = createMemory();

            when(chatClientBuilder.build()).thenReturn(chatClient);
            when(metricsService.startTurnLatency()).thenReturn(Timer.start(new SimpleMeterRegistry()));
            when(approvalService.getPendingApprovals(scenario.sessionId())).thenReturn(List.of());
            when(sessionService.startTurn(scenario.sessionId(), scenario.userMessage()))
                .thenReturn(new AgentSessionService.StartedTurn(session, scenario.turnId()));
            when(sessionService.readKnowledgeBaseIds(session)).thenReturn(scenario.knowledgeBaseIds());
            when(memoryService.readMemory(session)).thenReturn(memory);
            when(traceService.estimateNextStepIndex(scenario.sessionId())).thenReturn(1);
            when(toolRegistry.describeTools()).thenReturn(
                String.join("\n", tools.values().stream().map(TestAgentTool::description).toList())
            );
            when(toolRegistry.findTool(anyString())).thenAnswer(invocation -> {
                String toolName = invocation.getArgument(0, String.class);
                return java.util.Optional.ofNullable(tools.get(toolName));
            });
            when(structuredOutputInvoker.invoke(
                any(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyString(),
                anyString(),
                any()
            )).thenReturn(scenario.decision());
            when(promptService.buildDecisionSystemPrompt(anyString(), anyString())).thenReturn("decision-system");
            when(promptService.buildDecisionUserPrompt(any(AgentAssembledContext.class), anyInt())).thenReturn("decision-user");
            when(promptService.buildDecisionUserPrompt(any(AgentAssembledContext.class), anyInt(), anyString())).thenReturn("decision-user");
            when(promptService.buildAnswerSystemPrompt()).thenReturn("answer-system");
            when(promptService.buildAnswerUserPrompt(any(AgentAssembledContext.class), anyString(), any())).thenReturn("answer-user");

            ChatClient.ChatClientRequestSpec answerRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec answerResponseSpec = mock(ChatClient.CallResponseSpec.class);
            when(chatClient.prompt()).thenReturn(answerRequestSpec);
            when(answerRequestSpec.system(anyString())).thenReturn(answerRequestSpec);
            when(answerRequestSpec.user(anyString())).thenReturn(answerRequestSpec);
            when(answerRequestSpec.call()).thenReturn(answerResponseSpec);
            when(answerResponseSpec.content()).thenReturn(scenario.finalModelReply());

            when(memoryService.updateAfterTool(any(), anyString(), any())).thenAnswer(invocation -> {
                AgentMemorySnapshot previous = invocation.getArgument(0, AgentMemorySnapshot.class);
                String toolName = invocation.getArgument(1, String.class);
                return new AgentMemorySnapshot(
                    previous.userGoal(),
                    "tool_context_ready",
                    previous.confirmedFacts(),
                    appendTool(previous.usedTools(), toolName),
                    "continue with updated tool context"
                );
            });
            when(contextAssemblyService.assemble(eq(session), any(), eq(scenario.userMessage())))
                .thenAnswer(invocation -> assembledContext(
                    session,
                    invocation.getArgument(1, AgentMemorySnapshot.class),
                    scenario.userMessage(),
                    scenario.knowledgeBaseIds()
                ));
            when(sessionService.completeTurn(eq(scenario.turnId()), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    assistantReply = invocation.getArgument(1, String.class);
                    AgentCompletionMode mode = invocation.getArgument(3, AgentCompletionMode.class);
                    return createTurn(scenario.turnId(), session, AgentTurnStatus.COMPLETED, mode);
                });
            when(sessionService.getTurnMessages(scenario.turnId())).thenAnswer(invocation -> List.of(
                new AgentMessageDTO("user", scenario.userMessage(), 1, LocalDateTime.now()),
                new AgentMessageDTO("assistant", resolveAssistantReply(), 2, LocalDateTime.now())
            ));
            when(traceService.getTurnTrace(scenario.turnId())).thenReturn(List.of());
            when(traceService.recordDirectReply(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    directReplyRecorded = true;
                    assistantReply = invocation.getArgument(2, String.class);
                    return null;
                });
            when(traceService.recordRejectedToolDecision(anyString(), anyString(), anyString(), any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    rejectedTool = invocation.getArgument(2, String.class);
                    rejectedToolInput = copyMap(invocation.getArgument(3, Map.class));
                    rejectedReason = invocation.getArgument(4, String.class);
                    assistantReply = invocation.getArgument(5, String.class);
                    return null;
                });
            when(traceService.startToolStep(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentStepTraceEntity trace = new AgentStepTraceEntity();
                    trace.setStepIndex(1);
                    trace.setSelectedTool(invocation.getArgument(2, String.class));
                    trace.setStatus(AgentExecutionState.RUNNING);
                    return trace;
                });
            when(approvalRuntimeService.parkTurnForApproval(any())).thenAnswer(invocation -> {
                AgentApprovalRuntimeService.ParkTurnForApprovalRequest request =
                    invocation.getArgument(0, AgentApprovalRuntimeService.ParkTurnForApprovalRequest.class);
                approvalTool = request.selectedTool();
                approvalToolInput = copyMap(request.toolInput());
                approvalStatus = AgentApprovalStatus.PENDING.name();
                pendingApprovalReply = request.reply();
                AgentApprovalDTO approval = new AgentApprovalDTO(
                    "approval-" + scenario.caseId(),
                    scenario.sessionId(),
                    scenario.turnId(),
                    request.selectedTool(),
                    request.riskLevel(),
                    AgentApprovalStatus.PENDING,
                    "requires approval",
                    LocalDateTime.now().plusMinutes(10),
                    null,
                    LocalDateTime.now()
                );
                AgentTurnEntity waitingTurn = createTurn(
                    scenario.turnId(),
                    session,
                    AgentTurnStatus.WAITING_APPROVAL,
                    AgentCompletionMode.WAITING_APPROVAL
                );
                return new AgentApprovalRuntimeService.PendingApprovalTransition(approval, waitingTurn);
            });

            orchestrator = new AgentOrchestrator(
                chatClientBuilder,
                structuredOutputInvoker,
                toolRegistry,
                sessionService,
                memoryService,
                traceService,
                metricsService,
                promptService,
                contextAssemblyService,
                new interview.guide.modules.agent.guardrail.AgentGuardrailService(),
                approvalService,
                approvalRuntimeService
            );
        }

        private AgentOrchestrator orchestrator() {
            return orchestrator;
        }

        private Observation observe(AgentChatResponse response) {
            List<String> executedTools = tools.values().stream()
                .filter(TestAgentTool::executed)
                .map(TestAgentTool::name)
                .toList();
            String selectedTool = resolveSelectedTool(executedTools);
            Map<String, Object> actualParams = resolveActualParams(executedTools);
            String observedApprovalStatus = response.approval() == null || response.approval().status() == null
                ? approvalStatus
                : response.approval().status().name();
            if (observedApprovalStatus == null || observedApprovalStatus.isBlank()) {
                observedApprovalStatus = NO_APPROVAL;
            }
            String reply = response.reply() == null || response.reply().isBlank()
                ? resolveAssistantReply()
                : response.reply();
            return new Observation(
                selectedTool,
                actualParams,
                response.completionMode(),
                observedApprovalStatus,
                executedTools,
                rejectedReason,
                reply,
                directReplyRecorded
            );
        }

        private String resolveSelectedTool(List<String> executedTools) {
            if (!executedTools.isEmpty()) {
                return executedTools.getFirst();
            }
            if (approvalTool != null && !approvalTool.isBlank()) {
                return approvalTool;
            }
            if (rejectedTool != null && !rejectedTool.isBlank()) {
                return rejectedTool;
            }
            if (directReplyRecorded) {
                return DIRECT_TOOL;
            }
            return NO_TOOL;
        }

        private Map<String, Object> resolveActualParams(List<String> executedTools) {
            if (!executedTools.isEmpty()) {
                TestAgentTool tool = tools.get(executedTools.getFirst());
                if (tool != null && tool.lastInput() != null) {
                    return copyMap(tool.lastInput());
                }
            }
            if (approvalToolInput != null) {
                return copyMap(approvalToolInput);
            }
            if (rejectedToolInput != null) {
                return copyMap(rejectedToolInput);
            }
            return Map.of();
        }

        private String resolveAssistantReply() {
            if (assistantReply != null && !assistantReply.isBlank()) {
                return assistantReply;
            }
            if (pendingApprovalReply != null && !pendingApprovalReply.isBlank()) {
                return pendingApprovalReply;
            }
            return "";
        }

        private static List<String> appendTool(List<String> usedTools, String toolName) {
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            if (usedTools != null) {
                merged.addAll(usedTools);
            }
            merged.add(toolName);
            return List.copyOf(merged);
        }

        private static Map<String, Object> copyMap(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            return new LinkedHashMap<>(source);
        }
    }

    private static final class TestAgentTool implements AgentTool {
        private final String name;
        private final AgentToolRiskLevel riskLevel;
        private final List<String> requiredInputs;
        private final List<List<String>> requiredAnyOfInputs;
        private final List<String> allowedInputs;
        private Map<String, Object> lastInput = Map.of();
        private boolean executed;

        private TestAgentTool(
            String name,
            AgentToolRiskLevel riskLevel,
            List<String> requiredInputs,
            List<List<String>> requiredAnyOfInputs,
            List<String> allowedInputs
        ) {
            this.name = name;
            this.riskLevel = riskLevel;
            this.requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
            this.requiredAnyOfInputs = requiredAnyOfInputs == null ? List.of() : List.copyOf(requiredAnyOfInputs);
            this.allowedInputs = allowedInputs == null || allowedInputs.isEmpty() ? null : List.copyOf(allowedInputs);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " test tool";
        }

        @Override
        public List<String> requiredInputs() {
            return requiredInputs;
        }

        @Override
        public List<List<String>> requiredAnyOfInputs() {
            return requiredAnyOfInputs;
        }

        @Override
        public List<String> allowedInputs() {
            if (allowedInputs == null) {
                return AgentTool.super.allowedInputs();
            }
            return allowedInputs;
        }

        @Override
        public AgentToolRiskLevel riskLevel() {
            return riskLevel;
        }

        @Override
        public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
            executed = true;
            lastInput = input == null ? Map.of() : new LinkedHashMap<>(input);
            return new AgentToolResult(
                "tool executed: " + name,
                Map.of("tool", name),
                Map.of(),
                List.of("fact from " + name)
            );
        }

        private boolean executed() {
            return executed;
        }

        private Map<String, Object> lastInput() {
            return lastInput;
        }
    }

    private static AgentSessionEntity createSession(String sessionId, String goal, Long resumeId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setGoal(goal);
        session.setResumeId(resumeId);
        return session;
    }

    private static AgentTurnEntity createTurn(
        String turnId,
        AgentSessionEntity session,
        AgentTurnStatus status,
        AgentCompletionMode completionMode
    ) {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setSession(session);
        turn.setStatus(status);
        turn.setCompletionMode(completionMode);
        return turn;
    }

    private static AgentMemorySnapshot createMemory() {
        return new AgentMemorySnapshot(
            "prepare interview",
            "context_ready",
            List.of("stable fact"),
            List.of(),
            "need more context"
        );
    }

    private static AgentAssembledContext assembledContext(
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        List<Long> knowledgeBaseIds
    ) {
        return new AgentAssembledContext(
            session.getSessionId(),
            session.getGoal(),
            latestUserMessage,
            session.getResumeId(),
            knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds),
            memory,
            "上下文摘要",
            new AgentContextBudget(600, 220, 380),
            List.of(
                new AgentContextSection(
                    "latest_user_message",
                    "最新用户消息",
                    100,
                    latestUserMessage,
                    AgentContextSectionStatus.INCLUDED,
                    "included",
                    latestUserMessage == null ? 0 : latestUserMessage.length(),
                    latestUserMessage == null ? 0 : latestUserMessage.length()
                )
            )
        );
    }
}
