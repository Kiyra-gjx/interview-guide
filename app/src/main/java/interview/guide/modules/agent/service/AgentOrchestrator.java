package interview.guide.modules.agent.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.*;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.AgentTool;
import interview.guide.modules.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行编排器。
 */
@Slf4j
@Service
public class AgentOrchestrator {

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

    @Transactional
    public AgentChatResponse chat(String sessionId, AgentChatRequest request) {
        AgentSessionEntity session = sessionService.getSessionEntity(sessionId);
        session.setStatus(AgentExecutionState.RUNNING);
        sessionService.saveSession(session);
        sessionService.addMessage(session, AgentMessageEntity.MessageRole.USER, request.message());

        AgentMemorySnapshot memory = memoryService.readMemory(session);
        int stepIndex = traceService.nextStepIndex(sessionId);
        AgentDecisionDTO decision = decide(session, memory, request.message(), stepIndex);
        String reply = executeDecision(session, memory, request.message(), stepIndex, decision);

        sessionService.addMessage(session, AgentMessageEntity.MessageRole.ASSISTANT, reply);
        session.setStatus(AgentExecutionState.COMPLETED);
        sessionService.saveSession(session);

        return new AgentChatResponse(
            sessionId,
            reply,
            memoryService.readMemory(session),
            traceService.getTrace(sessionId),
            sessionService.getMessages(sessionId)
        );
    }

    private AgentDecisionDTO decide(
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
            return structuredOutputInvoker.invoke(
                chatClient,
                systemPrompt,
                userPrompt,
                decisionOutputConverter,
                ErrorCode.AI_RESPONSE_FORMAT_INVALID,
                "Agent 决策失败",
                "Agent 决策",
                log
            );
        } catch (Exception e) {
            traceService.recordDecisionFailure(session, stepIndex, "模型决策失败，降级为直接文本回复", e);
            log.warn("Agent 决策失败，已降级为直接文本回复: sessionId={}, error={}", session.getSessionId(), e.getMessage());
            return new AgentDecisionDTO(
                false,
                null,
                Map.of(),
                "决策失败，降级回复",
                buildFallbackReply(session)
            );
        }
    }

    private String executeDecision(
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        int stepIndex,
        AgentDecisionDTO decision
    ) {
        if (!Boolean.TRUE.equals(decision.shouldUseTool()) || isBlank(decision.toolName())) {
            return resolveDirectAnswer(session, decision);
        }

        AgentTool tool = toolRegistry.getRequiredTool(decision.toolName());
        Map<String, Object> toolInput = enrichToolInput(tool.name(), decision.toolInput(), session, latestUserMessage);

        AgentStepTraceEntity trace = traceService.startStep(
            session,
            stepIndex,
            blankToDefault(decision.decisionSummary(), "调用 Tool 补充上下文"),
            tool.name(),
            toolInput
        );

        try {
            AgentToolResult result = tool.execute(toolInput, buildToolContext(session, memory, latestUserMessage));
            traceService.completeStep(trace, result);
            AgentMemorySnapshot updatedMemory = memoryService.updateAfterTool(memory, tool.name(), result);
            memoryService.writeMemory(session, updatedMemory);
            sessionService.saveSession(session);
            return buildFinalAnswer(session, latestUserMessage, updatedMemory, tool.name(), result, decision);
        } catch (Exception e) {
            traceService.failStep(trace, e);
            log.warn("Agent Tool 执行失败: sessionId={}, tool={}, error={}", session.getSessionId(), tool.name(), e.getMessage());
            return blankToDefault(decision.finalAnswer(), buildFallbackReply(session));
        }
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

    private String buildFinalAnswer(
        AgentSessionEntity session,
        String latestUserMessage,
        AgentMemorySnapshot updatedMemory,
        String toolName,
        AgentToolResult toolResult,
        AgentDecisionDTO decision
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
            return blankToDefault(content, blankToDefault(decision.finalAnswer(), toolResult.summary()));
        } catch (Exception e) {
            log.warn("Agent 最终回复生成失败，回退到简化结果: sessionId={}, error={}", session.getSessionId(), e.getMessage());
            return blankToDefault(decision.finalAnswer(), toolResult.summary());
        }
    }

    private String resolveDirectAnswer(AgentSessionEntity session, AgentDecisionDTO decision) {
        return blankToDefault(decision.finalAnswer(), buildFallbackReply(session));
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
}
