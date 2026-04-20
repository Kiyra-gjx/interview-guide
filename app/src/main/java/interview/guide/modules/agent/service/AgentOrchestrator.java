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

    // Remote calls stay outside transactions. Persistence only happens at turn start/end.
    public AgentChatResponse chat(String sessionId, AgentChatRequest request) {
        String turnId = null;
        String reply;

        try {
            AgentSessionService.StartedTurn startedTurn = sessionService.startTurn(sessionId, request.message());
            turnId = startedTurn.turnId();
            AgentSessionEntity session = startedTurn.session();
            AgentMemorySnapshot memory = memoryService.readMemory(session);
            int stepIndexHint = traceService.estimateNextStepIndex(sessionId);
            ResolvedDecision decision = decide(session, memory, request.message(), stepIndexHint);
            TurnExecution execution = executeDecision(turnId, session, memory, request.message(), decision);
            sessionService.completeTurn(turnId, execution.reply(), execution.memorySnapshot(), execution.completionMode());
            reply = execution.reply();
        } catch (Exception e) {
            if (turnId != null) {
                sessionService.failTurn(turnId, e);
            }
            throw e;
        }

        return buildChatResponse(sessionId, reply);
    }

    private ResolvedDecision decide(
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        int stepIndex
    ) {
        try {
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

    // Treat the model output as a proposal until local validation turns it into an executable decision.
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

    private TurnExecution executeDirectReply(
        String turnId,
        AgentMemorySnapshot memory,
        ResolvedDecision decision
    ) {
        traceService.recordDirectReply(turnId, decision.decisionSummary(), decision.reply());
        return new TurnExecution(decision.reply(), memory, AgentCompletionMode.SUCCESS);
    }

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

    private TurnExecution executeToolReply(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        ResolvedDecision decision
    ) {
        AgentStepTraceEntity trace = traceService.startToolStep(
            turnId,
            decision.decisionSummary(),
            decision.selectedTool(),
            decision.toolInput()
        );

        try {
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
            String reply = buildToolFailureReply(session, decision.tool().name());
            traceService.failToolStep(trace, e, reply);
            log.warn("Agent Tool 执行失败: sessionId={}, tool={}, error={}", session.getSessionId(), decision.tool().name(), e.getMessage());
            return new TurnExecution(reply, memory, AgentCompletionMode.DEGRADED);
        }
    }

    private AgentChatResponse buildChatResponse(String sessionId, String reply) {
        AgentSessionEntity latestSession = sessionService.getSessionEntity(sessionId);
        return new AgentChatResponse(
            sessionId,
            reply,
            memoryService.readMemory(latestSession),
            traceService.getTrace(sessionId),
            sessionService.getMessages(sessionId)
        );
    }

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

    private List<String> findMissingInputs(AgentTool tool, Map<String, Object> toolInput) {
        return tool.requiredInputs().stream()
            .filter(key -> isMissing(toolInput.get(key)))
            .toList();
    }

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

    private String resolveDirectAnswer(AgentSessionEntity session, AgentDecisionDTO decision) {
        return blankToDefault(decision.directAnswer(), buildFallbackReply(session));
    }

    private String buildDecisionFallbackReply(AgentSessionEntity session) {
        if (session.getResumeId() == null && sessionService.readKnowledgeBaseIds(session).isEmpty()) {
            return buildFallbackReply(session);
        }
        return "本轮没有拿到可执行的工具决策。我先保守处理这次回复，你可以再试一次，或把问题描述得更具体一些。";
    }

    private String buildMissingInputReply(AgentSessionEntity session, List<String> missingInputs) {
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

    private String buildToolFailureReply(AgentSessionEntity session, String toolName) {
        if ("get_resume_profile".equals(toolName)) {
            return "这次读取简历上下文失败了。你可以稍后重试，或先确认简历仍然可用。";
        }
        if ("search_knowledge_base".equals(toolName)) {
            return "这次检索知识库失败了。你可以稍后重试，或先把问题描述得更具体一些。";
        }
        return buildFallbackReply(session);
    }

    private String buildFallbackReply(AgentSessionEntity session) {
        boolean hasResume = session.getResumeId() != null;
        boolean hasKnowledgeBase = !sessionService.readKnowledgeBaseIds(session).isEmpty();
        if (!hasResume && !hasKnowledgeBase) {
            return "我已经记录你的目标，但当前没有可用的简历或知识库上下文。请先绑定一份简历，或选择一个知识库后再继续。";
        }
        return "我已经记录你的目标，但本轮没有成功完成自动决策。你可以再试一次，或把问题描述得更具体一些，例如指定岗位方向、简历或知识库主题。";
    }

    private String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeMessage(Exception error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "unknown_error";
        }
        return error.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
    }

    private enum DecisionRoute {
        DIRECT_REPLY,
        TOOL_CALL,
        DEGRADED_REPLY
    }

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

    private record TurnExecution(
        String reply,
        AgentMemorySnapshot memorySnapshot,
        AgentCompletionMode completionMode
    ) {
    }
}
