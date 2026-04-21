package interview.guide.modules.agent.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentChatRequest;
import interview.guide.modules.agent.model.AgentChatResponse;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentDecisionDTO;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.AgentTool;
import interview.guide.modules.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

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
    private final AgentPromptService promptService;
    private final BeanOutputConverter<AgentDecisionDTO> decisionOutputConverter;

    public AgentOrchestrator(
        ChatClient.Builder chatClientBuilder,
        StructuredOutputInvoker structuredOutputInvoker,
        ToolRegistry toolRegistry,
        AgentSessionService sessionService,
        AgentMemoryService memoryService,
        AgentTraceService traceService,
        AgentPromptService promptService
    ) {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.toolRegistry = toolRegistry;
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.traceService = traceService;
        this.promptService = promptService;
        this.decisionOutputConverter = new BeanOutputConverter<>(AgentDecisionDTO.class);
    }

    /**
     * 执行一轮 Agent 对话。
     * 远程模型调用和工具调用都放在事务外，只在 turn 的开始和结束阶段做持久化。
     */
    public AgentChatResponse chat(String sessionId, AgentChatRequest request) {
        String turnId = null;
        boolean turnCompleted = false;
        AgentChatResponse response;

        try {
            // 1. 创建 turn，并先把用户消息落库，确保本轮执行有唯一归属。
            AgentSessionService.StartedTurn startedTurn = sessionService.startTurn(sessionId, request.message());
            turnId = startedTurn.turnId();
            AgentSessionEntity session = startedTurn.session();

            // 2. 读取记忆并生成决策，决定是直接回复、调用工具还是走降级分支。
            AgentMemorySnapshot memory = memoryService.readMemory(session);
            int stepIndexHint = traceService.estimateNextStepIndex(sessionId);
            ResolvedDecision decision = decide(session, memory, request.message(), stepIndexHint);

            // 3. 执行决策并在成功后提交 turn 完成态。
            TurnExecution execution = executeDecision(turnId, session, memory, request.message(), decision);
            AgentTurnEntity completedTurn = sessionService.completeTurn(
                turnId,
                execution.reply(),
                execution.memorySnapshot(),
                execution.completionMode()
            );
            turnCompleted = true;
            response = buildChatResponse(completedTurn, execution.reply(), execution.memorySnapshot());
        } catch (Exception e) {
            // 4. 只有 turn 已创建时才补失败终态；终态保护由 sessionService 负责。
            if (turnId != null && !turnCompleted) {
                sessionService.failTurn(turnId, e);
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
                "模型决策失败: " + safeMessage(e)
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
                "模型要求调用工具，但 toolName 为空"
            );
        }

        AgentTool tool = toolRegistry.findTool(decision.toolName()).orElse(null);
        if (tool == null) {
            return ResolvedDecision.degraded(
                decisionSummary,
                decision.toolName(),
                decision.toolInput(),
                buildDecisionFallbackReply(session),
                "模型返回了不可用的 toolName: " + decision.toolName()
            );
        }

        // 2. 根据会话上下文补齐常见入参，再检查是否还有必填项缺失。
        Map<String, Object> toolInput = enrichToolInput(tool.name(), decision.toolInput(), session, latestUserMessage);
        List<String> missingInputs = findMissingInputs(tool, toolInput);
        if (!missingInputs.isEmpty()) {
            return ResolvedDecision.degraded(
                decisionSummary,
                tool.name(),
                toolInput,
                buildMissingInputReply(session, missingInputs),
                "调用 " + tool.name() + " 前缺少必要参数: " + String.join(", ", missingInputs)
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
            case DIRECT_REPLY -> executeDirectReply(turnId, memory, decision);
            case DEGRADED_REPLY -> executeDegradedReply(turnId, memory, decision);
            case TOOL_CALL -> executeToolReply(turnId, session, memory, latestUserMessage, decision);
        };
    }

    /**
     * 记录直接回复型 trace，并原样返回当前记忆快照。
     */
    private TurnExecution executeDirectReply(
        String turnId,
        AgentMemorySnapshot memory,
        ResolvedDecision decision
    ) {
        traceService.recordDirectReply(turnId, decision.decisionSummary(), decision.reply());
        return new TurnExecution(decision.reply(), memory, AgentCompletionMode.SUCCESS);
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
            decision.reply()
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
            decision.toolInput()
        );

        try {
            // 2. 执行工具、更新 trace，并把工具输出合并进最新记忆。
            AgentToolResult result = decision.tool().execute(
                decision.toolInput(),
                buildToolContext(session, memory, latestUserMessage)
            );
            traceService.completeToolStep(trace, result);
            AgentMemorySnapshot updatedMemory = memoryService.updateAfterTool(memory, decision.tool().name(), result);
            String reply = buildFinalAnswer(
                session,
                latestUserMessage,
                updatedMemory,
                decision.tool().name(),
                result
            );
            return new TurnExecution(reply, updatedMemory, AgentCompletionMode.SUCCESS);
        } catch (Exception e) {
            // 3. 工具失败时不让整轮异常扩散，而是落失败 trace 并返回降级文案。
            String reply = buildToolFailureReply(session, decision.tool().name());
            traceService.failToolStep(trace, e, reply);
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
        return new AgentChatResponse(
            completedTurn.getSession().getSessionId(),
            turnId,
            completedTurn.getStatus(),
            completedTurn.getCompletionMode(),
            reply,
            memorySnapshot,
            traceService.getTurnTrace(turnId),
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
        String failureReason
    ) {
        private static ResolvedDecision direct(String decisionSummary, String reply) {
            return new ResolvedDecision(
                DecisionRoute.DIRECT_REPLY,
                decisionSummary,
                reply,
                null,
                DIRECT_ANSWER_TOOL,
                Map.of(),
                null
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
                null
            );
        }

        private static ResolvedDecision degraded(
            String decisionSummary,
            String selectedTool,
            Map<String, Object> toolInput,
            String reply,
            String failureReason
        ) {
            return new ResolvedDecision(
                DecisionRoute.DEGRADED_REPLY,
                decisionSummary,
                reply,
                null,
                selectedTool,
                immutableCopy(toolInput),
                failureReason
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
