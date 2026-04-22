package interview.guide.modules.agent.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.guardrail.AgentGuardrailService;
import interview.guide.modules.agent.model.AgentChatRequest;
import interview.guide.modules.agent.model.AgentChatResponse;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentDecisionDTO;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.AgentTool;
import interview.guide.modules.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Timer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行编排器。
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private static final String DIRECT_ANSWER_TOOL = "direct_answer";
    private static final String DECISION_FALLBACK_TOOL = "decision_fallback";
    private static final String INVALID_TOOL_NAME = "invalid_tool";

    private final ChatClient chatClient;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final ToolRegistry toolRegistry;
    private final AgentSessionService sessionService;
    private final AgentMemoryService memoryService;
    private final AgentTraceService traceService;
    private final AgentMetricsService metricsService;
    private final AgentPromptService promptService;
    private final AgentGuardrailService guardrailService;
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
        AgentGuardrailService guardrailService
    ) {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.toolRegistry = toolRegistry;
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.traceService = traceService;
        this.metricsService = metricsService;
        this.promptService = promptService;
        this.guardrailService = guardrailService;
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

        try {
            // 1. 创建 turn，并先把用户消息落库，确保本轮执行有唯一归属。
            AgentSessionService.StartedTurn startedTurn = sessionService.startTurn(sessionId, request.message());
            turnId = startedTurn.turnId();
            AgentSessionEntity session = startedTurn.session();
            metricsService.recordTurnStarted();
            metricsService.recordTurnReclaimed(startedTurn.reclaimedExpiredTurnCount());

            // 2. 先做输入 Guardrail，命中后直接返回安全回复，不再进入模型决策链路。
            AgentMemorySnapshot memory = memoryService.readMemory(session);
            AgentGuardrailService.InputGuardrailDecision inputGuardrail = guardrailService.evaluateInput(request.message());
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
                metricsService.recordTurnCompleted(completionMode);
                turnTerminalPersisted = true;
                response = buildChatResponse(completedTurn, reply, memory);
                metricsService.stopTurnLatency(latencySample, "degraded");
                return response;
            }

            // 3. 输入安全后，再读取 stepIndex 并生成决策。
            int stepIndexHint = traceService.estimateNextStepIndex(sessionId);
            ResolvedDecision decision = decide(session, memory, inputGuardrail.normalizedMessage(), stepIndexHint);

            // 4. 执行决策并在成功后提交 turn 完成态。
            TurnExecution execution = executeDecision(turnId, session, memory, inputGuardrail.normalizedMessage(), decision);
            AgentTurnEntity completedTurn = sessionService.completeTurn(
                turnId,
                execution.reply(),
                execution.memorySnapshot(),
                execution.completionMode()
            );
            completionMode = execution.completionMode();
            metricsService.recordTurnCompleted(completionMode);
            turnTerminalPersisted = true;
            response = buildChatResponse(completedTurn, execution.reply(), execution.memorySnapshot());
            metricsService.stopTurnLatency(
                latencySample,
                completionMode == AgentCompletionMode.DEGRADED ? "degraded" : "success"
            );
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
     * 让模型基于目标、记忆和最新用户输入，产出本轮执行决策。
     */
    private ResolvedDecision decide(
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        int stepIndex
    ) {
        try {
            // 1. 组装系统提示词和用户提示词，让模型输出结构化决策。
            String systemPrompt = promptService.buildDecisionSystemPrompt(
                toolRegistry.describeTools(),
                decisionOutputConverter.getFormat()
            );
            String userPrompt = promptService.buildDecisionUserPrompt(
                session.getGoal(),
                latestUserMessage,
                memory,
                stepIndex
            );
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

            // 2. 模型输出只是提案，真正执行前还要做本地校验与参数补齐。
            return resolveDecision(session, latestUserMessage, decision);
        } catch (Exception e) {
            log.warn("Agent 决策失败，已降级为直接回复: sessionId={}, error={}", session.getSessionId(), e.getMessage());
            return ResolvedDecision.degraded(
                "模型决策失败，降级为直接文本回复",
                DECISION_FALLBACK_TOOL,
                Map.of(),
                buildFallbackReply(session),
                "模型决策失败: " + safeMessage(e),
                List.of()
            );
        }
    }

    /**
     * 校验并标准化模型决策。
     * 这里会处理工具名缺失、工具不存在、参数缺失等情况，避免把无效提案直接送去执行。
     */
    private ResolvedDecision resolveDecision(
        AgentSessionEntity session,
        String latestUserMessage,
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

        // 2. 根据会话上下文补齐常见入参，再检查是否还有必填项缺失。
        Map<String, Object> toolInput = enrichToolInput(tool.name(), decision.toolInput(), session, latestUserMessage);
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

        return ResolvedDecision.tool(decisionSummary, tool, toolInput);
    }

    /**
     * 根据已解析的路由选择具体执行分支。
     */
    private TurnExecution executeDecision(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        ResolvedDecision decision
    ) {
        return switch (decision.route()) {
            case DIRECT_REPLY -> executeDirectReply(turnId, session, memory, decision);
            case DEGRADED_REPLY -> executeDegradedReply(turnId, memory, decision);
            case TOOL_CALL -> executeToolReply(turnId, session, memory, latestUserMessage, decision);
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
        return new TurnExecution(reply, memory, completionMode);
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
        return new TurnExecution(decision.reply(), memory, AgentCompletionMode.DEGRADED);
    }

    /**
     * 执行工具调用，并在成功后基于工具结果生成最终回复。
     */
    private TurnExecution executeToolReply(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        ResolvedDecision decision
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
                buildToolContext(session, memory, latestUserMessage)
            );
            metricsService.recordToolExecution(decision.tool().name(), true);
            AgentMemorySnapshot postProcessedMemory = memory;
            try {
                postProcessedMemory = memoryService.updateAfterTool(memory, decision.tool().name(), result);
                String reply = buildFinalAnswer(
                    session,
                    latestUserMessage,
                    postProcessedMemory,
                    decision.tool().name(),
                    result
                );
                AgentGuardrailService.OutputGuardrailDecision outputGuardrail = guardrailService.evaluateOutput(
                    reply,
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
                return new TurnExecution(outputGuardrail.reply(), postProcessedMemory, completionMode);
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
                return new TurnExecution(reply, memory, AgentCompletionMode.DEGRADED);
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
            return new TurnExecution(reply, memory, AgentCompletionMode.DEGRADED);
        }
    }

    /**
     * 组装接口层最终需要的会话视图。
     */
    private AgentChatResponse buildChatResponse(
        AgentTurnEntity completedTurn,
        String reply,
        AgentMemorySnapshot memorySnapshot
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
            reply,
            memorySnapshot,
            traceSteps,
            guardrailResults,
            sessionService.getTurnMessages(turnId)
        );
    }

    /**
     * 为工具执行构造统一上下文，避免工具重复查询会话信息。
     */
    private AgentToolContext buildToolContext(
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage
    ) {
        List<Long> knowledgeBaseIds = sessionService.readKnowledgeBaseIds(session);
        return new AgentToolContext(
            session.getSessionId(),
            session.getResumeId(),
            knowledgeBaseIds,
            memory,
            latestUserMessage
        );
    }

    /**
     * 根据工具类型补齐默认入参。
     */
    private Map<String, Object> enrichToolInput(
        String toolName,
        Map<String, Object> rawInput,
        AgentSessionEntity session,
        String latestUserMessage
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (rawInput != null) {
            input.putAll(rawInput);
        }
        if ("get_resume_profile".equals(toolName) && !input.containsKey("resumeId") && session.getResumeId() != null) {
            input.put("resumeId", session.getResumeId());
        }
        if ("search_knowledge_base".equals(toolName)) {
            if (!input.containsKey("knowledgeBaseIds")) {
                input.put("knowledgeBaseIds", sessionService.readKnowledgeBaseIds(session));
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
        return tool.requiredInputs().stream()
            .filter(key -> isMissing(toolInput.get(key)))
            .toList();
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

    /**
     * 让模型基于工具结果生成最终回答。
     * 如果生成失败，则回退到工具摘要或通用兜底文案。
     */
    private String buildFinalAnswer(
        AgentSessionEntity session,
        String latestUserMessage,
        AgentMemorySnapshot updatedMemory,
        String toolName,
        AgentToolResult toolResult
    ) {
        try {
            String content = chatClient.prompt()
                .system(promptService.buildAnswerSystemPrompt())
                .user(promptService.buildAnswerUserPrompt(
                    session.getGoal(),
                    latestUserMessage,
                    updatedMemory,
                    toolName,
                    toolResult
                ))
                .call()
                .content();
            return blankToDefault(content, blankToDefault(toolResult.summary(), buildFallbackReply(session)));
        } catch (Exception e) {
            log.warn("Agent 最终回复生成失败，回退到工具摘要: sessionId={}, tool={}, error={}",
                session.getSessionId(), toolName, e.getMessage());
            return blankToDefault(toolResult.summary(), buildFallbackReply(session));
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
            return "这个动作属于高风险操作，当前版本不会自动执行。后续审批能力会在 S2-03 中补齐。";
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
     * 本轮决策的三种执行路径。
     */
    private enum DecisionRoute {
        DIRECT_REPLY,
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
     * 某条执行分支产出的最终结果。
     */
    private record TurnExecution(
        String reply,
        AgentMemorySnapshot memorySnapshot,
        AgentCompletionMode completionMode
    ) {
    }
}
