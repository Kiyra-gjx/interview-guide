package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.agent.guardrail.AgentGuardrailService;
import interview.guide.modules.agent.model.AgentChatRequest;
import interview.guide.modules.agent.model.AgentChatResponse;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentDecisionDTO;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentMessageDTO;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
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
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStage3MemoryEvalTest {

    private static final String SUITE_ID = "stage-3-memory-set";
    private static final String JSON_REPORT_NAME = "stage-3-memory-set-report.json";
    private static final String MARKDOWN_REPORT_NAME = "stage-3-memory-set-report.md";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentMemoryService memoryService = new AgentMemoryService(objectMapper);

    @Test
    @DisplayName("should run the fixed stage 3 memory quantification suite and persist reports")
    void shouldRunTheFixedStage3MemoryQuantificationSuiteAndPersistReports() throws Exception {
        Path reportDirectory = Path.of("build", "reports", "agent-eval");

        AgentStage3MemoryEvalReport report = runFixedSuite(reportDirectory);

        assertThat(report.summary().totalCases()).isEqualTo(9);
        assertThat(report.summary().passedCases()).isEqualTo(9);
        assertThat(report.summary().averageToolCallCount()).isGreaterThan(0);
        assertThat(report.summary().repeatedToolCallsBefore()).isGreaterThan(report.summary().repeatedToolCallsAfter());
        assertThat(report.summary().repeatedFactChecksBefore()).isGreaterThan(report.summary().repeatedFactChecksAfter());
        assertThat(report.summary().extraCallsAfterMemoryReady()).isZero();
        assertThat(report.caseResults()).allMatch(AgentStage3MemoryEvalCaseResult::passed);

        Path jsonReport = reportDirectory.resolve(JSON_REPORT_NAME);
        Path markdownReport = reportDirectory.resolve(MARKDOWN_REPORT_NAME);

        assertThat(Files.exists(jsonReport)).isTrue();
        assertThat(Files.exists(markdownReport)).isTrue();
        assertThat(Files.readString(markdownReport))
            .contains("repeatedToolCallsBefore")
            .contains("repeatedFactChecksBefore")
            .contains("extraCallsAfterMemoryReady");
    }

    private AgentStage3MemoryEvalReport runFixedSuite(Path reportDirectory) throws Exception {
        List<MemoryEvalScenario> scenarios = List.of(
            new MemoryEvalScenario("MEM-01", "phase_mapping", 1, this::runPhaseMappingCase),
            new MemoryEvalScenario("MEM-02", "fact_dedup_and_cap", 1, this::runFactDedupAndCapCase),
            new MemoryEvalScenario("MEM-03", "summary_and_fact_normalization", 1, this::runSummaryAndFactNormalizationCase),
            new MemoryEvalScenario("MEM-04", "explicit_next_focus", 1, this::runExplicitNextFocusCase),
            new MemoryEvalScenario("MEM-05", "legacy_fact_normalization", 1, this::runLegacyFactNormalizationCase),
            new MemoryEvalScenario("MEM-06", "preserve_short_facts", 1, this::runPreserveShortFactsCase),
            new MemoryEvalScenario("MEM-07", "follow_up_reuse_known_fact", 2, this::runFollowUpReuseKnownFactCase),
            new MemoryEvalScenario("MEM-08", "follow_up_reuse_tool_result", 2, this::runFollowUpReuseToolResultCase),
            new MemoryEvalScenario("MEM-09", "delegated_memory_writeback", 2, this::runDelegatedMemoryWritebackCase)
        );

        List<AgentStage3MemoryEvalCaseResult> caseResults = new ArrayList<>();
        for (MemoryEvalScenario scenario : scenarios) {
            long startedAt = System.nanoTime();
            MemoryEvalActual actual = scenario.execution().run();
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            caseResults.add(new AgentStage3MemoryEvalCaseResult(
                scenario.caseId(),
                scenario.scenarioType(),
                scenario.turnCount(),
                actual.toolCallCount(),
                actual.repeatedToolCallsBefore(),
                actual.repeatedToolCallsAfter(),
                actual.repeatedFactChecksBefore(),
                actual.repeatedFactChecksAfter(),
                actual.extraCallsAfterMemoryReady(),
                actual.passed(),
                latencyMs,
                actual.note()
            ));
        }

        AgentStage3MemoryEvalReport report = new AgentStage3MemoryEvalReport(
            SUITE_ID,
            LocalDateTime.now().toString(),
            buildSummary(caseResults),
            caseResults
        );
        writeReport(reportDirectory, report);
        return report;
    }

    private MemoryEvalActual runPhaseMappingCase() {
        AgentMemorySnapshot current = createMemory();
        AgentMemorySnapshot afterResume = memoryService.updateAfterTool(
            current,
            "get_resume_profile",
            toolResult("已读取简历画像", List.of("已绑定简历ID: 42"))
        );
        AgentMemorySnapshot afterHistory = memoryService.updateAfterTool(
            afterResume,
            "get_interview_history_summary",
            toolResult("已归纳历史面试记录", List.of("最近一次面试分数: 68"))
        );
        AgentMemorySnapshot afterGap = memoryService.updateAfterTool(
            afterHistory,
            "analyze_interview_gaps",
            toolResult("已提炼主要短板和练习优先级", List.of("低分维度: 数据库"))
        );
        AgentMemorySnapshot afterFollowUp = memoryService.updateAfterTool(
            afterGap,
            "suggest_follow_up_questions",
            toolResult("已整理下一轮追问", List.of("建议追问: 数据库索引取舍"))
        );

        boolean passed = "resume_context_ready".equals(afterResume.currentPhase())
            && "interview_history_ready".equals(afterHistory.currentPhase())
            && "interview_gap_ready".equals(afterGap.currentPhase())
            && "follow_up_ready".equals(afterFollowUp.currentPhase())
            && afterFollowUp.usedTools().equals(List.of(
                "get_resume_profile",
                "get_interview_history_summary",
                "analyze_interview_gaps",
                "suggest_follow_up_questions"
            ));

        return new MemoryEvalActual(4, 0, 0, 0, 0, 0, passed, "验证 interview tools 到 memory phase 的稳定映射");
    }

    private MemoryEvalActual runFactDedupAndCapCase() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of("fact-1", "fact-2", "fact-3"),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = new AgentToolResult(
            "summary",
            Map.of(),
            Map.of(),
            List.of("fact-2", " ", "fact-4", "fact-5", "fact-6", "fact-7", "fact-8", "fact-9", "fact-10")
        );

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "analyze_interview_gaps", result);
        boolean passed = updated.confirmedFacts().equals(List.of(
            "fact-1",
            "fact-2",
            "fact-3",
            "fact-4",
            "fact-5",
            "fact-6",
            "fact-7",
            "fact-8"
        )) && updated.usedTools().equals(List.of("get_resume_profile", "analyze_interview_gaps"));

        return new MemoryEvalActual(1, 0, 0, 0, 0, 0, passed, "验证 facts 去重、顺序稳定和总量上限");
    }

    private MemoryEvalActual runSummaryAndFactNormalizationCase() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of(),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = new AgentToolResult(
            "s".repeat(220),
            Map.of(),
            Map.of(),
            List.of(
                "fact-0-" + "x".repeat(200),
                "fact-1-" + "x".repeat(200),
                "fact-2-" + "x".repeat(200),
                "fact-3-" + "x".repeat(200),
                "fact-4-" + "x".repeat(200),
                "fact-5-" + "x".repeat(200),
                "fact-6-" + "x".repeat(200),
                "fact-7-" + "x".repeat(200)
            )
        );

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "search_knowledge_base", result);
        boolean passed = updated.nextFocus().length() == 203
            && updated.nextFocus().endsWith("...")
            && updated.confirmedFacts().size() == 6
            && updated.confirmedFacts().stream().allMatch(fact -> fact.length() <= 183);

        return new MemoryEvalActual(1, 0, 0, 0, 0, 0, passed, "验证 summary 和 facts 写回前会被统一归一化");
    }

    private MemoryEvalActual runExplicitNextFocusCase() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of("fact-1"),
            List.of("get_resume_profile"),
            "旧的 focus"
        );
        AgentToolResult result = new AgentToolResult(
            "这是委派总结",
            Map.of("nextFocus", "围绕一个项目亮点继续整合最终回答"),
            Map.of(),
            List.of("fact-2")
        );

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "subagent_handoff", result);
        boolean passed = "delegated_context_ready".equals(updated.currentPhase())
            && updated.nextFocus().equals("围绕一个项目亮点继续整合最终回答");

        return new MemoryEvalActual(1, 0, 0, 0, 0, 0, passed, "验证结构化 nextFocus 高于默认 summary");
    }

    private MemoryEvalActual runLegacyFactNormalizationCase() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of("legacy-" + "x".repeat(200)),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = toolResult("summary", List.of("fact-2"));

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "analyze_interview_gaps", result);
        boolean passed = updated.confirmedFacts().equals(List.of("legacy-" + "x".repeat(173) + "...", "fact-2"));

        return new MemoryEvalActual(1, 0, 0, 0, 0, 0, passed, "验证 legacy facts 在合并时会被统一清洗");
    }

    private MemoryEvalActual runPreserveShortFactsCase() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of("fact-1", "fact-2", "fact-3", "fact-4", "fact-5", "fact-6", "fact-7", "fact-8"),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = toolResult("summary", List.of("fact-9"));

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "search_knowledge_base", result);
        boolean passed = updated.confirmedFacts().equals(List.of(
            "fact-1",
            "fact-2",
            "fact-3",
            "fact-4",
            "fact-5",
            "fact-6",
            "fact-7",
            "fact-8"
        ));

        return new MemoryEvalActual(1, 0, 0, 0, 0, 0, passed, "验证已有短 facts 不会被新结果错误挤掉");
    }

    private MemoryEvalActual runFollowUpReuseKnownFactCase() {
        AgentMemorySnapshot memoryReady = memoryService.updateAfterTool(
            createMemory(),
            "get_resume_profile",
            toolResult("已读取简历画像", List.of("已绑定简历ID: 42", "最该优先补数据库表达"))
        );
        AgentToolResult repeatedToolResult = toolResult("数据库表达仍是最该先补的短板。", List.of("最该优先补数据库表达"));
        AgentMemorySnapshot controlMemory = memoryService.updateAfterTool(memoryReady, "get_resume_profile", repeatedToolResult);

        SingleTurnOutcome control = runToolFollowUpTurn(
            "session-memory-fact-control",
            "turn-memory-fact-control",
            memoryReady,
            "继续追问我最该先补哪一块",
            List.of(),
            "get_resume_profile",
            "重新确认简历里的数据库短板",
            repeatedToolResult,
            controlMemory,
            "数据库表达仍是最该先补的短板。"
        );
        SingleTurnOutcome actual = runDirectFollowUpTurn(
            "session-memory-fact-actual",
            "turn-memory-fact-actual",
            memoryReady,
            "继续追问我最该先补哪一块",
            List.of(),
            "memory 已足够，直接回答",
            "数据库表达仍是最该先补的一块，下一轮优先补项目取舍和索引优化表达。"
        );

        boolean passed = "resume_context_ready".equals(memoryReady.currentPhase())
            && memoryReady.confirmedFacts().contains("最该优先补数据库表达")
            && control.toolExecutions() == 1
            && actual.toolExecutions() == 0
            && actual.reply().contains("数据库表达");

        return new MemoryEvalActual(1, 1, 0, 1, 0, 0, passed, "follow-up 直接复用已确认事实，避免再次读取同一简历事实");
    }

    private MemoryEvalActual runFollowUpReuseToolResultCase() {
        AgentMemorySnapshot memoryReady = memoryService.updateAfterTool(
            createMemory(),
            "search_knowledge_base",
            new AgentToolResult(
                "已归纳知识库结论：优先补数据库索引与慢查询定位。",
                Map.of("nextFocus", "围绕数据库索引与慢查询定位准备下一轮回答"),
                Map.of(),
                List.of("知识库结论: 优先补数据库索引与慢查询定位")
            )
        );
        AgentToolResult repeatedToolResult = new AgentToolResult(
            "知识库里最值得先讲的仍然是数据库索引与慢查询定位。",
            Map.of("nextFocus", "围绕数据库索引与慢查询定位准备下一轮回答"),
            Map.of(),
            List.of("知识库结论: 优先补数据库索引与慢查询定位")
        );
        AgentMemorySnapshot controlMemory = memoryService.updateAfterTool(memoryReady, "search_knowledge_base", repeatedToolResult);

        SingleTurnOutcome control = runToolFollowUpTurn(
            "session-memory-tool-control",
            "turn-memory-tool-control",
            memoryReady,
            "继续根据知识库告诉我下一轮最该优先补什么",
            List.of(7L, 8L),
            "search_knowledge_base",
            "重新读取知识库确认优先级",
            repeatedToolResult,
            controlMemory,
            "知识库里最值得先讲的仍然是数据库索引与慢查询定位。"
        );
        SingleTurnOutcome actual = runDirectFollowUpTurn(
            "session-memory-tool-actual",
            "turn-memory-tool-actual",
            memoryReady,
            "继续根据知识库告诉我下一轮最该优先补什么",
            List.of(7L, 8L),
            "memory 已足够，直接收敛下一步",
            "下一轮继续围绕数据库索引与慢查询定位准备，把问题收敛到一个排障案例即可。"
        );

        boolean passed = "knowledge_context_ready".equals(memoryReady.currentPhase())
            && memoryReady.nextFocus().contains("数据库索引")
            && control.toolExecutions() == 1
            && actual.toolExecutions() == 0
            && actual.reply().contains("数据库索引");

        return new MemoryEvalActual(1, 1, 0, 1, 0, 0, passed, "follow-up 直接复用上一步工具结论，避免重复读取知识库");
    }

    private MemoryEvalActual runDelegatedMemoryWritebackCase() {
        AgentMemorySnapshot delegatedMemory = memoryService.updateAfterTool(
            createMemory(),
            "subagent_handoff",
            new AgentToolResult(
                "先聚焦一个能体现 Java 和 Spring Boot 深度的项目亮点",
                Map.of("nextFocus", "围绕一个后端项目亮点给最终建议"),
                Map.of("readOnly", true),
                List.of("最值得先讲的是一个后端项目亮点")
            )
        );
        AgentToolResult repeatedToolResult = toolResult("我又重新读取了一次简历亮点。", List.of("最值得先讲的是一个后端项目亮点"));
        AgentMemorySnapshot controlMemory = memoryService.updateAfterTool(delegatedMemory, "get_resume_profile", repeatedToolResult);

        SingleTurnOutcome control = runToolFollowUpTurn(
            "session-delegated-memory-control",
            "turn-delegated-memory-control",
            delegatedMemory,
            "继续给我最后建议",
            List.of(),
            "get_resume_profile",
            "重新读取简历亮点再给建议",
            repeatedToolResult,
            controlMemory,
            "我又重新读取了一次简历亮点。"
        );
        SingleTurnOutcome actual = runDirectFollowUpTurn(
            "session-delegated-memory-actual",
            "turn-delegated-memory-actual",
            delegatedMemory,
            "继续给我最后建议",
            List.of(),
            "委派结果已写回 memory，直接给建议",
            "先突出一个能体现 Java 与 Spring Boot 深度的项目亮点，再补一轮追问结构。"
        );

        boolean passed = "delegated_context_ready".equals(delegatedMemory.currentPhase())
            && delegatedMemory.confirmedFacts().contains("最值得先讲的是一个后端项目亮点")
            && delegatedMemory.nextFocus().contains("后端项目亮点")
            && control.toolExecutions() == 1
            && actual.toolExecutions() == 0
            && actual.reply().contains("项目亮点");

        return new MemoryEvalActual(1, 1, 0, 0, 0, 0, passed, "委派产出的 summary / facts / nextFocus 写回后，follow-up 不再额外读工具");
    }

    private SingleTurnOutcome runToolFollowUpTurn(
        String sessionId,
        String turnId,
        AgentMemorySnapshot memory,
        String requestMessage,
        List<Long> knowledgeBaseIds,
        String selectedTool,
        String decisionSummary,
        AgentToolResult toolResult,
        AgentMemorySnapshot updatedMemory,
        String reply
    ) {
        OrchestratorHarness harness = new OrchestratorHarness();
        AgentChatRequest request = new AgentChatRequest(requestMessage);
        AgentSessionEntity session = createSession(sessionId, "准备 Java 面试", 42L);
        AgentAssembledContext assembledContext = assembledContext(session, memory, requestMessage, knowledgeBaseIds);
        AgentStepTraceEntity stepTrace = new AgentStepTraceEntity();
        AgentTurnEntity completedTurn = createCompletedTurn(turnId, session, AgentCompletionMode.SUCCESS);
        List<AgentTraceDTO> trace = List.of(createTrace(selectedTool, AgentExecutionState.COMPLETED));
        List<AgentMessageDTO> messagesDelta = List.of(
            createMessage("user", requestMessage, 1),
            createMessage("assistant", reply, 2)
        );
        AtomicInteger toolExecutions = new AtomicInteger();

        when(harness.sessionService.startTurn(sessionId, requestMessage))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(harness.memoryService.readMemory(session)).thenReturn(memory);
        when(harness.traceService.estimateNextStepIndex(sessionId)).thenReturn(1);
        when(harness.toolRegistry.describeTools()).thenReturn("- " + selectedTool);
        when(harness.contextAssemblyService.assemble(session, memory, requestMessage)).thenReturn(assembledContext);
        when(harness.promptService.buildDecisionUserPrompt(eq(assembledContext), eq(1))).thenReturn("decision-user");
        when(harness.structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(new AgentDecisionDTO(true, selectedTool, Map.of(), decisionSummary, null));
        when(harness.toolRegistry.findTool(selectedTool)).thenReturn(Optional.of(harness.tool));
        when(harness.tool.name()).thenReturn(selectedTool);
        when(harness.tool.requiredInputs()).thenReturn(requiredInputsFor(selectedTool));
        when(harness.traceService.startToolStep(
            eq(turnId),
            eq(decisionSummary),
            eq(selectedTool),
            anyMap(),
            eq(memory)
        )).thenReturn(stepTrace);
        when(harness.tool.execute(anyMap(), any())).thenAnswer(invocation -> {
            toolExecutions.incrementAndGet();
            return toolResult;
        });
        when(harness.memoryService.updateAfterTool(memory, selectedTool, toolResult)).thenReturn(updatedMemory);
        when(harness.sessionService.completeTurn(
            eq(turnId),
            eq(reply),
            eq(updatedMemory),
            eq(AgentCompletionMode.SUCCESS)
        )).thenReturn(completedTurn);
        when(harness.traceService.getTurnTrace(turnId)).thenReturn(trace);
        when(harness.sessionService.getTurnMessages(turnId)).thenReturn(messagesDelta);

        AgentChatResponse response = harness.orchestrator.chat(sessionId, request);

        assertThat(response.reply()).isEqualTo(reply);
        assertThat(response.memory()).isEqualTo(updatedMemory);
        assertThat(response.completionMode()).isEqualTo(AgentCompletionMode.SUCCESS);
        verify(harness.tool).execute(anyMap(), any());
        return new SingleTurnOutcome(toolExecutions.get(), response.reply());
    }

    private SingleTurnOutcome runDirectFollowUpTurn(
        String sessionId,
        String turnId,
        AgentMemorySnapshot memory,
        String requestMessage,
        List<Long> knowledgeBaseIds,
        String decisionSummary,
        String reply
    ) {
        OrchestratorHarness harness = new OrchestratorHarness();
        AgentChatRequest request = new AgentChatRequest(requestMessage);
        AgentSessionEntity session = createSession(sessionId, "准备 Java 面试", 42L);
        AgentAssembledContext assembledContext = assembledContext(session, memory, requestMessage, knowledgeBaseIds);
        AgentTurnEntity completedTurn = createCompletedTurn(turnId, session, AgentCompletionMode.SUCCESS);
        List<AgentTraceDTO> trace = List.of(createTrace("direct_answer", AgentExecutionState.COMPLETED));
        List<AgentMessageDTO> messagesDelta = List.of(
            createMessage("user", requestMessage, 1),
            createMessage("assistant", reply, 2)
        );

        when(harness.sessionService.startTurn(sessionId, requestMessage))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(harness.memoryService.readMemory(session)).thenReturn(memory);
        when(harness.traceService.estimateNextStepIndex(sessionId)).thenReturn(1);
        when(harness.toolRegistry.describeTools()).thenReturn("- get_resume_profile");
        when(harness.contextAssemblyService.assemble(session, memory, requestMessage)).thenReturn(assembledContext);
        when(harness.promptService.buildDecisionUserPrompt(eq(assembledContext), eq(1))).thenReturn("decision-user");
        when(harness.structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(new AgentDecisionDTO(false, null, Map.of(), decisionSummary, reply));
        when(harness.sessionService.completeTurn(
            eq(turnId),
            eq(reply),
            eq(memory),
            eq(AgentCompletionMode.SUCCESS)
        )).thenReturn(completedTurn);
        when(harness.traceService.getTurnTrace(turnId)).thenReturn(trace);
        when(harness.sessionService.getTurnMessages(turnId)).thenReturn(messagesDelta);

        AgentChatResponse response = harness.orchestrator.chat(sessionId, request);

        assertThat(response.reply()).isEqualTo(reply);
        assertThat(response.memory()).isEqualTo(memory);
        assertThat(response.completionMode()).isEqualTo(AgentCompletionMode.SUCCESS);
        return new SingleTurnOutcome(0, response.reply());
    }

    private AgentStage3MemoryEvalSummary buildSummary(List<AgentStage3MemoryEvalCaseResult> caseResults) {
        int totalCases = caseResults.size();
        int passedCases = (int) caseResults.stream().filter(AgentStage3MemoryEvalCaseResult::passed).count();
        double averageToolCallCount = average(caseResults.stream().mapToInt(AgentStage3MemoryEvalCaseResult::toolCallCount).toArray());
        int repeatedToolCallsBefore = caseResults.stream().mapToInt(AgentStage3MemoryEvalCaseResult::repeatedToolCallsBefore).sum();
        int repeatedToolCallsAfter = caseResults.stream().mapToInt(AgentStage3MemoryEvalCaseResult::repeatedToolCallsAfter).sum();
        int repeatedFactChecksBefore = caseResults.stream().mapToInt(AgentStage3MemoryEvalCaseResult::repeatedFactChecksBefore).sum();
        int repeatedFactChecksAfter = caseResults.stream().mapToInt(AgentStage3MemoryEvalCaseResult::repeatedFactChecksAfter).sum();
        int extraCallsAfterMemoryReady = caseResults.stream().mapToInt(AgentStage3MemoryEvalCaseResult::extraCallsAfterMemoryReady).sum();
        return new AgentStage3MemoryEvalSummary(
            totalCases,
            passedCases,
            averageToolCallCount,
            repeatedToolCallsBefore,
            repeatedToolCallsAfter,
            repeatedFactChecksBefore,
            repeatedFactChecksAfter,
            extraCallsAfterMemoryReady
        );
    }

    private void writeReport(Path reportDirectory, AgentStage3MemoryEvalReport report) throws Exception {
        Files.createDirectories(reportDirectory);
        Files.writeString(
            reportDirectory.resolve(JSON_REPORT_NAME),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        );
        Files.writeString(reportDirectory.resolve(MARKDOWN_REPORT_NAME), toMarkdown(report));
    }

    private String toMarkdown(AgentStage3MemoryEvalReport report) {
        AgentStage3MemoryEvalSummary summary = report.summary();
        StringBuilder builder = new StringBuilder();
        builder.append("# Stage 3 Memory Set Report\n\n");
        builder.append("- suite: ").append(report.suiteId()).append('\n');
        builder.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        builder.append("- totalCases: ").append(summary.totalCases()).append('\n');
        builder.append("- passedCases: ").append(summary.passedCases()).append('\n');
        builder.append("- averageToolCallCount: ").append(summary.averageToolCallCount()).append('\n');
        builder.append("- repeatedToolCallsBefore: ").append(summary.repeatedToolCallsBefore()).append('\n');
        builder.append("- repeatedToolCallsAfter: ").append(summary.repeatedToolCallsAfter()).append('\n');
        builder.append("- repeatedFactChecksBefore: ").append(summary.repeatedFactChecksBefore()).append('\n');
        builder.append("- repeatedFactChecksAfter: ").append(summary.repeatedFactChecksAfter()).append('\n');
        builder.append("- extraCallsAfterMemoryReady: ").append(summary.extraCallsAfterMemoryReady()).append("\n\n");
        builder.append("| Case | Scenario | TurnCount | ToolCalls | RepeatedTools | RepeatedFacts | ExtraAfterReady | Passed | Note |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (AgentStage3MemoryEvalCaseResult result : report.caseResults()) {
            builder.append("| ")
                .append(result.caseId())
                .append(" | ")
                .append(result.scenarioType())
                .append(" | ")
                .append(result.turnCount())
                .append(" | ")
                .append(result.toolCallCount())
                .append(" | ")
                .append(result.repeatedToolCallsBefore())
                .append(" -> ")
                .append(result.repeatedToolCallsAfter())
                .append(" | ")
                .append(result.repeatedFactChecksBefore())
                .append(" -> ")
                .append(result.repeatedFactChecksAfter())
                .append(" | ")
                .append(result.extraCallsAfterMemoryReady())
                .append(" | ")
                .append(result.passed())
                .append(" | ")
                .append(result.note())
                .append(" |\n");
        }
        return builder.toString();
    }

    private static List<String> requiredInputsFor(String selectedTool) {
        return switch (selectedTool) {
            case "get_resume_profile" -> List.of("resumeId");
            case "search_knowledge_base" -> List.of("knowledgeBaseIds", "question");
            default -> List.of();
        };
    }

    private static AgentToolResult toolResult(String summary, List<String> facts) {
        return new AgentToolResult(summary, Map.of(), Map.of(), facts);
    }

    private static AgentMemorySnapshot createMemory() {
        return new AgentMemorySnapshot(
            "准备 Java 面试",
            "goal_received",
            List.of("fact-1"),
            List.of(),
            "need more context"
        );
    }

    private static AgentSessionEntity createSession(String sessionId, String goal, Long resumeId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setGoal(goal);
        session.setResumeId(resumeId);
        return session;
    }

    private static AgentTurnEntity createCompletedTurn(String turnId, AgentSessionEntity session, AgentCompletionMode completionMode) {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setSession(session);
        turn.setStatus(AgentTurnStatus.COMPLETED);
        turn.setCompletionMode(completionMode);
        return turn;
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
            knowledgeBaseIds == null ? List.of() : knowledgeBaseIds,
            memory,
            "上下文摘要",
            new AgentContextBudget(420, 180, 240),
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

    private static AgentTraceDTO createTrace(String selectedTool, AgentExecutionState status) {
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

    private static AgentMessageDTO createMessage(String role, String content, int order) {
        return new AgentMessageDTO(role, content, order, LocalDateTime.now());
    }

    private static double average(int[] values) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (int value : values) {
            sum += value;
        }
        return Math.round((sum / values.length) * 100.0) / 100.0;
    }

    private record MemoryEvalScenario(
        String caseId,
        String scenarioType,
        int turnCount,
        ScenarioExecution execution
    ) {
    }

    private record MemoryEvalActual(
        int toolCallCount,
        int repeatedToolCallsBefore,
        int repeatedToolCallsAfter,
        int repeatedFactChecksBefore,
        int repeatedFactChecksAfter,
        int extraCallsAfterMemoryReady,
        boolean passed,
        String note
    ) {
    }

    private record AgentStage3MemoryEvalSummary(
        int totalCases,
        int passedCases,
        double averageToolCallCount,
        int repeatedToolCallsBefore,
        int repeatedToolCallsAfter,
        int repeatedFactChecksBefore,
        int repeatedFactChecksAfter,
        int extraCallsAfterMemoryReady
    ) {
    }

    private record AgentStage3MemoryEvalCaseResult(
        String caseId,
        String scenarioType,
        int turnCount,
        int toolCallCount,
        int repeatedToolCallsBefore,
        int repeatedToolCallsAfter,
        int repeatedFactChecksBefore,
        int repeatedFactChecksAfter,
        int extraCallsAfterMemoryReady,
        boolean passed,
        long latencyMs,
        String note
    ) {
    }

    private record AgentStage3MemoryEvalReport(
        String suiteId,
        String generatedAt,
        AgentStage3MemoryEvalSummary summary,
        List<AgentStage3MemoryEvalCaseResult> caseResults
    ) {
    }

    private record SingleTurnOutcome(int toolExecutions, String reply) {
    }

    @FunctionalInterface
    private interface ScenarioExecution {
        MemoryEvalActual run() throws Exception;
    }

    private static final class OrchestratorHarness {
        private final ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        private final ChatClient chatClient = mock(ChatClient.class);
        private final StructuredOutputInvoker structuredOutputInvoker = mock(StructuredOutputInvoker.class);
        private final LlmProviderRegistry llmProviderRegistry = mock(LlmProviderRegistry.class);
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
            when(promptService.buildDecisionUserPrompt(any(AgentAssembledContext.class), anyInt())).thenReturn("decision-user");
            when(promptService.buildDecisionUserPrompt(any(AgentAssembledContext.class), anyInt(), anyString())).thenReturn("decision-user");
            when(promptService.buildAnswerSystemPrompt()).thenReturn("answer-system");
            when(promptService.buildAnswerUserPrompt(any(AgentAssembledContext.class), anyString(), any())).thenReturn("answer-user");
            when(promptService.buildHandoffSystemPrompt()).thenReturn("handoff-system");
            when(promptService.buildHandoffUserPrompt(any(AgentAssembledContext.class), anyString(), anyString(), anyString()))
                .thenReturn("handoff-user");
            orchestrator = new AgentOrchestrator(
                chatClientBuilder,
                llmProviderRegistry,
                structuredOutputInvoker,
                toolRegistry,
                sessionService,
                memoryService,
                traceService,
                metricsService,
                promptService,
                contextAssemblyService,
                new AgentGuardrailService(testSanitizer()),
                approvalService,
                approvalRuntimeService
            );
        }
    }

    private static PromptSanitizer testSanitizer() {
        PromptSanitizer s = new PromptSanitizer();
        ReflectionTestUtils.setField(s, "enabled", true);
        return s;
    }
}
