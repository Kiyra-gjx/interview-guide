package interview.guide.modules.agent.eval;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.agent.guardrail.AgentGuardrailService;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentApprovalEntity;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import interview.guide.modules.agent.model.AgentChatRequest;
import interview.guide.modules.agent.model.AgentChatResponse;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentDecisionDTO;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentExecutionSummaryDTO;
import interview.guide.modules.agent.model.AgentHandoffResultDTO;
import interview.guide.modules.agent.model.AgentLoopStopReason;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentMessageDTO;
import interview.guide.modules.agent.model.AgentRuntimeConfig;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTerminalState;
import interview.guide.modules.agent.model.AgentTraceDTO;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStage5BenchmarkEvalTest {

    private static final String SUITE_ID = "stage-5-benchmark";
    private static final String JSON_REPORT_NAME = "stage-5-benchmark-report.json";
    private static final String MARKDOWN_REPORT_NAME = "stage-5-benchmark-report.md";
    private static final String NO_STOP_REASON = "NONE";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("should run the fixed stage 5 benchmark suite and persist reports")
    void shouldRunTheFixedStage5BenchmarkSuiteAndPersistReports() throws Exception {
        Path reportDirectory = Path.of("build", "reports", "agent-eval");

        AgentStage5BenchmarkReport report = runFixedSuite(reportDirectory);

        assertThat(report.summary().totalCases()).isEqualTo(4);
        assertThat(report.summary().passedCases()).isEqualTo(4);
        assertThat(report.summary().multiStepCases()).isEqualTo(2);
        assertThat(report.summary().exhaustedCases()).isEqualTo(1);
        assertThat(report.summary().handoffAcceptedCases()).isEqualTo(1);
        assertThat(report.summary().handoffRejectedCases()).isEqualTo(1);
        assertThat(report.summary().replayBlockedCases()).isEqualTo(1);
        assertThat(report.summary().stopReasonCounts())
            .containsEntry(AgentLoopStopReason.DIRECT_REPLY.name(), 1L)
            .containsEntry(AgentLoopStopReason.HANDOFF_NOT_ALLOWED.name(), 1L)
            .containsEntry(AgentLoopStopReason.STEP_BUDGET_EXHAUSTED.name(), 1L)
            .containsEntry(AgentLoopStopReason.APPROVAL_REPLAY_BLOCKED.name(), 1L);
        assertThat(report.summary().terminalStateCounts())
            .containsEntry(AgentTerminalState.SUCCESS.name(), 1L)
            .containsEntry(AgentTerminalState.DEGRADED.name(), 2L)
            .containsEntry(AgentTerminalState.EXHAUSTED.name(), 1L);
        assertThat(report.caseResults()).allMatch(AgentStage5BenchmarkCaseResult::passed);
        assertThat(report.caseResults()).allMatch(result -> result.latencyMs() >= 0);

        Path jsonReport = reportDirectory.resolve(JSON_REPORT_NAME);
        Path markdownReport = reportDirectory.resolve(MARKDOWN_REPORT_NAME);

        assertThat(Files.exists(jsonReport)).isTrue();
        assertThat(Files.exists(markdownReport)).isTrue();
        assertThat(Files.readString(markdownReport))
            .contains("平均执行步数")
            .contains("stopReason 分布")
            .contains("terminalState 分布")
            .contains("委派成功样例数");
    }

    private AgentStage5BenchmarkReport runFixedSuite(Path reportDirectory) throws Exception {
        List<AgentStage5BenchmarkScenario> scenarios = List.of(
            new AgentStage5BenchmarkScenario(
                "bounded_handoff_success",
                "多步模式下只读委派后继续收口",
                new AgentStage5BenchmarkExpectation(
                    true,
                    2,
                    AgentLoopStopReason.DIRECT_REPLY.name(),
                    NO_STOP_REASON,
                    AgentTerminalState.SUCCESS.name(),
                    false
                ),
                this::runBoundedHandoffSuccessCase
            ),
            new AgentStage5BenchmarkScenario(
                "handoff_rejected_single_step",
                "单步路径下委派被边界拒绝并降级收口",
                new AgentStage5BenchmarkExpectation(
                    false,
                    1,
                    AgentLoopStopReason.HANDOFF_NOT_ALLOWED.name(),
                    NO_STOP_REASON,
                    AgentTerminalState.DEGRADED.name(),
                    false
                ),
                this::runHandoffRejectedSingleStepCase
            ),
            new AgentStage5BenchmarkScenario(
                "step_budget_exhausted",
                "多步预算耗尽后进入 exhausted 终态",
                new AgentStage5BenchmarkExpectation(
                    true,
                    1,
                    AgentLoopStopReason.STEP_BUDGET_EXHAUSTED.name(),
                    AgentLoopStopReason.STEP_BUDGET_EXHAUSTED.name(),
                    AgentTerminalState.EXHAUSTED.name(),
                    false
                ),
                this::runStepBudgetExhaustedCase
            ),
            new AgentStage5BenchmarkScenario(
                "approval_replay_blocked",
                "审批恢复场景下阻断重复副作用执行",
                new AgentStage5BenchmarkExpectation(
                    false,
                    0,
                    AgentLoopStopReason.APPROVAL_REPLAY_BLOCKED.name(),
                    NO_STOP_REASON,
                    AgentTerminalState.DEGRADED.name(),
                    false
                ),
                this::runApprovalReplayBlockedCase
            )
        );

        List<AgentStage5BenchmarkCaseResult> caseResults = new java.util.ArrayList<>();
        for (AgentStage5BenchmarkScenario scenario : scenarios) {
            long startedAt = System.nanoTime();
            AgentStage5BenchmarkActual actual;
            try {
                actual = scenario.execution().run();
            } catch (Exception error) {
                actual = AgentStage5BenchmarkActual.fromThrowable(error);
            }
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            caseResults.add(new AgentStage5BenchmarkCaseResult(
                scenario.caseId(),
                scenario.description(),
                scenario.expectation().multiStepEnabled(),
                actual.multiStepEnabled(),
                scenario.expectation().executedSteps(),
                actual.executedSteps(),
                scenario.expectation().stopReason(),
                actual.stopReason(),
                scenario.expectation().budgetStopReason(),
                actual.budgetStopReason(),
                scenario.expectation().terminalState(),
                actual.terminalState(),
                scenario.expectation().recoverable(),
                actual.recoverable(),
                latencyMs,
                actual.matches(scenario.expectation()),
                actual.note()
            ));
        }

        AgentStage5BenchmarkReport report = new AgentStage5BenchmarkReport(
            SUITE_ID,
            LocalDateTime.now().toString(),
            buildSummary(caseResults),
            caseResults
        );
        writeReport(reportDirectory, report);
        return report;
    }

    private AgentStage5BenchmarkSummary buildSummary(List<AgentStage5BenchmarkCaseResult> caseResults) {
        int totalCases = caseResults.size();
        int passedCases = (int) caseResults.stream().filter(AgentStage5BenchmarkCaseResult::passed).count();
        int multiStepCases = (int) caseResults.stream().filter(AgentStage5BenchmarkCaseResult::actualMultiStepEnabled).count();
        double averageExecutedSteps = Math.round(caseResults.stream()
            .mapToInt(AgentStage5BenchmarkCaseResult::actualExecutedSteps)
            .average()
            .orElse(0) * 100.0) / 100.0;
        long averageLatencyMs = Math.round(caseResults.stream().mapToLong(AgentStage5BenchmarkCaseResult::latencyMs).average().orElse(0));
        long maxLatencyMs = caseResults.stream().mapToLong(AgentStage5BenchmarkCaseResult::latencyMs).max().orElse(0);
        int exhaustedCases = (int) caseResults.stream()
            .filter(result -> AgentTerminalState.EXHAUSTED.name().equals(result.actualTerminalState()))
            .count();
        int handoffAcceptedCases = (int) caseResults.stream()
            .filter(result -> result.actualMultiStepEnabled()
                && result.actualExecutedSteps() > 1
                && AgentLoopStopReason.DIRECT_REPLY.name().equals(result.actualStopReason()))
            .count();
        int handoffRejectedCases = (int) caseResults.stream()
            .filter(result -> AgentLoopStopReason.HANDOFF_NOT_ALLOWED.name().equals(result.actualStopReason()))
            .count();
        int replayBlockedCases = (int) caseResults.stream()
            .filter(result -> AgentLoopStopReason.APPROVAL_REPLAY_BLOCKED.name().equals(result.actualStopReason()))
            .count();

        Map<String, Long> stopReasonCounts = new TreeMap<>();
        Map<String, Long> terminalStateCounts = new TreeMap<>();
        for (AgentStage5BenchmarkCaseResult result : caseResults) {
            stopReasonCounts.merge(result.actualStopReason(), 1L, Long::sum);
            terminalStateCounts.merge(result.actualTerminalState(), 1L, Long::sum);
        }

        return new AgentStage5BenchmarkSummary(
            totalCases,
            passedCases,
            multiStepCases,
            averageExecutedSteps,
            averageLatencyMs,
            maxLatencyMs,
            exhaustedCases,
            handoffAcceptedCases,
            handoffRejectedCases,
            replayBlockedCases,
            stopReasonCounts,
            terminalStateCounts
        );
    }

    private void writeReport(Path reportDirectory, AgentStage5BenchmarkReport report) throws Exception {
        Files.createDirectories(reportDirectory);
        Files.writeString(
            reportDirectory.resolve(JSON_REPORT_NAME),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        );
        Files.writeString(reportDirectory.resolve(MARKDOWN_REPORT_NAME), toMarkdown(report));
    }

    private String toMarkdown(AgentStage5BenchmarkReport report) {
        AgentStage5BenchmarkSummary summary = report.summary();
        StringBuilder builder = new StringBuilder();
        builder.append("# Stage 5 Agent Benchmark Report\n\n");
        builder.append("- suite: ").append(report.suiteId()).append('\n');
        builder.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        builder.append("- totalCases: ").append(summary.totalCases()).append('\n');
        builder.append("- passedCases: ").append(summary.passedCases()).append('\n');
        builder.append("- multiStep 场景数: ").append(summary.multiStepCases()).append('\n');
        builder.append("- 平均执行步数: ").append(summary.averageExecutedSteps()).append('\n');
        builder.append("- 平均延迟: ").append(summary.averageLatencyMs()).append(" ms\n");
        builder.append("- 最大延迟: ").append(summary.maxLatencyMs()).append(" ms\n");
        builder.append("- 预算耗尽样例数: ").append(summary.exhaustedCases()).append('\n');
        builder.append("- 委派成功样例数: ").append(summary.handoffAcceptedCases()).append('\n');
        builder.append("- 委派拒绝样例数: ").append(summary.handoffRejectedCases()).append('\n');
        builder.append("- replay blocked 样例数: ").append(summary.replayBlockedCases()).append('\n');
        builder.append("- stopReason 分布: ").append(summary.stopReasonCounts()).append('\n');
        builder.append("- terminalState 分布: ").append(summary.terminalStateCounts()).append("\n\n");
        builder.append("| Case | MultiStep | Steps | Stop Reason | Budget Stop | Terminal | Recoverable | Passed | Note |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (AgentStage5BenchmarkCaseResult result : report.caseResults()) {
            builder.append("| ")
                .append(result.caseId())
                .append(" | ")
                .append(result.actualMultiStepEnabled())
                .append(" | ")
                .append(result.actualExecutedSteps())
                .append(" | ")
                .append(result.actualStopReason())
                .append(" | ")
                .append(result.actualBudgetStopReason())
                .append(" | ")
                .append(result.actualTerminalState())
                .append(" | ")
                .append(result.actualRecoverable())
                .append(" | ")
                .append(result.passed())
                .append(" | ")
                .append(result.note())
                .append(" |\n");
        }
        return builder.toString();
    }

    private AgentStage5BenchmarkActual runBoundedHandoffSuccessCase() {
        OrchestratorHarness harness = new OrchestratorHarness();
        String sessionId = "session-handoff";
        String turnId = "turn-handoff";
        AgentChatRequest request = new AgentChatRequest(
            "先帮我拆解一下现在最值得先讲的亮点，再给最终建议",
            new AgentRuntimeConfig(true, 3, 15_000L, 4_000)
        );
        AgentSessionEntity session = createSession(sessionId, "准备 Java 面试", 42L);
        AgentMemorySnapshot memory = createMemory();
        AgentMemorySnapshot delegatedMemory = new AgentMemorySnapshot(
            "准备 Java 面试",
            "delegated_context_ready",
            List.of("fact-1", "最值得先讲的是一个后端项目亮点"),
            List.of("subagent_handoff"),
            "围绕一个后端项目亮点给最终建议"
        );
        AgentStepTraceEntity handoffTrace = new AgentStepTraceEntity();
        AgentHandoffResultDTO handoffResult = new AgentHandoffResultDTO(
            "先聚焦一个能体现 Java 和 Spring Boot 深度的项目亮点",
            List.of("最值得先讲的是一个后端项目亮点"),
            "围绕一个后端项目亮点给最终建议",
            "先突出一个能体现 Java 与 Spring Boot 深度的项目亮点。"
        );
        List<AgentTraceDTO> trace = List.of(
            createTrace("subagent_handoff", AgentExecutionState.COMPLETED),
            createTrace("direct_answer", AgentExecutionState.COMPLETED)
        );
        List<AgentMessageDTO> messagesDelta = List.of(
            createMessage("user", request.message(), 1),
            createMessage("assistant", "先突出一个能体现 Java 与 Spring Boot 深度的项目亮点。", 2)
        );
        AgentTurnEntity completedTurn = createCompletedTurn(turnId, session, AgentCompletionMode.SUCCESS);

        when(harness.sessionService.startTurn(sessionId, request.message()))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(harness.memoryService.readMemory(session)).thenReturn(memory);
        when(harness.traceService.estimateNextStepIndex(sessionId)).thenReturn(1, 2);
        when(harness.toolRegistry.describeTools()).thenReturn("- get_resume_profile");
        when(harness.structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(
            new AgentDecisionDTO(
                false,
                null,
                Map.of(),
                "先把问题拆小再继续",
                null,
                true,
                "基于当前上下文拆解最值得先讲的亮点",
                "当前问题更适合先做只读拆解",
                "返回 summary / confirmedFacts / nextFocus"
            ),
            handoffResult,
            new AgentDecisionDTO(
                false,
                null,
                Map.of(),
                "上下文已足够直接给建议",
                "先突出一个能体现 Java 与 Spring Boot 深度的项目亮点。"
            )
        );
        when(harness.traceService.startToolStep(
            eq(turnId),
            eq("先把问题拆小再继续"),
            eq("subagent_handoff"),
            anyMap(),
            eq(memory)
        )).thenReturn(handoffTrace);
        when(harness.memoryService.updateAfterTool(eq(memory), eq("subagent_handoff"), any(AgentToolResult.class)))
            .thenReturn(delegatedMemory);
        when(harness.sessionService.completeTurn(
            eq(turnId),
            eq("先突出一个能体现 Java 与 Spring Boot 深度的项目亮点。"),
            eq(delegatedMemory),
            eq(AgentCompletionMode.SUCCESS)
        )).thenReturn(completedTurn);
        when(harness.traceService.getTurnTrace(turnId)).thenReturn(trace);
        when(harness.sessionService.getTurnMessages(turnId)).thenReturn(messagesDelta);

        AgentChatResponse response = harness.orchestrator.chat(sessionId, request);
        return AgentStage5BenchmarkActual.fromResponse(response, "多步只读委派后继续收口");
    }

    private AgentStage5BenchmarkActual runHandoffRejectedSingleStepCase() {
        OrchestratorHarness harness = new OrchestratorHarness();
        String sessionId = "session-handoff-rejected";
        String turnId = "turn-handoff-rejected";
        AgentChatRequest request = new AgentChatRequest("先帮我拆解回答结构");
        AgentSessionEntity session = createSession(sessionId, "准备 Java 面试", 42L);
        AgentMemorySnapshot memory = createMemory();
        List<AgentTraceDTO> trace = List.of(createTrace("subagent_handoff", AgentExecutionState.FAILED));
        List<AgentMessageDTO> messagesDelta = List.of(
            createMessage("user", request.message(), 1),
            createMessage("assistant", "这次请求不适合继续走子委派，我先不扩散执行。", 2)
        );
        AgentTurnEntity completedTurn = createCompletedTurn(turnId, session, AgentCompletionMode.DEGRADED);

        when(harness.sessionService.startTurn(sessionId, request.message()))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(harness.memoryService.readMemory(session)).thenReturn(memory);
        when(harness.traceService.estimateNextStepIndex(sessionId)).thenReturn(1);
        when(harness.toolRegistry.describeTools()).thenReturn("- get_resume_profile");
        when(harness.structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(new AgentDecisionDTO(
            false,
            null,
            Map.of(),
            "先做只读拆解再继续",
            null,
            true,
            "基于当前上下文拆解回答结构",
            "直接回答收益不稳定",
            "返回 summary / nextFocus"
        ));
        when(harness.sessionService.completeTurn(
            eq(turnId),
            anyString(),
            eq(memory),
            eq(AgentCompletionMode.DEGRADED)
        )).thenReturn(completedTurn);
        when(harness.traceService.getTurnTrace(turnId)).thenReturn(trace);
        when(harness.sessionService.getTurnMessages(turnId)).thenReturn(messagesDelta);

        AgentChatResponse response = harness.orchestrator.chat(sessionId, request);
        return AgentStage5BenchmarkActual.fromResponse(response, "单步路径下委派被显式拒绝");
    }

    private AgentStage5BenchmarkActual runStepBudgetExhaustedCase() {
        OrchestratorHarness harness = new OrchestratorHarness();
        String sessionId = "session-step-budget";
        String turnId = "turn-step-budget";
        AgentChatRequest request = new AgentChatRequest(
            "先读取我的简历，再继续推导",
            new AgentRuntimeConfig(true, 1, 15_000L, 4_000)
        );
        AgentSessionEntity session = createSession(sessionId, "准备 Java 面试", 42L);
        AgentMemorySnapshot memory = createMemory();
        AgentMemorySnapshot updatedMemory = new AgentMemorySnapshot(
            "准备 Java 面试",
            "resume_context_ready",
            List.of("已绑定简历ID: 42"),
            List.of("get_resume_profile"),
            "继续根据简历上下文给出建议"
        );
        AgentStepTraceEntity stepTrace = new AgentStepTraceEntity();
        AgentToolResult toolResult = new AgentToolResult(
            "已读取简历画像，包含摘要与优势。",
            Map.of("resumeId", 42L),
            Map.of(),
            List.of("已绑定简历ID: 42")
        );
        List<AgentTraceDTO> trace = List.of(
            createTrace("get_resume_profile", AgentExecutionState.COMPLETED),
            createTrace("bounded_loop", AgentExecutionState.FAILED)
        );
        List<AgentMessageDTO> messagesDelta = List.of(
            createMessage("user", request.message(), 1),
            createMessage("assistant", "本轮多步预算已用尽，我先停在当前结论。", 2)
        );
        AgentTurnEntity completedTurn = createCompletedTurn(turnId, session, AgentCompletionMode.DEGRADED);

        when(harness.sessionService.startTurn(sessionId, request.message()))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(harness.memoryService.readMemory(session)).thenReturn(memory);
        when(harness.traceService.estimateNextStepIndex(sessionId)).thenReturn(1);
        when(harness.toolRegistry.describeTools()).thenReturn("- get_resume_profile");
        when(harness.structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(new AgentDecisionDTO(true, "get_resume_profile", Map.of(), "先补齐简历上下文", null));
        when(harness.toolRegistry.findTool("get_resume_profile")).thenReturn(java.util.Optional.of(harness.tool));
        when(harness.tool.name()).thenReturn("get_resume_profile");
        when(harness.tool.requiredInputs()).thenReturn(List.of("resumeId"));
        when(harness.traceService.startToolStep(
            eq(turnId),
            eq("先补齐简历上下文"),
            eq("get_resume_profile"),
            anyMap(),
            eq(memory)
        )).thenReturn(stepTrace);
        when(harness.tool.execute(anyMap(), any())).thenReturn(toolResult);
        when(harness.memoryService.updateAfterTool(memory, "get_resume_profile", toolResult)).thenReturn(updatedMemory);
        when(harness.sessionService.completeTurn(
            eq(turnId),
            anyString(),
            eq(updatedMemory),
            eq(AgentCompletionMode.DEGRADED)
        )).thenReturn(completedTurn);
        when(harness.traceService.getTurnTrace(turnId)).thenReturn(trace);
        when(harness.sessionService.getTurnMessages(turnId)).thenReturn(messagesDelta);

        AgentChatResponse response = harness.orchestrator.chat(sessionId, request);
        return AgentStage5BenchmarkActual.fromResponse(response, "多步预算耗尽后停止");
    }

    private AgentStage5BenchmarkActual runApprovalReplayBlockedCase() {
        OrchestratorHarness harness = new OrchestratorHarness();
        String approvalId = "approval-approve-started";
        String sessionId = "session-approve-started";
        String turnId = "turn-approve-started";
        AgentSessionEntity session = createSession(sessionId, "prepare interview", 42L);
        AgentTurnEntity runningTurn = createTurn(turnId, session, AgentTurnStatus.RUNNING, null);
        runningTurn.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        AgentTurnEntity reclaimedTurn = createTurn(turnId, session, AgentTurnStatus.RUNNING, null);
        AgentStepTraceEntity traceEntity = new AgentStepTraceEntity();
        traceEntity.setTurn(runningTurn);
        traceEntity.setStatus(AgentExecutionState.RUNNING);
        AgentApprovalEntity approvalEntity = createApprovalEntity(approvalId, runningTurn, traceEntity, AgentApprovalStatus.APPROVED);
        approvalEntity.setSelectedTool("delete_resume");
        approvalEntity.setLatestUserMessage("delete this resume");
        AgentApprovalDTO approvedApproval = new AgentApprovalDTO(
            approvalId,
            sessionId,
            turnId,
            "delete_resume",
            AgentToolRiskLevel.REQUIRES_APPROVAL,
            AgentApprovalStatus.APPROVED,
            "approval required",
            approvalEntity.getExpiresAt(),
            LocalDateTime.now().minusMinutes(1),
            approvalEntity.getCreatedAt()
        );
        AgentMemorySnapshot memory = createMemory();
        AgentTurnEntity completedTurn = createCompletedTurn(turnId, session, AgentCompletionMode.DEGRADED);
        List<AgentTraceDTO> trace = List.of(createTrace("delete_resume", AgentExecutionState.FAILED));
        List<AgentMessageDTO> messagesDelta = List.of(
            createMessage("user", "delete this resume", 1),
            createMessage("assistant", "waiting approval", 2),
            createMessage("assistant", "approved replay blocked", 3)
        );

        when(harness.approvalService.withLockedApproval(eq(approvalId), any())).thenAnswer(invocation ->
            ((Function<AgentApprovalEntity, Object>) invocation.getArgument(1)).apply(approvalEntity)
        );
        when(harness.approvalService.toDTO(approvalEntity)).thenReturn(approvedApproval);
        when(harness.sessionService.claimTurnForApprovedExecution(turnId))
            .thenReturn(new AgentSessionService.ApprovedTurnClaim(true, reclaimedTurn));
        when(harness.memoryService.readMemory(session)).thenReturn(memory);
        when(harness.sessionService.completeTurn(
            eq(turnId),
            anyString(),
            eq(memory),
            eq(AgentCompletionMode.DEGRADED)
        )).thenReturn(completedTurn);
        when(harness.traceService.getTurnTrace(turnId)).thenReturn(trace);
        when(harness.sessionService.getTurnMessages(turnId)).thenReturn(messagesDelta);

        AgentChatResponse response = harness.orchestrator.approveApproval(approvalId);
        return AgentStage5BenchmarkActual.fromResponse(response, "审批恢复场景下阻断重复副作用执行");
    }

    private AgentSessionEntity createSession(String sessionId, String goal, Long resumeId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setGoal(goal);
        session.setResumeId(resumeId);
        return session;
    }

    private AgentTurnEntity createCompletedTurn(
        String turnId,
        AgentSessionEntity session,
        AgentCompletionMode completionMode
    ) {
        return createTurn(turnId, session, AgentTurnStatus.COMPLETED, completionMode);
    }

    private AgentTurnEntity createTurn(
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

    private AgentApprovalEntity createApprovalEntity(
        String approvalId,
        AgentTurnEntity turn,
        AgentStepTraceEntity trace,
        AgentApprovalStatus status
    ) {
        AgentApprovalEntity approval = new AgentApprovalEntity();
        approval.setApprovalId(approvalId);
        approval.setSession(turn.getSession());
        approval.setTurn(turn);
        approval.setTrace(trace);
        approval.setSelectedTool("delete_resume");
        approval.setRiskLevel(AgentToolRiskLevel.REQUIRES_APPROVAL);
        approval.setStatus(status);
        approval.setReason("高风险工具必须先审批后执行");
        approval.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        approval.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return approval;
    }

    private AgentMemorySnapshot createMemory() {
        return new AgentMemorySnapshot(
            "prepare interview",
            "goal_received",
            List.of("fact-1"),
            List.of(),
            "need more context"
        );
    }

    private AgentTraceDTO createTrace(String selectedTool, AgentExecutionState status) {
        return new AgentTraceDTO(
            1,
            "decision",
            selectedTool,
            "{}",
            "{}",
            null,
            "observation",
            createMemory(),
            createMemory(),
            List.of(),
            status,
            null,
            null,
            null,
            false,
            null,
            LocalDateTime.now()
        );
    }

    private AgentMessageDTO createMessage(String role, String content, int order) {
        return new AgentMessageDTO(role, content, order, LocalDateTime.now());
    }

    private record AgentStage5BenchmarkScenario(
        String caseId,
        String description,
        AgentStage5BenchmarkExpectation expectation,
        ScenarioExecution execution
    ) {
    }

    private record AgentStage5BenchmarkExpectation(
        boolean multiStepEnabled,
        int executedSteps,
        String stopReason,
        String budgetStopReason,
        String terminalState,
        boolean recoverable
    ) {
    }

    private record AgentStage5BenchmarkActual(
        boolean multiStepEnabled,
        int executedSteps,
        String stopReason,
        String budgetStopReason,
        String terminalState,
        boolean recoverable,
        String note
    ) {
        private static AgentStage5BenchmarkActual fromResponse(AgentChatResponse response, String note) {
            AgentExecutionSummaryDTO execution = response.execution();
            return new AgentStage5BenchmarkActual(
                execution.multiStepEnabled(),
                execution.executedSteps(),
                execution.stopReason() == null ? NO_STOP_REASON : execution.stopReason().name(),
                execution.budgetStopReason() == null ? NO_STOP_REASON : execution.budgetStopReason().name(),
                execution.terminalState() == null ? NO_STOP_REASON : execution.terminalState().name(),
                execution.recoverable(),
                sanitizeNote(note)
            );
        }

        private static AgentStage5BenchmarkActual fromThrowable(Exception error) {
            return new AgentStage5BenchmarkActual(
                false,
                0,
                "ERROR",
                NO_STOP_REASON,
                "ERROR",
                false,
                sanitizeNote(error.getClass().getSimpleName() + ": " + blankToDefault(error.getMessage(), "no_message"))
            );
        }

        private boolean matches(AgentStage5BenchmarkExpectation expectation) {
            return multiStepEnabled == expectation.multiStepEnabled()
                && executedSteps == expectation.executedSteps()
                && Objects.equals(stopReason, expectation.stopReason())
                && Objects.equals(budgetStopReason, expectation.budgetStopReason())
                && Objects.equals(terminalState, expectation.terminalState())
                && recoverable == expectation.recoverable();
        }
    }

    private record AgentStage5BenchmarkSummary(
        int totalCases,
        int passedCases,
        int multiStepCases,
        double averageExecutedSteps,
        long averageLatencyMs,
        long maxLatencyMs,
        int exhaustedCases,
        int handoffAcceptedCases,
        int handoffRejectedCases,
        int replayBlockedCases,
        Map<String, Long> stopReasonCounts,
        Map<String, Long> terminalStateCounts
    ) {
    }

    private record AgentStage5BenchmarkCaseResult(
        String caseId,
        String description,
        boolean expectedMultiStepEnabled,
        boolean actualMultiStepEnabled,
        int expectedExecutedSteps,
        int actualExecutedSteps,
        String expectedStopReason,
        String actualStopReason,
        String expectedBudgetStopReason,
        String actualBudgetStopReason,
        String expectedTerminalState,
        String actualTerminalState,
        boolean expectedRecoverable,
        boolean actualRecoverable,
        long latencyMs,
        boolean passed,
        String note
    ) {
    }

    private record AgentStage5BenchmarkReport(
        String suiteId,
        String generatedAt,
        AgentStage5BenchmarkSummary summary,
        List<AgentStage5BenchmarkCaseResult> caseResults
    ) {
    }

    @FunctionalInterface
    private interface ScenarioExecution {
        AgentStage5BenchmarkActual run() throws Exception;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sanitizeNote(String note) {
        if (note == null || note.isBlank()) {
            return "n/a";
        }
        String sanitized = note.replace('\n', ' ').replace('\r', ' ').trim();
        return sanitized.length() > 160 ? sanitized.substring(0, 160) + "..." : sanitized;
    }

    private static final class OrchestratorHarness {
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
        private final AgentTool tool = mock(AgentTool.class);
        private final AgentOrchestrator orchestrator;

        private OrchestratorHarness() {
            when(chatClientBuilder.build()).thenReturn(chatClient);
            when(metricsService.startTurnLatency()).thenReturn(Timer.start(new SimpleMeterRegistry()));
            when(tool.riskLevel()).thenReturn(AgentToolRiskLevel.READ_ONLY);
            when(promptService.buildDecisionSystemPrompt(anyString(), anyString())).thenReturn("decision-system");
            when(promptService.buildDecisionUserPrompt(any(AgentAssembledContext.class), anyInt()))
                .thenReturn("decision-user");
            when(promptService.buildDecisionUserPrompt(any(AgentAssembledContext.class), anyInt(), anyString()))
                .thenReturn("decision-user");
            when(promptService.buildAnswerSystemPrompt()).thenReturn("answer-system");
            when(promptService.buildAnswerUserPrompt(any(AgentAssembledContext.class), anyString(), any()))
                .thenReturn("answer-user");
            when(promptService.buildHandoffSystemPrompt()).thenReturn("handoff-system");
            when(promptService.buildHandoffUserPrompt(any(AgentAssembledContext.class), anyString(), anyString(), anyString()))
                .thenReturn("handoff-user");
            when(contextAssemblyService.assemble(any(), any(), anyString())).thenAnswer(invocation -> {
                AgentSessionEntity session = invocation.getArgument(0);
                AgentMemorySnapshot memory = invocation.getArgument(1);
                String latestUserMessage = invocation.getArgument(2);
                return new AgentAssembledContext(
                    session.getSessionId(),
                    session.getGoal(),
                    latestUserMessage,
                    session.getResumeId(),
                    List.of(),
                    memory,
                    "上下文摘要",
                    new AgentContextBudget(320, 16, 304),
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
                new AgentGuardrailService(),
                approvalService,
                approvalRuntimeService
            );
        }
    }
}
