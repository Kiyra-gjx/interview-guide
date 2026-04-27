package interview.guide.modules.agent.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.guardrail.AgentGuardrailService;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentApprovalEntity;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import interview.guide.modules.agent.model.AgentChatRequest;
import interview.guide.modules.agent.model.AgentChatResponse;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentDecisionDTO;
import interview.guide.modules.agent.model.AgentExecutionSummaryDTO;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentLoopStopReason;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentRuntimeConfig;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.AgentTool;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import interview.guide.modules.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Timer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行时编排器。
 * 负责串联一轮 chat、工具执行、审批挂起、审批恢复、trace 写入和 turn 收口。
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private static final String DIRECT_ANSWER_TOOL = "direct_answer";
    private static final String DECISION_FALLBACK_TOOL = "decision_fallback";
    private static final String INVALID_TOOL_NAME = "invalid_tool";
    private static final String BOUNDED_LOOP_TOOL = "bounded_loop";
    private static final String SESSION_OR_RESUME_INPUT = "sessionId/resumeId";
    private static final int DEFAULT_MULTI_STEP_MAX_STEPS = 3;
    private static final long DEFAULT_MULTI_STEP_MAX_DURATION_MILLIS = 15_000L;
    private static final int DEFAULT_MULTI_STEP_MAX_ESTIMATED_MODEL_TOKENS = 4_000;
    private static final int MAX_ALLOWED_MULTI_STEP_STEPS = 5;
    private static final long MAX_ALLOWED_MULTI_STEP_DURATION_MILLIS = 30_000L;
    private static final int MAX_ALLOWED_MULTI_STEP_ESTIMATED_MODEL_TOKENS = 12_000;

    private final ChatClient chatClient;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final ToolRegistry toolRegistry;
    private final AgentSessionService sessionService;
    private final AgentMemoryService memoryService;
    private final AgentTraceService traceService;
    private final AgentMetricsService metricsService;
    private final AgentPromptService promptService;
    private final AgentContextAssemblyService contextAssemblyService;
    private final AgentGuardrailService guardrailService;
    private final AgentApprovalService approvalService;
    private final AgentApprovalRuntimeService approvalRuntimeService;
    private final BeanOutputConverter<AgentDecisionDTO> decisionOutputConverter;

    public AgentOrchestrator(
        ChatClient.Builder chatClientBuilder,
        StructuredOutputInvoker structuredOutputInvoker,
        ToolRegistry toolRegistry,
        AgentSessionService sessionService,
        AgentMemoryService memoryService,
        AgentTraceService traceService,
        AgentMetricsService metricsService,
        AgentPromptService promptService,
        AgentContextAssemblyService contextAssemblyService,
        AgentGuardrailService guardrailService,
        AgentApprovalService approvalService,
        AgentApprovalRuntimeService approvalRuntimeService
    ) {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.toolRegistry = toolRegistry;
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.traceService = traceService;
        this.metricsService = metricsService;
        this.promptService = promptService;
        this.contextAssemblyService = contextAssemblyService;
        this.guardrailService = guardrailService;
        this.approvalService = approvalService;
        this.approvalRuntimeService = approvalRuntimeService;
        this.decisionOutputConverter = new BeanOutputConverter<>(AgentDecisionDTO.class);
    }

    /**
     * 执行一轮 Agent 对话。
     * 远程模型调用和工具调用都放在事务外，只在 turn 的开始和结束阶段做持久化。
     */
    public AgentChatResponse chat(String sessionId, AgentChatRequest request) {
        String turnId = null;
        boolean turnTerminalPersisted = false;
        Timer.Sample latencySample = metricsService.startTurnLatency();
        AgentCompletionMode completionMode = null;
        AgentChatResponse response;
        AgentExecutionSummaryDTO executionSummary = null;

        try {
            expirePendingApprovals(sessionId);
            // 1. 创建 turn，并先把用户消息落库，确保本轮执行有唯一归属。
            AgentSessionService.StartedTurn startedTurn = sessionService.startTurn(sessionId, request.message());
            turnId = startedTurn.turnId();
            AgentSessionEntity session = startedTurn.session();
            metricsService.recordTurnStarted();
            metricsService.recordTurnReclaimed(startedTurn.reclaimedExpiredTurnCount());

            // 2. 先做输入 Guardrail，命中后直接返回安全回复，不再进入模型决策链路。
            AgentMemorySnapshot memory = memoryService.readMemory(session);
            AgentGuardrailService.InputGuardrailDecision inputGuardrail = guardrailService.evaluateInput(request.message());
            ResolvedRunConfig runConfig = resolveRunConfig(request.runtimeConfig());
            if (inputGuardrail.blocked()) {
                String reply = buildInputGuardrailReply(inputGuardrail.result());
                traceService.recordInputGuardrailRejection(
                    turnId,
                    "输入触发安全拦截，已拒绝继续执行",
                    reply,
                    memory,
                    memory,
                    inputGuardrail.guardrailResults()
                );
                AgentTurnEntity completedTurn = sessionService.completeTurn(
                    turnId,
                    reply,
                    memory,
                    AgentCompletionMode.DEGRADED
                );
                completionMode = AgentCompletionMode.DEGRADED;
                executionSummary = buildInputGuardrailExecutionSummary(runConfig);
                metricsService.recordTurnCompleted(completionMode);
                metricsService.recordExecutionSummary(executionSummary);
                turnTerminalPersisted = true;
                response = buildChatResponse(completedTurn, null, reply, memory, executionSummary);
                metricsService.stopTurnLatency(latencySample, "degraded");
                return response;
            }

            // 3. 输入安全后，根据运行时配置选择单步或受控多步执行。
            TurnExecution execution = runConfig.multiStepEnabled()
                ? executeBoundedLoop(turnId, session, memory, inputGuardrail.normalizedMessage(), runConfig)
                : executeSingleStep(turnId, session, memory, inputGuardrail.normalizedMessage(), runConfig);
            AgentTurnEntity completedTurn = execution.persistedTurn() != null
                ? execution.persistedTurn()
                : sessionService.completeTurn(
                    turnId,
                    execution.reply(),
                    execution.memorySnapshot(),
                    execution.completionMode()
                );
            completionMode = execution.completionMode();
            executionSummary = execution.executionSummary();
            metricsService.recordTurnCompleted(completionMode);
            metricsService.recordExecutionSummary(executionSummary);
            turnTerminalPersisted = true;
            response = buildChatResponse(
                completedTurn,
                execution.approval(),
                execution.reply(),
                execution.memorySnapshot(),
                executionSummary
            );
            metricsService.stopTurnLatency(latencySample, resolveLatencyOutcome(completionMode));
        } catch (Exception e) {
            // 5. 只有 turn 已创建时才补失败终态；终态保护由 sessionService 负责。
            if (turnId != null && !turnTerminalPersisted) {
                sessionService.failTurn(turnId, e);
                metricsService.recordTurnFailed();
                metricsService.stopTurnLatency(latencySample, "failed");
            } else if (turnTerminalPersisted) {
                metricsService.stopTurnLatency(latencySample, "response_error");
            }
            throw e;
        }

        return response;
    }

    /**
     * 拒绝一条待审批动作，并把对应 turn 收敛到降级终态。
     */
    public AgentChatResponse rejectApproval(String approvalId) {
        ApprovalTransition transition = approvalService.withLockedApproval(approvalId, approval -> {
            if (approval.getStatus() != AgentApprovalStatus.PENDING) {
                return ApprovalTransition.snapshot(approvalId, approval.getTurn());
            }
            if (approvalService.isExpired(approval, LocalDateTime.now())) {
                return finalizeExpiredApproval(approval);
            }

            AgentApprovalDTO rejectedApproval = approvalService.markRejected(approval);
            AgentMemorySnapshot memory = memoryService.readMemory(approval.getSession());
            String reply = buildApprovalRejectedReply(approval.getSelectedTool());
            traceService.markToolStepApprovalRejected(approval.getTrace(), rejectedApproval, reply, memory);
            AgentTurnEntity completedTurn = sessionService.completeTurn(
                approval.getTurn().getTurnId(),
                reply,
                memory,
                AgentCompletionMode.DEGRADED
            );
            return ApprovalTransition.finalized(completedTurn, rejectedApproval, reply, memory);
        });
        return resolveApprovalTransition(transition);
    }

    /**
     * 批准一条待审批动作，并根据当前恢复语义决定后续处理策略。
     * 这里不会盲目重放工具，而是先判断该审批对应的 turn 和 trace 是否还能安全恢复。
     */
    public AgentChatResponse approveApproval(String approvalId) {
        ApprovalTransition transition = approvalService.withLockedApproval(approvalId, approval -> {
            LocalDateTime now = LocalDateTime.now();

            // 1. 如果审批还未决定，但已经超时，则直接按过期收口，不再允许批准。
            if (approval.getStatus() == AgentApprovalStatus.PENDING && approvalService.isExpired(approval, now)) {
                return finalizeExpiredApproval(approval);
            }

            // 2. 第一次批准时，先把审批状态推进到 APPROVED，再尝试抢占对应 turn 的恢复执行权。
            if (approval.getStatus() == AgentApprovalStatus.PENDING) {
                return claimApprovedRecovery(approval, approvalService.markApproved(approval));
            }

            // 3. 如果审批之前已经批准过，则按“重复点击批准”处理。
            //    这里不重复写状态，只尝试判断当前应该返回快照、恢复结果，还是继续执行。
            if (approval.getStatus() == AgentApprovalStatus.APPROVED) {
                return claimApprovedRecovery(approval, approvalService.toDTO(approval));
            }

            // 4. REJECTED / EXPIRED 等终态不再进入恢复执行，只返回当前持久化快照。
            return ApprovalTransition.snapshot(approvalId, approval.getTurn());
        });

        // 5. 没有拿到执行 claim 时，说明这次请求不应该继续执行工具。
        //    可能的情况包括：
        //    - 审批已在本次调用内直接终结，例如 expire
        //    - 审批已经是终态，只能返回快照
        //    - turn 执行权没有被当前请求抢到，只能返回当前状态快照
        if (transition.claim() == null) {
            return resolveApprovalTransition(transition);
        }

        // 6. 只有拿到 claim，才说明当前请求拿到了继续推进该审批的资格。
        ApprovalExecutionClaim claim = transition.claim();
        AgentApprovalEntity approval = claim.approval();
        AgentSessionEntity session = approval.getSession();

        // 7. 三种恢复模式分别对应三种不同策略：
        //    - FINALIZE_FROM_TRACE：工具结果已经落在 trace 里，只需要恢复结果，不再执行工具
        //    - BLOCK_REPLAY：之前可能已经开始执行工具，但状态不明确，为避免重复副作用，禁止自动重放
        //    - EXECUTE_TOOL：工具尚未真正开始执行，现在可以安全按冻结输入执行一次
        if (claim.mode() == ApprovedExecutionMode.FINALIZE_FROM_TRACE) {
            return finalizeApprovedTraceRecovery(claim, session);
        }

        AgentMemorySnapshot memory = readApprovalMemory(session);
        if (claim.mode() == ApprovedExecutionMode.BLOCK_REPLAY) {
            return finalizeApprovedFailure(
                claim,
                session,
                memory,
                new IllegalStateException("approved_tool_execution_replay_blocked"),
                buildApprovedReplayBlockedReply(approval.getSelectedTool()),
                "approved_tool_execution_replay_blocked",
                "审批通过后执行状态已不明确，为避免重复副作用，本次不再自动重放",
                false
            );
        }

        String toolName = approval.getSelectedTool();

        AgentTool tool;
        Map<String, Object> toolInput;
        AgentAssembledContext frozenAssembledContext;
        try {
            // 8. 恢复执行前，必须重新解析工具实现，并读取审批冻结下来的输入。
            //    同时把原 trace 从 WAITING_APPROVAL 推进到 RUNNING，表示工具真正开始执行。
            tool = toolRegistry.getRequiredTool(toolName);
            toolInput = approvalService.readToolInput(approval);
            frozenAssembledContext = approvalService.readAssembledContext(approval);
            traceService.markApprovedToolExecutionStarted(approval.getTrace(), claim.approvedApproval());
        } catch (Exception e) {
            return finalizeApprovedFailure(
                claim,
                session,
                memory,
                e,
                buildToolFailureReply(session, toolName),
                "approved_tool_resume_failure",
                "Approval recovery failed before the tool could continue",
                false
            );
        }

        try {
            // 9. 真正执行工具时，优先复用审批冻结时的统一上下文快照。
            //    只有旧审批没有该快照时，才回退到重新装配，兼容历史数据。
            AgentAssembledContext assembledContext = frozenAssembledContext;
            if (assembledContext == null) {
                assembledContext = contextAssemblyService.assemble(
                    session,
                    memory,
                    approval.getLatestUserMessage()
                );
            }
            AgentToolResult result = tool.execute(
                toolInput,
                buildToolContext(assembledContext)
            );
            metricsService.recordToolExecution(tool.name(), true);

            try {
                // 10. 工具执行成功后，继续完成 memory 更新、答案生成、输出 guardrail 和 turn 收口。
                AgentMemorySnapshot updatedMemory = memoryService.updateAfterTool(memory, tool.name(), result);
                GeneratedAnswer generatedAnswer = buildFinalAnswer(
                    session,
                    approval.getLatestUserMessage(),
                    updatedMemory,
                    tool.name(),
                    result
                );
                AgentGuardrailService.OutputGuardrailDecision outputGuardrail = guardrailService.evaluateOutput(
                    generatedAnswer.reply(),
                    buildFallbackReply(session)
                );
                AgentCompletionMode completionMode = outputGuardrail.degraded()
                    ? AgentCompletionMode.DEGRADED
                    : AgentCompletionMode.SUCCESS;
                traceService.completeApprovedToolStep(
                    approval.getTrace(),
                    claim.approvedApproval(),
                    result,
                    updatedMemory,
                    outputGuardrail.reply(),
                    outputGuardrail.guardrailResults(),
                    completionMode
                );
                AgentTurnEntity completedTurn = sessionService.completeTurn(
                    approval.getTurn().getTurnId(),
                    outputGuardrail.reply(),
                    updatedMemory,
                    completionMode
                );
                return buildChatResponse(completedTurn, claim.approvedApproval(), outputGuardrail.reply(), updatedMemory, null);
            } catch (Exception e) {
                return finalizeApprovedFailure(
                    claim,
                    session,
                    memory,
                    e,
                    buildToolPostProcessingFailureReply(session, tool.name()),
                    "approved_tool_post_processing_failure",
                    "审批通过后工具后处理失败，已回退为直接回复",
                    false
                );
            }
        } catch (Exception e) {
            return finalizeApprovedFailure(
                claim,
                session,
                memory,
                e,
                buildToolFailureReply(session, tool.name()),
                "approved_tool_execution_failure",
                "审批通过后工具执行失败，已回退为直接回复",
                true
            );
        }
    }

    /**
     * 保持现有单步链路不变，只是补一层统一的预算与执行摘要表达。
     */
    private TurnExecution executeSingleStep(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        ResolvedRunConfig runConfig
    ) {
        LoopBudgetTracker budgetTracker = LoopBudgetTracker.start(runConfig);
        budgetTracker.beginStep();
        int stepIndexHint = traceService.estimateNextStepIndex(session.getSessionId());
        AgentAssembledContext assembledContext = contextAssemblyService.assemble(session, memory, latestUserMessage);
        DecisionEvaluation decision = decide(session, assembledContext, stepIndexHint);
        budgetTracker.recordEstimatedModelTokens(decision.estimatedModelTokensUsed());
        TurnExecution execution = executeDecision(turnId, session, memory, assembledContext, decision.decision(), budgetTracker);
        return execution.withExecutionSummary(budgetTracker.finish(execution.stopReason()));
    }

    /**
     * 受控多步执行入口。
     * 只有显式开启多步模式时才会进入该链路，避免影响当前单步基线。
     */
    private TurnExecution executeBoundedLoop(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot initialMemory,
        String latestUserMessage,
        ResolvedRunConfig runConfig
    ) {
        LoopBudgetTracker budgetTracker = LoopBudgetTracker.start(runConfig);
        AgentMemorySnapshot currentMemory = initialMemory;
        CompletedToolStep lastToolStep = null;

        while (true) {
            AgentLoopStopReason budgetStopReason = budgetTracker.resolveBudgetStopBeforeNextStep();
            if (budgetStopReason != null) {
                return finalizeBudgetStop(turnId, session, currentMemory, budgetTracker, lastToolStep, budgetStopReason);
            }

            budgetTracker.beginStep();
            int stepIndexHint = traceService.estimateNextStepIndex(session.getSessionId());
            AgentAssembledContext assembledContext = contextAssemblyService.assemble(
                session,
                currentMemory,
                latestUserMessage
            );
            DecisionEvaluation decision = decide(
                session,
                assembledContext,
                stepIndexHint,
                budgetTracker.promptBudgetSummary()
            );
            budgetTracker.recordEstimatedModelTokens(decision.estimatedModelTokensUsed());

            switch (decision.decision().route()) {
                case DIRECT_REPLY -> {
                    TurnExecution execution = executeDirectReply(
                        turnId,
                        session,
                        currentMemory,
                        decision.decision()
                    );
                    return execution.withExecutionSummary(budgetTracker.finish(execution.stopReason()));
                }
                case DEGRADED_REPLY -> {
                    TurnExecution execution = executeDegradedReply(
                        turnId,
                        currentMemory,
                        decision.decision()
                    );
                    return execution.withExecutionSummary(budgetTracker.finish(execution.stopReason()));
                }
                case PENDING_APPROVAL -> {
                    TurnExecution execution = executePendingApproval(
                        turnId,
                        session,
                        currentMemory,
                        assembledContext,
                        decision.decision()
                    );
                    return execution.withExecutionSummary(budgetTracker.finish(execution.stopReason()));
                }
                case TOOL_CALL -> {
                    ToolStepExecution toolExecution = executeLoopToolStep(
                        turnId,
                        session,
                        currentMemory,
                        assembledContext,
                        decision.decision()
                    );
                    if (toolExecution.terminalExecution() != null) {
                        return toolExecution.terminalExecution()
                            .withExecutionSummary(budgetTracker.finish(toolExecution.stopReason()));
                    }
                    currentMemory = toolExecution.updatedMemory();
                    lastToolStep = toolExecution.completedToolStep();
                }
            }
        }
    }

    /**
     * 让模型基于目标、记忆和最新用户输入，产出本轮执行决策。
     * 同时估算本次结构化调用大致消耗的模型预算，供多步控制使用。
     */
    private DecisionEvaluation decide(
        AgentSessionEntity session,
        AgentAssembledContext assembledContext,
        int stepIndex
    ) {
        return decide(session, assembledContext, stepIndex, "");
    }

    private DecisionEvaluation decide(
        AgentSessionEntity session,
        AgentAssembledContext assembledContext,
        int stepIndex,
        String runtimeBudgetSummary
    ) {
        String systemPrompt = promptService.buildDecisionSystemPrompt(
            toolRegistry.describeTools(),
            decisionOutputConverter.getFormat()
        );
        String userPrompt = isBlank(runtimeBudgetSummary)
            ? promptService.buildDecisionUserPrompt(assembledContext, stepIndex)
            : promptService.buildDecisionUserPrompt(assembledContext, stepIndex, runtimeBudgetSummary);
        int estimatedTokens = estimateModelTokens(systemPrompt, userPrompt);
        try {
            // 1. 组装系统提示词和用户提示词，让模型输出结构化决策。
            AgentDecisionDTO decision = structuredOutputInvoker.invoke(
                chatClient,
                systemPrompt,
                userPrompt,
                decisionOutputConverter,
                ErrorCode.AI_RESPONSE_FORMAT_INVALID,
                "Agent 决策失败",
                "Agent 决策",
                log
            );
            estimatedTokens += estimateDecisionOutputTokens(decision);

            // 2. 模型输出只是提案，真正执行前还要做本地校验与参数补齐。
            return new DecisionEvaluation(resolveDecision(session, assembledContext, decision), estimatedTokens);
        } catch (Exception e) {
            log.warn("Agent 决策失败，已降级为直接回复: sessionId={}, error={}", session.getSessionId(), e.getMessage());
            return new DecisionEvaluation(ResolvedDecision.degraded(
                "模型决策失败，降级为直接文本回复",
                DECISION_FALLBACK_TOOL,
                Map.of(),
                buildFallbackReply(session),
                "模型决策失败: " + safeMessage(e),
                List.of()
            ), estimatedTokens);
        }
    }

    /**
     * 校验并标准化模型决策。
     * 这里会处理工具名缺失、工具不存在、参数缺失等情况，避免把无效提案直接送去执行。
     */
    private ResolvedDecision resolveDecision(
        AgentSessionEntity session,
        AgentAssembledContext assembledContext,
        AgentDecisionDTO decision
    ) {
        String decisionSummary = blankToDefault(
            decision.decisionSummary(),
            Boolean.TRUE.equals(decision.shouldUseTool())
                ? "Use tool to gather more context"
                : "Answer the user directly"
        );

        if (!Boolean.TRUE.equals(decision.shouldUseTool())) {
            return ResolvedDecision.direct(
                decisionSummary,
                resolveDirectAnswer(session, decision)
            );
        }

        // 1. 工具模式下先验证 toolName 是否存在、是否可用。
        if (isBlank(decision.toolName())) {
            return ResolvedDecision.degraded(
                decisionSummary,
                INVALID_TOOL_NAME,
                decision.toolInput(),
                buildDecisionFallbackReply(session),
                "模型要求调用工具，但 toolName 为空",
                List.of()
            );
        }

        AgentTool tool = toolRegistry.findTool(decision.toolName()).orElse(null);
        if (tool == null) {
            return ResolvedDecision.degraded(
                decisionSummary,
                decision.toolName(),
                decision.toolInput(),
                buildDecisionFallbackReply(session),
                "模型返回了不可用的 toolName: " + decision.toolName(),
                List.of()
            );
        }

        // 2. 根据统一装配后的上下文补齐常见入参，再检查是否还有必填项缺失。
        Map<String, Object> toolInput = enrichToolInput(tool.name(), decision.toolInput(), assembledContext);
        List<String> missingInputs = findMissingInputs(tool, toolInput);
        if (!missingInputs.isEmpty()) {
            AgentGuardrailResult guardrailResult = new AgentGuardrailResult(
                null,
                AgentGuardrailCode.TOOL_MISSING_REQUIRED_INPUT,
                interview.guide.modules.agent.guardrail.AgentGuardrailAction.REJECT,
                interview.guide.modules.agent.guardrail.AgentGuardrailResolution.BLOCK_TOOL_CALL,
                "调用 " + tool.name() + " 前缺少必要参数: " + String.join(", ", missingInputs)
            );
            return ResolvedDecision.degraded(
                decisionSummary,
                tool.name(),
                toolInput,
                buildMissingInputReply(session, missingInputs),
                "调用 " + tool.name() + " 前缺少必要参数: " + String.join(", ", missingInputs),
                List.of(guardrailResult)
            );
        }

        AgentGuardrailService.ToolGuardrailDecision toolGuardrail = guardrailService.evaluateTool(tool, toolInput);
        if (toolGuardrail.blocked()) {
            return ResolvedDecision.degraded(
                decisionSummary,
                tool.name(),
                toolGuardrail.toolInput(),
                buildToolGuardrailReply(toolGuardrail.result()),
                toolGuardrail.result().reason(),
                toolGuardrail.guardrailResults()
            );
        }

        ApprovalRequirement approvalRequirement = resolveApprovalRequirement(tool);
        if (approvalRequirement.required()) {
            return ResolvedDecision.approval(
                decisionSummary,
                tool,
                toolInput,
                approvalRequirement.guardrailResult()
            );
        }

        return ResolvedDecision.tool(decisionSummary, tool, toolInput);
    }

    /**
     * 根据已解析的路由选择具体执行分支。
     */
    private TurnExecution executeDecision(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        AgentAssembledContext assembledContext,
        ResolvedDecision decision,
        LoopBudgetTracker budgetTracker
    ) {
        return switch (decision.route()) {
            case DIRECT_REPLY -> executeDirectReply(turnId, session, memory, decision);
            case DEGRADED_REPLY -> executeDegradedReply(turnId, memory, decision);
            case PENDING_APPROVAL -> executePendingApproval(turnId, session, memory, assembledContext, decision);
            case TOOL_CALL -> executeToolReply(turnId, session, memory, assembledContext, decision, budgetTracker);
        };
    }

    /**
     * 记录直接回复型 trace，并原样返回当前记忆快照。
     */
    private TurnExecution executeDirectReply(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        ResolvedDecision decision
    ) {
        AgentGuardrailService.OutputGuardrailDecision outputGuardrail = guardrailService.evaluateOutput(
            decision.reply(),
            buildFallbackReply(session)
        );
        String reply = outputGuardrail.reply();
        traceService.recordDirectReply(
            turnId,
            decision.decisionSummary(),
            reply,
            memory,
            memory,
            outputGuardrail.guardrailResults()
        );
        AgentCompletionMode completionMode = outputGuardrail.degraded()
            ? AgentCompletionMode.DEGRADED
            : AgentCompletionMode.SUCCESS;
        return new TurnExecution(reply, memory, completionMode, null, null, AgentLoopStopReason.DIRECT_REPLY, null);
    }

    /**
     * 记录降级回复型 trace。
     * 这类分支不会更新记忆，只保留一条可追溯的失败原因。
     */
    private TurnExecution executeDegradedReply(
        String turnId,
        AgentMemorySnapshot memory,
        ResolvedDecision decision
    ) {
        traceService.recordRejectedToolDecision(
            turnId,
            decision.decisionSummary(),
            decision.selectedTool(),
            decision.toolInput(),
            decision.failureReason(),
            decision.reply(),
            memory,
            memory,
            decision.guardrailResults()
        );
        return new TurnExecution(
            decision.reply(),
            memory,
            AgentCompletionMode.DEGRADED,
            null,
            null,
            AgentLoopStopReason.DEGRADED_REPLY,
            null
        );
    }

    /**
     * 高风险工具不再直接拒绝，而是进入待审批状态，等待显式 approve / reject。
     */
    private TurnExecution executePendingApproval(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        AgentAssembledContext assembledContext,
        ResolvedDecision decision
    ) {
        String reply = buildApprovalPendingReply(decision.selectedTool());
        AgentApprovalRuntimeService.PendingApprovalTransition transition = approvalRuntimeService.parkTurnForApproval(
            new AgentApprovalRuntimeService.ParkTurnForApprovalRequest(
                turnId,
                session,
                memory,
                assembledContext.latestUserMessage(),
                assembledContext,
                decision.decisionSummary(),
                decision.selectedTool(),
                effectiveRiskLevel(decision.tool()),
                decision.toolInput(),
                reply,
                decision.guardrailResults()
            )
        );
        return TurnExecution.waitingApproval(
            reply,
            memory,
            transition.approval(),
            transition.persistedTurn(),
            AgentLoopStopReason.PENDING_APPROVAL
        );
    }

    /**
     * 执行工具调用，并在成功后基于工具结果生成最终回复。
     */
    private TurnExecution executeToolReply(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        AgentAssembledContext assembledContext,
        ResolvedDecision decision,
        LoopBudgetTracker budgetTracker
    ) {
        // 1. 先记录一个 RUNNING 状态的 trace，保证后续成功或失败都能闭环。
        AgentStepTraceEntity trace = traceService.startToolStep(
            turnId,
            decision.decisionSummary(),
            decision.selectedTool(),
            decision.toolInput(),
            memory
        );

        try {
            // 2. 先执行工具本身，再单独处理 memory / trace 后处理，避免污染 tool 成功率。
            AgentToolResult result = decision.tool().execute(
                decision.toolInput(),
                buildToolContext(assembledContext)
            );
            metricsService.recordToolExecution(decision.tool().name(), true);
            AgentMemorySnapshot postProcessedMemory = memory;
            try {
                postProcessedMemory = memoryService.updateAfterTool(memory, decision.tool().name(), result);
                GeneratedAnswer generatedAnswer = buildFinalAnswer(
                    session,
                    assembledContext.latestUserMessage(),
                    postProcessedMemory,
                    decision.tool().name(),
                    result
                );
                budgetTracker.recordEstimatedModelTokens(generatedAnswer.estimatedModelTokensUsed());
                AgentGuardrailService.OutputGuardrailDecision outputGuardrail = guardrailService.evaluateOutput(
                    generatedAnswer.reply(),
                    buildFallbackReply(session)
                );
                traceService.completeToolStep(
                    trace,
                    result,
                    postProcessedMemory,
                    outputGuardrail.reply(),
                    outputGuardrail.guardrailResults()
                );
                AgentCompletionMode completionMode = outputGuardrail.degraded()
                    ? AgentCompletionMode.DEGRADED
                    : AgentCompletionMode.SUCCESS;
                return new TurnExecution(
                    outputGuardrail.reply(),
                    postProcessedMemory,
                    completionMode,
                    null,
                    null,
                    AgentLoopStopReason.TOOL_COMPLETED_SINGLE_STEP,
                    null
                );
            } catch (Exception e) {
                String reply = buildToolPostProcessingFailureReply(session, decision.tool().name());
                traceService.failToolStep(
                    trace,
                    e,
                    reply,
                    memory,
                    "tool_post_processing_failure",
                    "工具后处理失败，已回退为直接回复"
                );
                log.warn("Agent Tool 后处理失败: sessionId={}, tool={}, error={}",
                    session.getSessionId(), decision.tool().name(), e.getMessage());
                return new TurnExecution(
                    reply,
                    memory,
                    AgentCompletionMode.DEGRADED,
                    null,
                    null,
                    AgentLoopStopReason.TOOL_POST_PROCESSING_FAILED,
                    null
                );
            }
        } catch (Exception e) {
            // 3. 工具失败时不让整轮异常扩散，而是落失败 trace 并返回降级文案。
            String reply = buildToolFailureReply(session, decision.tool().name());
            metricsService.recordToolExecution(decision.tool().name(), false);
            traceService.failToolStep(
                trace,
                e,
                reply,
                memory,
                "tool_execution_failure",
                "工具执行失败，已回退为直接回复"
            );
            log.warn("Agent Tool 执行失败: sessionId={}, tool={}, error={}", session.getSessionId(), decision.tool().name(), e.getMessage());
            return new TurnExecution(
                reply,
                memory,
                AgentCompletionMode.DEGRADED,
                null,
                null,
                AgentLoopStopReason.TOOL_EXECUTION_FAILED,
                null
            );
        }
    }

    /**
     * 多步模式下只执行工具与 memory 更新，不立即生成最终回复。
     * 这样下一步可以继续基于更新后的 memory 重新决策。
     */
    private ToolStepExecution executeLoopToolStep(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        AgentAssembledContext assembledContext,
        ResolvedDecision decision
    ) {
        AgentStepTraceEntity trace = traceService.startToolStep(
            turnId,
            decision.decisionSummary(),
            decision.selectedTool(),
            decision.toolInput(),
            memory
        );

        try {
            AgentToolResult result = decision.tool().execute(
                decision.toolInput(),
                buildToolContext(assembledContext)
            );
            metricsService.recordToolExecution(decision.tool().name(), true);
            try {
                AgentMemorySnapshot updatedMemory = memoryService.updateAfterTool(memory, decision.tool().name(), result);
                traceService.completeToolStep(trace, result, updatedMemory, "", List.of());
                return ToolStepExecution.continueLoop(
                    updatedMemory,
                    new CompletedToolStep(decision.tool().name(), result)
                );
            } catch (Exception e) {
                String reply = buildToolPostProcessingFailureReply(session, decision.tool().name());
                traceService.failToolStep(
                    trace,
                    e,
                    reply,
                    memory,
                    "tool_post_processing_failure",
                    "工具后处理失败，已回退为直接回复"
                );
                log.warn("Agent 多步 Tool 后处理失败: sessionId={}, tool={}, error={}",
                    session.getSessionId(), decision.tool().name(), e.getMessage());
                return ToolStepExecution.terminal(new TurnExecution(
                    reply,
                    memory,
                    AgentCompletionMode.DEGRADED,
                    null,
                    null,
                    AgentLoopStopReason.TOOL_POST_PROCESSING_FAILED,
                    null
                ));
            }
        } catch (Exception e) {
            String reply = buildToolFailureReply(session, decision.tool().name());
            metricsService.recordToolExecution(decision.tool().name(), false);
            traceService.failToolStep(
                trace,
                e,
                reply,
                memory,
                "tool_execution_failure",
                "工具执行失败，已回退为直接回复"
            );
            log.warn("Agent 多步 Tool 执行失败: sessionId={}, tool={}, error={}",
                session.getSessionId(), decision.tool().name(), e.getMessage());
            return ToolStepExecution.terminal(new TurnExecution(
                reply,
                memory,
                AgentCompletionMode.DEGRADED,
                null,
                null,
                AgentLoopStopReason.TOOL_EXECUTION_FAILED,
                null
            ));
        }
    }

    /**
     * 当多步预算耗尽时，使用确定性文案收口，而不是再发起新的模型调用。
     */
    private TurnExecution finalizeBudgetStop(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot currentMemory,
        LoopBudgetTracker budgetTracker,
        CompletedToolStep lastToolStep,
        AgentLoopStopReason stopReason
    ) {
        String rawReply = buildBudgetExhaustedReply(currentMemory, lastToolStep, stopReason);
        AgentGuardrailService.OutputGuardrailDecision outputGuardrail = guardrailService.evaluateOutput(
            rawReply,
            buildFallbackReply(session)
        );
        traceService.recordBudgetExhaustedStop(
            turnId,
            stopReason,
            outputGuardrail.reply(),
            currentMemory,
            currentMemory,
            outputGuardrail.guardrailResults()
        );
        return new TurnExecution(
            outputGuardrail.reply(),
            currentMemory,
            AgentCompletionMode.DEGRADED,
            null,
            null,
            stopReason,
            budgetTracker.finish(stopReason)
        );
    }

    /**
     * 组装接口层最终需要的会话视图。
     */
    private AgentChatResponse buildChatResponse(
        AgentTurnEntity completedTurn,
        AgentApprovalDTO approval,
        String reply,
        AgentMemorySnapshot memorySnapshot,
        AgentExecutionSummaryDTO executionSummary
    ) {
        String turnId = completedTurn.getTurnId();
        List<AgentTraceDTO> traceSteps = traceService.getTurnTrace(turnId);
        List<AgentGuardrailResult> guardrailResults = traceSteps.stream()
            .flatMap(step -> step.guardrailResults().stream())
            .toList();
        return new AgentChatResponse(
            completedTurn.getSession().getSessionId(),
            turnId,
            completedTurn.getStatus(),
            completedTurn.getCompletionMode(),
            approval,
            reply,
            memorySnapshot,
            traceSteps,
            guardrailResults,
            sessionService.getTurnMessages(turnId),
            executionSummary
        );
    }

    /**
     * 为工具执行构造统一上下文，避免工具重复查询会话信息。
     */
    private AgentToolContext buildToolContext(AgentAssembledContext assembledContext) {
        return new AgentToolContext(assembledContext);
    }

    /**
     * 根据工具类型补齐默认入参。
     */
    private Map<String, Object> enrichToolInput(
        String toolName,
        Map<String, Object> rawInput,
        AgentAssembledContext assembledContext
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (rawInput != null) {
            input.putAll(rawInput);
        }
        Long resumeId = assembledContext == null ? null : assembledContext.resumeId();
        List<Long> knowledgeBaseIds = assembledContext == null ? List.of() : assembledContext.knowledgeBaseIds();
        String latestUserMessage = assembledContext == null ? null : assembledContext.latestUserMessage();
        if (("get_resume_profile".equals(toolName)
            || "get_interview_history_summary".equals(toolName)
            || "analyze_interview_gaps".equals(toolName)
            || "suggest_follow_up_questions".equals(toolName))
            && !input.containsKey("resumeId")
            && resumeId != null) {
            input.put("resumeId", resumeId);
        }
        if ("search_knowledge_base".equals(toolName)) {
            if (!input.containsKey("knowledgeBaseIds")) {
                input.put("knowledgeBaseIds", knowledgeBaseIds);
            }
            if (!input.containsKey("question")) {
                input.put("question", latestUserMessage);
            }
        }
        return input;
    }

    /**
     * 找出工具仍缺失的必填参数。
     */
    private List<String> findMissingInputs(AgentTool tool, Map<String, Object> toolInput) {
        List<String> missingInputs = new java.util.ArrayList<>();
        for (String key : safeList(tool.requiredInputs())) {
            if (isMissing(toolInput.get(key))) {
                missingInputs.add(key);
            }
        }
        for (List<String> group : safeListOfLists(tool.requiredAnyOfInputs())) {
            List<String> normalizedGroup = safeList(group);
            if (normalizedGroup.isEmpty()) {
                continue;
            }
            boolean satisfied = normalizedGroup.stream().anyMatch(key -> !isMissing(toolInput.get(key)));
            if (!satisfied) {
                missingInputs.add(String.join("/", normalizedGroup));
            }
        }
        return missingInputs;
    }

    /**
     * 判断一个参数值是否应视为“未提供”。
     */
    private boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String str) {
            return str.isBlank();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<List<String>> safeListOfLists(List<List<String>> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 让模型基于工具结果生成最终回答。
     * 如果生成失败，则回退到工具摘要或通用兜底文案。
     */
    private GeneratedAnswer buildFinalAnswer(
        AgentSessionEntity session,
        String latestUserMessage,
        AgentMemorySnapshot updatedMemory,
        String toolName,
        AgentToolResult toolResult
    ) {
        String systemPrompt = promptService.buildAnswerSystemPrompt();
        try {
            AgentAssembledContext assembledContext = contextAssemblyService.assemble(
                session,
                updatedMemory,
                latestUserMessage
            );
            String userPrompt = promptService.buildAnswerUserPrompt(assembledContext, toolName, toolResult);
            int estimatedTokens = estimateModelTokens(systemPrompt, userPrompt);
            String content = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
            String reply = blankToDefault(content, blankToDefault(toolResult.summary(), buildFallbackReply(session)));
            return new GeneratedAnswer(reply, estimatedTokens + estimateTextTokens(content));
        } catch (Exception e) {
            log.warn("Agent 最终回复生成失败，回退到工具摘要: sessionId={}, tool={}, error={}",
                session.getSessionId(), toolName, e.getMessage());
            return new GeneratedAnswer(
                blankToDefault(toolResult.summary(), buildFallbackReply(session)),
                estimateModelTokens(systemPrompt, toolResult.summary())
            );
        }
    }

    /**
     * 解析直接回复分支的最终文案。
     */
    private String resolveDirectAnswer(AgentSessionEntity session, AgentDecisionDTO decision) {
        return blankToDefault(decision.directAnswer(), buildFallbackReply(session));
    }

    /**
     * 构造“决策不可执行”时的兜底回复。
     */
    private String buildDecisionFallbackReply(AgentSessionEntity session) {
        // 当模型给出的工具决策不可执行时，优先返回保守兜底文案。
        if (session.getResumeId() == null && sessionService.readKnowledgeBaseIds(session).isEmpty()) {
            return buildFallbackReply(session);
        }
        return "本轮没有拿到可执行的工具决策。我先保守处理这次回复，你可以再试一次，或把问题描述得更具体一些。";
    }

    /**
     * 针对缺失的关键参数返回更具体的引导文案。
     */
    private String buildMissingInputReply(AgentSessionEntity session, List<String> missingInputs) {
        if (missingInputs.contains(SESSION_OR_RESUME_INPUT)) {
            return "当前缺少可用的面试上下文。请提供 sessionId，或先绑定一份简历让系统拿到 resumeId 后再继续。";
        }
        // 按缺失参数类型返回更贴近场景的提示，帮助用户补齐上下文。
        if (missingInputs.contains("resumeId")) {
            return "当前缺少可用的简历上下文。请先绑定一份简历后再继续。";
        }
        if (missingInputs.contains("knowledgeBaseIds")) {
            return "当前缺少可用的知识库上下文。请先选择至少一个知识库后再继续。";
        }
        if (missingInputs.contains("question")) {
            return "我需要更具体的问题描述后才能继续检索知识库。请补充你想问的主题、岗位或场景。";
        }
        return buildFallbackReply(session);
    }

    /**
     * 根据工具类型返回更贴近场景的失败提示。
     */
    private String buildToolFailureReply(AgentSessionEntity session, String toolName) {
        if ("get_resume_profile".equals(toolName)) {
            return "这次读取简历上下文失败了。你可以稍后重试，或先确认简历仍然可用。";
        }
        if ("search_knowledge_base".equals(toolName)) {
            return "这次检索知识库失败了。你可以稍后重试，或先把问题描述得更具体一些。";
        }
        return buildFallbackReply(session);
    }

    /**
     * Tool 已成功执行，但后续 memory / trace 收敛失败时的保守回复。
     */
    private String buildToolPostProcessingFailureReply(AgentSessionEntity session, String toolName) {
        if ("get_resume_profile".equals(toolName)) {
            return "这次已经拿到简历结果，但在整理上下文时失败了。你可以稍后重试。";
        }
        if ("search_knowledge_base".equals(toolName)) {
            return "这次已经拿到知识库结果，但在整理上下文时失败了。你可以稍后重试。";
        }
        return buildFallbackReply(session);
    }

    /**
     * 构造最通用的会话兜底回复。
     */
    private String buildFallbackReply(AgentSessionEntity session) {
        // 是否有可用上下文，决定兜底文案是提示绑定资源还是提示重试。
        boolean hasResume = session.getResumeId() != null;
        boolean hasKnowledgeBase = !sessionService.readKnowledgeBaseIds(session).isEmpty();
        if (!hasResume && !hasKnowledgeBase) {
            return "我已经记录你的目标，但当前没有可用的简历或知识库上下文。请先绑定一份简历，或选择一个知识库后再继续。";
        }
        return "我已经记录你的目标，但本轮没有成功完成自动决策。你可以再试一次，或把问题描述得更具体一些，例如指定岗位方向、简历或知识库主题。";
    }

    /**
     * 输入 Guardrail 命中后的统一安全回复。
     */
    private String buildInputGuardrailReply(AgentGuardrailResult guardrailResult) {
        if (guardrailResult == null || guardrailResult.code() == null) {
            return "这次输入没有通过安全校验，我先不继续自动执行。请换一种更直接的求职问题描述后再试。";
        }
        if (guardrailResult.code() == AgentGuardrailCode.INPUT_INTERNAL_DATA_REQUEST) {
            return "我不能提供系统提示词、内部调试信息或 Memory 快照，但可以直接帮你分析简历、知识库或面试问题。";
        }
        if (guardrailResult.code() == AgentGuardrailCode.INPUT_MESSAGE_TOO_LONG) {
            return "这次消息过长，我先不继续自动执行。请尽量聚焦一个具体问题，并控制在 4000 字以内。";
        }
        if (guardrailResult.code() == AgentGuardrailCode.INPUT_CONTROL_CHARACTERS) {
            return "这次消息包含不可解析的控制字符，我先不继续自动执行。请清理后重试。";
        }
        return "这次输入没有通过安全校验，我先不继续自动执行。请换一种更直接的求职问题描述后再试。";
    }

    /**
     * 工具 Guardrail 命中后的统一安全回复。
     */
    private String buildToolGuardrailReply(AgentGuardrailResult guardrailResult) {
        if (guardrailResult == null || guardrailResult.code() == null) {
            return "本轮工具调用没有通过安全校验，我没有继续自动执行。请换一种更直接的描述后再试。";
        }
        if (guardrailResult.code() == AgentGuardrailCode.TOOL_REQUIRES_APPROVAL) {
            return "这个动作属于高风险操作，需要审批后才能继续执行。";
        }
        if (guardrailResult.code() == AgentGuardrailCode.TOOL_UNEXPECTED_INPUT) {
            return "本轮工具参数没有通过安全校验，我没有继续自动执行。请换一种更直接的描述后再试。";
        }
        if (guardrailResult.code() == AgentGuardrailCode.TOOL_MISSING_REQUIRED_INPUT) {
            return "当前工具上下文还不完整，我先不继续自动执行。请补充必要信息后再试。";
        }
        return "本轮工具调用没有通过安全校验，我没有继续自动执行。请换一种更直接的描述后再试。";
    }

    /**
     * 解析用户请求里的运行时配置。
     * 默认仍保持单步模式，避免 Stage 5 能力无意间影响既有基线。
     */
    private ResolvedRunConfig resolveRunConfig(AgentRuntimeConfig runtimeConfig) {
        boolean multiStepEnabled = runtimeConfig != null && Boolean.TRUE.equals(runtimeConfig.multiStepEnabled());
        if (!multiStepEnabled) {
            return new ResolvedRunConfig(
                false,
                1,
                DEFAULT_MULTI_STEP_MAX_DURATION_MILLIS,
                DEFAULT_MULTI_STEP_MAX_ESTIMATED_MODEL_TOKENS
            );
        }
        return new ResolvedRunConfig(
            true,
            clampInt(runtimeConfig.maxSteps(), 1, MAX_ALLOWED_MULTI_STEP_STEPS, DEFAULT_MULTI_STEP_MAX_STEPS),
            clampLong(
                runtimeConfig.maxDurationMillis(),
                1L,
                MAX_ALLOWED_MULTI_STEP_DURATION_MILLIS,
                DEFAULT_MULTI_STEP_MAX_DURATION_MILLIS
            ),
            clampInt(
                runtimeConfig.maxEstimatedModelTokens(),
                1,
                MAX_ALLOWED_MULTI_STEP_ESTIMATED_MODEL_TOKENS,
                DEFAULT_MULTI_STEP_MAX_ESTIMATED_MODEL_TOKENS
            )
        );
    }

    /**
     * 输入 Guardrail 在模型决策前就终止，因此执行步数仍为 0。
     */
    private AgentExecutionSummaryDTO buildInputGuardrailExecutionSummary(ResolvedRunConfig runConfig) {
        return new AgentExecutionSummaryDTO(
            runConfig.multiStepEnabled(),
            runConfig.maxSteps(),
            0,
            runConfig.maxSteps(),
            runConfig.maxDurationMillis(),
            0L,
            runConfig.maxDurationMillis(),
            runConfig.maxEstimatedModelTokens(),
            0,
            runConfig.maxEstimatedModelTokens(),
            AgentLoopStopReason.INPUT_GUARDRAIL_BLOCKED,
            null
        );
    }

    /**
     * 多步预算耗尽时的统一收口文案。
     * 这里不再发起新的模型调用，只复用已有中间结果给出阶段性结论。
     */
    private String buildBudgetExhaustedReply(
        AgentMemorySnapshot currentMemory,
        CompletedToolStep lastToolStep,
        AgentLoopStopReason stopReason
    ) {
        String budgetPhrase = switch (stopReason) {
            case TIME_BUDGET_EXHAUSTED -> "本轮多步时间预算已用尽";
            case TOKEN_BUDGET_EXHAUSTED -> "本轮多步模型预算已用尽";
            default -> "本轮多步预算已用尽";
        };
        if (lastToolStep != null && !isBlank(lastToolStep.result().summary())) {
            String nextFocus = currentMemory == null ? "" : blankToDefault(currentMemory.nextFocus(), "");
            String nextSentence = isBlank(nextFocus) ? "" : " 当前建议先聚焦：" + nextFocus + "。";
            return budgetPhrase + "，我先停在当前结论。" + " 已拿到的中间结论是：" + lastToolStep.result().summary() + "。" + nextSentence;
        }
        if (currentMemory != null && !isBlank(currentMemory.nextFocus())) {
            return budgetPhrase + "，我先停在当前结论。建议下一轮直接围绕“" + currentMemory.nextFocus() + "”继续追问。";
        }
        return budgetPhrase + "，我先停在当前结论。你可以基于当前问题拆成更具体的下一轮请求，我再继续处理。";
    }

    /**
     * 按粗粒度字符规则估算模型预算。
     * 当前底层没有稳定暴露真实 usage，因此这里只做可解释的近似值，不伪装成精确 token。
     */
    private int estimateModelTokens(String... segments) {
        int chars = 0;
        if (segments != null) {
            for (String segment : segments) {
                chars += segment == null ? 0 : segment.trim().length();
            }
        }
        return estimateTextTokens(chars);
    }

    private int estimateDecisionOutputTokens(AgentDecisionDTO decision) {
        if (decision == null) {
            return 0;
        }
        int chars = 0;
        chars += safeLength(decision.toolName());
        chars += safeLength(decision.decisionSummary());
        chars += safeLength(decision.directAnswer());
        chars += safeLength(String.valueOf(decision.toolInput()));
        return estimateTextTokens(chars);
    }

    private int estimateTextTokens(String value) {
        return estimateTextTokens(safeLength(value));
    }

    private int estimateTextTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, (chars + 3) / 4);
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.trim().length();
    }

    private int clampInt(Integer value, int min, int max, int defaultValue) {
        int normalized = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, normalized));
    }

    private long clampLong(Long value, long min, long max, long defaultValue) {
        long normalized = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, normalized));
    }

    /**
     * 为空字符串提供统一兜底值。
     */
    private String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    /**
     * 判断字符串是否为空白。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 清洗异常信息，避免落库内容包含换行或为空。
     */
    private String safeMessage(Exception error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "unknown_error";
        }
        return error.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * 在新 turn 开始前清理当前会话下已经过期的审批。
     * 这样可以及时释放 WAITING_APPROVAL 的旧 turn，避免它们长期占住会话。
     */
    private void expirePendingApprovals(String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        for (var approval : approvalService.getPendingApprovals(sessionId)) {
            if (!approvalService.isExpired(approval, now)) {
                continue;
            }
            approvalService.withLockedApproval(approval.getApprovalId(), lockedApproval -> {
                if (lockedApproval.getStatus() != AgentApprovalStatus.PENDING
                    || !approvalService.isExpired(lockedApproval, LocalDateTime.now())) {
                    return null;
                }
                finalizeExpiredApproval(lockedApproval);
                return null;
            });
        }
    }

    /**
     * 审批策略只看工具风险等级，不把策略散落在 if / else 分支里。
     */
    private ApprovalRequirement resolveApprovalRequirement(AgentTool tool) {
        AgentToolRiskLevel riskLevel = effectiveRiskLevel(tool);
        if (riskLevel != AgentToolRiskLevel.REQUIRES_APPROVAL) {
            return ApprovalRequirement.notRequired();
        }
        String reason = tool.riskLevel() == null
            ? "工具未声明风险等级，已按高风险动作进入审批"
            : "高风险工具必须先审批后执行";
        return ApprovalRequirement.required(new AgentGuardrailResult(
            null,
            AgentGuardrailCode.TOOL_REQUIRES_APPROVAL,
            interview.guide.modules.agent.guardrail.AgentGuardrailAction.REQUIRE_APPROVAL,
            interview.guide.modules.agent.guardrail.AgentGuardrailResolution.WAIT_FOR_APPROVAL,
            reason
        ));
    }

    private AgentToolRiskLevel effectiveRiskLevel(AgentTool tool) {
        return tool == null || tool.riskLevel() == null
            ? AgentToolRiskLevel.REQUIRES_APPROVAL
            : tool.riskLevel();
    }

    private String buildApprovalPendingReply(String toolName) {
        if ("delete_resume".equals(toolName)) {
            return "这个动作属于高风险操作，需要审批后才能继续执行。我已经先停在等待审批状态。";
        }
        return "这个动作属于高风险操作，需要审批后才能继续执行。我已经先停在等待审批状态。";
    }

    private String buildApprovalRejectedReply(String toolName) {
        if ("delete_resume".equals(toolName)) {
            return "审批已拒绝，这个高风险动作不会执行。";
        }
        return "审批已拒绝，这个高风险动作不会执行。";
    }

    private String buildApprovalExpiredReply(String toolName) {
        if ("delete_resume".equals(toolName)) {
            return "审批已过期，这个高风险动作不会继续执行。";
        }
        return "审批已过期，这个高风险动作不会继续执行。";
    }

    private String resolveLatencyOutcome(AgentCompletionMode completionMode) {
        if (completionMode == AgentCompletionMode.DEGRADED) {
            return "degraded";
        }
        if (completionMode == AgentCompletionMode.WAITING_APPROVAL) {
            return "waiting_approval";
        }
        return "success";
    }

    /**
     * 将已过期审批正式收敛为终态。
     * 这里会同时推进 approval、trace 和 turn，保证三者语义一致。
     */
    private ApprovalTransition finalizeExpiredApproval(AgentApprovalEntity approval) {
        AgentApprovalDTO expiredApproval = approvalService.markExpired(approval);
        AgentMemorySnapshot memory = memoryService.readMemory(approval.getSession());
        String reply = buildApprovalExpiredReply(approval.getSelectedTool());
        traceService.markToolStepApprovalExpired(approval.getTrace(), expiredApproval, reply, memory);
        AgentTurnEntity completedTurn = sessionService.completeTurn(
            approval.getTurn().getTurnId(),
            reply,
            memory,
            AgentCompletionMode.DEGRADED
        );
        return ApprovalTransition.finalized(completedTurn, expiredApproval, reply, memory);
    }

    /**
     * 在审批已经是 APPROVED 的前提下，尝试为当前请求抢占恢复执行权。
     * 抢占成功后，还要根据 trace 状态决定到底是执行工具、恢复结果，还是阻止重放。
     */
    private ApprovalTransition claimApprovedRecovery(AgentApprovalEntity approval, AgentApprovalDTO approvedApproval) {
        AgentSessionService.ApprovedTurnClaim turnClaim = sessionService.claimTurnForApprovedExecution(
            approval.getTurn().getTurnId()
        );
        if (turnClaim.claimed()) {
            return ApprovalTransition.claimed(new ApprovalExecutionClaim(
                approval,
                approvedApproval,
                resolveApprovedExecutionMode(approval.getTrace())
            ));
        }
        return ApprovalTransition.snapshot(approvedApproval.approvalId(), turnClaim.turn());
    }

    /**
     * 根据审批关联 trace 的状态，推导批准后的恢复模式。
     */
    private ApprovedExecutionMode resolveApprovedExecutionMode(AgentStepTraceEntity trace) {
        if (trace == null || trace.getStatus() == null || trace.getStatus() == AgentExecutionState.WAITING_APPROVAL) {
            return ApprovedExecutionMode.EXECUTE_TOOL;
        }
        if (trace.getStatus() == AgentExecutionState.RUNNING) {
            return ApprovedExecutionMode.BLOCK_REPLAY;
        }
        if (trace.getStatus() == AgentExecutionState.COMPLETED || trace.getStatus() == AgentExecutionState.FAILED) {
            return ApprovedExecutionMode.FINALIZE_FROM_TRACE;
        }
        return ApprovedExecutionMode.EXECUTE_TOOL;
    }

    /**
     * 从已持久化的 trace 恢复批准后的最终结果，而不是重新执行工具。
     * 适用于工具结果已经落盘，但 turn 还没有最终收口的场景。
     */
    private AgentChatResponse finalizeApprovedTraceRecovery(ApprovalExecutionClaim claim, AgentSessionEntity session) {
        AgentTraceService.ApprovedExecutionRecovery recovery = traceService.readApprovedExecutionRecovery(
            claim.approval().getTrace()
        );
        AgentMemorySnapshot memory = recovery.memoryAfter() == null ? readApprovalMemory(session) : recovery.memoryAfter();
        String reply = recovery.reply() == null || recovery.reply().isBlank()
            ? buildApprovedReplayBlockedReply(claim.approval().getSelectedTool())
            : recovery.reply();
        AgentCompletionMode completionMode = recovery.completionMode();
        if (completionMode == null) {
            completionMode = recovery.status() == AgentExecutionState.FAILED
                ? AgentCompletionMode.DEGRADED
                : AgentCompletionMode.SUCCESS;
        }
        try {
            AgentTurnEntity completedTurn = sessionService.completeTurn(
                claim.approval().getTurn().getTurnId(),
                reply,
                memory,
                completionMode
            );
            return buildChatResponse(completedTurn, claim.approvedApproval(), reply, memory, null);
        } catch (Exception e) {
            AgentTurnEntity failedTurn = sessionService.failTurn(
                claim.approval().getTurn().getTurnId(),
                e,
                reply
            );
            return buildChatResponse(failedTurn, claim.approvedApproval(), reply, memory, null);
        }
    }

    /**
     * 解析没有执行 claim 的审批过渡结果。
     * 这里只会返回两类结果：
     * 1. `finalized`：审批已经在本次调用里收口为终态
     * 2. `snapshot`：本次调用不拥有继续执行的资格，只返回当前快照
     */
    private AgentChatResponse resolveApprovalTransition(ApprovalTransition transition) {
        if (transition.finalized() != null) {
            FinalizedApproval finalized = transition.finalized();
            return buildChatResponse(finalized.turn(), finalized.approval(), finalized.reply(), finalized.memory(), null);
        }
        return buildApprovalSnapshotResponse(
            approvalService.getApproval(transition.snapshotApprovalId()),
            transition.snapshotTurn()
        );
    }

    /**
     * 读取审批恢复阶段使用的 memory。
     * 如果 memory 存储异常，则退回初始快照，避免整个恢复链路被读错误打断。
     */
    private AgentMemorySnapshot readApprovalMemory(AgentSessionEntity session) {
        try {
            return memoryService.readMemory(session);
        } catch (Exception e) {
            log.warn("Failed to read approval memory, falling back to initial snapshot. sessionId={}, error={}",
                session.getSessionId(), e.getMessage());
            return memoryService.createInitialSnapshot(session.getGoal());
        }
    }

    /**
     * 统一处理审批通过后的失败收口。
     * 这里优先把失败结果回填到原 trace，再把 turn 收敛成 DEGRADED；
     * 如果连正常收口都失败，才退回 `failTurn`。
     */
    private AgentChatResponse finalizeApprovedFailure(
        ApprovalExecutionClaim claim,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        Exception error,
        String reply,
        String failureKind,
        String observationSummary,
        boolean recordToolFailure
    ) {
        if (recordToolFailure) {
            metricsService.recordToolExecution(claim.approval().getSelectedTool(), false);
        }
        try {
            traceService.failApprovedToolStep(
                claim.approval().getTrace(),
                claim.approvedApproval(),
                error,
                reply,
                memory,
                failureKind,
                observationSummary
            );
            AgentTurnEntity completedTurn = sessionService.completeTurn(
                claim.approval().getTurn().getTurnId(),
                reply,
                memory,
                AgentCompletionMode.DEGRADED
            );
            return buildChatResponse(completedTurn, claim.approvedApproval(), reply, memory, null);
        } catch (Exception finalizeError) {
            try {
                AgentTurnEntity failedTurn = sessionService.failTurn(
                    claim.approval().getTurn().getTurnId(),
                    error,
                    reply
                );
                return buildChatResponse(failedTurn, claim.approvedApproval(), reply, memory, null);
            } catch (Exception failTurnError) {
                finalizeError.addSuppressed(failTurnError);
            }
            throw finalizeError;
        }
    }

    /**
     * 为审批快照场景选择一条最合理的回复文案。
     * 优先级是：最新 assistant 回复 -> trace 中的终态回复 -> 按审批状态生成兜底文案。
     */
    private String resolveApprovalSnapshotReply(AgentApprovalDTO approval, AgentTurnEntity turn) {
        String assistantReply = resolveLatestReply(turn.getTurnId());
        String traceReply = traceService.readLatestReply(turn.getTurnId());
        traceReply = traceReply == null ? "" : traceReply;
        if (approval.status() == AgentApprovalStatus.APPROVED) {
            if (turn.getStatus() == AgentTurnStatus.RUNNING) {
                return buildApprovedExecutionInProgressReply(approval.selectedTool());
            }
            if (!traceReply.isBlank()) {
                return traceReply;
            }
            String pendingReply = buildApprovalPendingReply(approval.selectedTool());
            if (!assistantReply.isBlank() && !assistantReply.equals(pendingReply)) {
                return assistantReply;
            }
            return buildApprovedReplayBlockedReply(approval.selectedTool());
        }
        if (!assistantReply.isBlank()) {
            return assistantReply;
        }
        if (!traceReply.isBlank()) {
            return traceReply;
        }
        if (approval.status() == AgentApprovalStatus.REJECTED) {
            return buildApprovalRejectedReply(approval.selectedTool());
        }
        if (approval.status() == AgentApprovalStatus.EXPIRED) {
            return buildApprovalExpiredReply(approval.selectedTool());
        }
        return buildApprovalPendingReply(approval.selectedTool());
    }

    private String buildApprovedExecutionInProgressReply(String toolName) {
        if ("delete_resume".equals(toolName)) {
            return "审批已通过，这个高风险动作仍在处理中。请稍后刷新结果，不要重复批准。";
        }
        return "审批已通过，这个高风险动作仍在处理中。请稍后刷新结果，不要重复批准。";
    }

    private String buildApprovedReplayBlockedReply(String toolName) {
        if ("delete_resume".equals(toolName)) {
            return "审批已通过，但上一次执行状态已不明确。为避免重复副作用，本次不再自动重放。请先确认外部系统结果，必要时重新发起新请求。";
        }
        return "审批已通过，但上一次执行状态已不明确。为避免重复副作用，本次不再自动重放。请先确认外部系统结果，必要时重新发起新请求。";
    }

    /**
     * 用审批 DTO 和当前 turn 快照组装审批查询响应。
     */
    private AgentChatResponse buildApprovalSnapshotResponse(AgentApprovalDTO approval, AgentTurnEntity turn) {
        AgentMemorySnapshot memory = readApprovalMemory(turn.getSession());
        return buildChatResponse(turn, approval, resolveApprovalSnapshotReply(approval, turn), memory, null);
    }

    /**
     * 从当前 turn 的消息增量中读取最后一条 assistant 回复。
     */
    private String resolveLatestReply(String turnId) {
        return sessionService.getTurnMessages(turnId).stream()
            .filter(message -> "assistant".equalsIgnoreCase(message.role()))
            .reduce((first, second) -> second)
            .map(message -> message.content() == null ? "" : message.content())
            .orElse("");
    }

    /**
     * 一次审批请求在编排器里的过渡结果。
     * 同一时刻只会命中三种形态之一：
     * - snapshot：本次请求只返回快照，不继续推进执行
     * - claim：本次请求拿到了继续推进审批恢复链路的资格
     * - finalized：本次请求已经把审批收口成终态
     */
    private record ApprovalTransition(
        String snapshotApprovalId,
        AgentTurnEntity snapshotTurn,
        ApprovalExecutionClaim claim,
        FinalizedApproval finalized
    ) {
        private static ApprovalTransition snapshot(String approvalId, AgentTurnEntity turn) {
            return new ApprovalTransition(approvalId, turn, null, null);
        }

        private static ApprovalTransition claimed(ApprovalExecutionClaim claim) {
            return new ApprovalTransition(null, null, claim, null);
        }

        private static ApprovalTransition finalized(
            AgentTurnEntity turn,
            AgentApprovalDTO approval,
            String reply,
            AgentMemorySnapshot memory
        ) {
            return new ApprovalTransition(null, null, null, new FinalizedApproval(turn, approval, reply, memory));
        }
    }

    /**
     * 当前请求拿到审批恢复执行权后的上下文。
     */
    private record ApprovalExecutionClaim(
        AgentApprovalEntity approval,
        AgentApprovalDTO approvedApproval,
        ApprovedExecutionMode mode
    ) {
    }

    /**
     * 审批已经在本次调用内收口后的最终结果。
     */
    private record FinalizedApproval(
        AgentTurnEntity turn,
        AgentApprovalDTO approval,
        String reply,
        AgentMemorySnapshot memory
    ) {
    }

    /**
     * 审批通过后的三种恢复模式。
     */
    private enum ApprovedExecutionMode {
        /**
         * 工具尚未真正执行，可以按冻结输入执行一次。
         */
        EXECUTE_TOOL,
        /**
         * 工具结果已经落在 trace 里，只需要恢复结果，不再执行工具。
         */
        FINALIZE_FROM_TRACE,
        /**
         * 工具可能已经开始执行，但状态不明确。
         * 为避免重复副作用，禁止自动重放。
         */
        BLOCK_REPLAY
    }

    private enum DecisionRoute {
        DIRECT_REPLY,
        PENDING_APPROVAL,
        TOOL_CALL,
        DEGRADED_REPLY
    }

    /**
     * 模型决策在本地校验后的标准表示。
     */
    private record ResolvedDecision(
        DecisionRoute route,
        String decisionSummary,
        String reply,
        AgentTool tool,
        String selectedTool,
        Map<String, Object> toolInput,
        String failureReason,
        List<AgentGuardrailResult> guardrailResults
    ) {
        private static ResolvedDecision direct(String decisionSummary, String reply) {
            return new ResolvedDecision(
                DecisionRoute.DIRECT_REPLY,
                decisionSummary,
                reply,
                null,
                DIRECT_ANSWER_TOOL,
                Map.of(),
                null,
                List.of()
            );
        }

        private static ResolvedDecision tool(String decisionSummary, AgentTool tool, Map<String, Object> toolInput) {
            return new ResolvedDecision(
                DecisionRoute.TOOL_CALL,
                decisionSummary,
                null,
                tool,
                tool.name(),
                immutableCopy(toolInput),
                null,
                List.of()
            );
        }

        private static ResolvedDecision approval(
            String decisionSummary,
            AgentTool tool,
            Map<String, Object> toolInput,
            AgentGuardrailResult guardrailResult
        ) {
            return new ResolvedDecision(
                DecisionRoute.PENDING_APPROVAL,
                decisionSummary,
                null,
                tool,
                tool.name(),
                immutableCopy(toolInput),
                null,
                guardrailResult == null ? List.of() : List.of(guardrailResult)
            );
        }

        private static ResolvedDecision degraded(
            String decisionSummary,
            String selectedTool,
            Map<String, Object> toolInput,
            String reply,
            String failureReason,
            List<AgentGuardrailResult> guardrailResults
        ) {
            return new ResolvedDecision(
                DecisionRoute.DEGRADED_REPLY,
                decisionSummary,
                reply,
                null,
                selectedTool,
                immutableCopy(toolInput),
                failureReason,
                guardrailResults == null ? List.of() : List.copyOf(guardrailResults)
            );
        }

        private static Map<String, Object> immutableCopy(Map<String, Object> toolInput) {
            if (toolInput == null || toolInput.isEmpty()) {
                return Map.of();
            }
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(toolInput));
        }
    }

    /**
     * 一次模型决策调用的结果。
     * 除了解析后的决策外，还会带回本次调用的大致模型预算消耗。
     */
    private record DecisionEvaluation(ResolvedDecision decision, int estimatedModelTokensUsed) {
    }

    /**
     * 最终回答生成结果。
     */
    private record GeneratedAnswer(String reply, int estimatedModelTokensUsed) {
    }

    /**
     * 已完成的一次工具步骤中间结果。
     * 供后续预算收口文案复用，而不必再次访问工具原始实现。
     */
    private record CompletedToolStep(String toolName, AgentToolResult result) {
    }

    /**
     * 多步工具执行结果。
     * 成功时继续循环；失败时直接返回一条可收口的终态执行结果。
     */
    private record ToolStepExecution(
        AgentMemorySnapshot updatedMemory,
        CompletedToolStep completedToolStep,
        TurnExecution terminalExecution,
        AgentLoopStopReason stopReason
    ) {
        private static ToolStepExecution continueLoop(
            AgentMemorySnapshot updatedMemory,
            CompletedToolStep completedToolStep
        ) {
            return new ToolStepExecution(updatedMemory, completedToolStep, null, null);
        }

        private static ToolStepExecution terminal(TurnExecution terminalExecution) {
            return new ToolStepExecution(
                terminalExecution.memorySnapshot(),
                null,
                terminalExecution,
                terminalExecution.stopReason()
            );
        }
    }

    /**
     * 某条执行分支产出的最终结果。
     */
    private record TurnExecution(
        String reply,
        AgentMemorySnapshot memorySnapshot,
        AgentCompletionMode completionMode,
        AgentApprovalDTO approval,
        AgentTurnEntity persistedTurn,
        AgentLoopStopReason stopReason,
        AgentExecutionSummaryDTO executionSummary
    ) {
        private static TurnExecution waitingApproval(
            String reply,
            AgentMemorySnapshot memorySnapshot,
            AgentApprovalDTO approval,
            AgentTurnEntity persistedTurn,
            AgentLoopStopReason stopReason
        ) {
            return new TurnExecution(
                reply,
                memorySnapshot,
                AgentCompletionMode.WAITING_APPROVAL,
                approval,
                persistedTurn,
                stopReason,
                null
            );
        }

        private TurnExecution withExecutionSummary(AgentExecutionSummaryDTO executionSummary) {
            return new TurnExecution(
                reply,
                memorySnapshot,
                completionMode,
                approval,
                persistedTurn,
                stopReason,
                executionSummary
            );
        }
    }

    /**
     * 解析后的安全运行配置。
     */
    private record ResolvedRunConfig(
        boolean multiStepEnabled,
        int maxSteps,
        long maxDurationMillis,
        int maxEstimatedModelTokens
    ) {
    }

    /**
     * 受控多步的预算跟踪器。
     * 这里统一维护步数、耗时和估算模型预算，避免多处重复计算。
     */
    private static final class LoopBudgetTracker {

        private final ResolvedRunConfig runConfig;
        private final long startedAtMillis;
        private int executedSteps;
        private int estimatedModelTokensUsed;

        private LoopBudgetTracker(ResolvedRunConfig runConfig, long startedAtMillis) {
            this.runConfig = runConfig;
            this.startedAtMillis = startedAtMillis;
        }

        private static LoopBudgetTracker start(ResolvedRunConfig runConfig) {
            return new LoopBudgetTracker(runConfig, System.currentTimeMillis());
        }

        private void beginStep() {
            executedSteps++;
        }

        private void recordEstimatedModelTokens(int estimatedTokens) {
            estimatedModelTokensUsed += Math.max(0, estimatedTokens);
        }

        private AgentLoopStopReason resolveBudgetStopBeforeNextStep() {
            if (executedSteps >= runConfig.maxSteps()) {
                return AgentLoopStopReason.STEP_BUDGET_EXHAUSTED;
            }
            if (elapsedMillis() >= runConfig.maxDurationMillis()) {
                return AgentLoopStopReason.TIME_BUDGET_EXHAUSTED;
            }
            if (estimatedModelTokensUsed >= runConfig.maxEstimatedModelTokens()) {
                return AgentLoopStopReason.TOKEN_BUDGET_EXHAUSTED;
            }
            return null;
        }

        private String promptBudgetSummary() {
            return """
                - 多步模式: %s
                - 已执行步骤: %d/%d
                - 当前准备开始第 %d 步
                - 剩余可执行步骤: %d
                - 已用估算模型预算: %d
                - 剩余估算模型预算: %d
                - 已耗时(ms): %d
                - 剩余时间(ms): %d
                - 如果当前上下文已经足够回答，请直接给出最终 directAnswer，不要继续调用工具。
                """.formatted(
                runConfig.multiStepEnabled() ? "开启" : "关闭",
                executedSteps,
                runConfig.maxSteps(),
                executedSteps + 1,
                remainingSteps(),
                estimatedModelTokensUsed,
                remainingEstimatedModelTokens(),
                elapsedMillis(),
                remainingDurationMillis()
            ).trim();
        }

        private AgentExecutionSummaryDTO finish(AgentLoopStopReason stopReason) {
            AgentLoopStopReason budgetStopReason = resolveBudgetStopForSummary(stopReason);
            return new AgentExecutionSummaryDTO(
                runConfig.multiStepEnabled(),
                runConfig.maxSteps(),
                executedSteps,
                remainingSteps(),
                runConfig.maxDurationMillis(),
                elapsedMillis(),
                remainingDurationMillis(),
                runConfig.maxEstimatedModelTokens(),
                estimatedModelTokensUsed,
                remainingEstimatedModelTokens(),
                stopReason,
                budgetStopReason
            );
        }

        private int remainingSteps() {
            return Math.max(0, runConfig.maxSteps() - executedSteps);
        }

        private int remainingEstimatedModelTokens() {
            return Math.max(0, runConfig.maxEstimatedModelTokens() - estimatedModelTokensUsed);
        }

        private long elapsedMillis() {
            return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
        }

        private long remainingDurationMillis() {
            return Math.max(0L, runConfig.maxDurationMillis() - elapsedMillis());
        }

        /**
         * 汇总执行摘要时，补充“预算是否已经被当前终态打穿”的语义。
         * stopReason 保留真实收口原因；budgetStopReason 只表达预算边界是否已命中。
         */
        private AgentLoopStopReason resolveBudgetStopForSummary(AgentLoopStopReason stopReason) {
            // 单步模式只是复用统一摘要结构，不对外暴露多步预算边界语义。
            if (!runConfig.multiStepEnabled()) {
                return null;
            }
            if (isBudgetStopReason(stopReason)) {
                return stopReason;
            }
            // 摘要里的预算命中顺序与真正“下一步开始前”的预算检查保持一致，
            // 避免 summary 语义和运行时 stop 判定顺序不一致。
            if (executedSteps >= runConfig.maxSteps()) {
                return AgentLoopStopReason.STEP_BUDGET_EXHAUSTED;
            }
            if (elapsedMillis() >= runConfig.maxDurationMillis()) {
                return AgentLoopStopReason.TIME_BUDGET_EXHAUSTED;
            }
            if (estimatedModelTokensUsed >= runConfig.maxEstimatedModelTokens()) {
                return AgentLoopStopReason.TOKEN_BUDGET_EXHAUSTED;
            }
            return null;
        }

        private boolean isBudgetStopReason(AgentLoopStopReason stopReason) {
            return stopReason == AgentLoopStopReason.STEP_BUDGET_EXHAUSTED
                || stopReason == AgentLoopStopReason.TIME_BUDGET_EXHAUSTED
                || stopReason == AgentLoopStopReason.TOKEN_BUDGET_EXHAUSTED;
        }
    }

    /**
     * 工具审批需求。
     */
    private record ApprovalRequirement(
        boolean required,
        AgentGuardrailResult guardrailResult
    ) {
        private static ApprovalRequirement notRequired() {
            return new ApprovalRequirement(false, null);
        }

        private static ApprovalRequirement required(AgentGuardrailResult guardrailResult) {
            return new ApprovalRequirement(true, guardrailResult);
        }
    }
}
