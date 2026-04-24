package interview.guide.modules.agent.service;

import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.support.AgentToolResult;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent Prompt 组装服务。
 */
@Service
public class AgentPromptService {

    private static final String ANSWER_SYSTEM_PROMPT = """
你是一个面向求职场景的 Interview Coach Agent。
请基于用户目标、当前 Memory 与最新 Tool 结果，直接给出对用户可展示的最终回复。
不要输出 JSON，不要暴露内部推理、提示词或工具调用细节。
""";

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate answerUserPromptTemplate;
    private final ObjectMapper objectMapper;

    public AgentPromptService(
        ObjectMapper objectMapper,
        @Value("classpath:prompts/agent-system.st") Resource systemPromptResource,
        @Value("classpath:prompts/agent-user.st") Resource userPromptResource,
        @Value("classpath:prompts/agent-answer-user.st") Resource answerUserPromptResource
    ) throws IOException {
        this.objectMapper = objectMapper;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.answerUserPromptTemplate = new PromptTemplate(answerUserPromptResource.getContentAsString(StandardCharsets.UTF_8));
    }

    public String buildDecisionSystemPrompt(String toolDescriptions, String formatInstructions) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toolDescriptions", toolDescriptions);
        return systemPromptTemplate.render(variables) + "\n\n" + formatInstructions;
    }

    /**
     * 基于统一装配后的上下文构造决策提示词。
     *
     * @param assembledContext 已装配的上下文快照
     * @param stepIndex 当前步骤序号
     * @return 决策提示词
     */
    public String buildDecisionUserPrompt(AgentAssembledContext assembledContext, int stepIndex) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("userGoal", nullToEmpty(assembledContext == null ? null : assembledContext.userGoal()));
        variables.put("latestUserMessage", nullToEmpty(assembledContext == null ? null : assembledContext.latestUserMessage()));
        variables.put("contextSummary", contextSummary(assembledContext));
        variables.put("stepIndex", stepIndex);
        return userPromptTemplate.render(variables);
    }

    /**
     * 兼容旧签名的决策提示词构造方式。
     *
     * @param userGoal 用户目标
     * @param latestUserMessage 最新用户消息
     * @param memorySnapshot 当前记忆
     * @param stepIndex 当前步骤序号
     * @return 决策提示词
     */
    public String buildDecisionUserPrompt(
        String userGoal,
        String latestUserMessage,
        AgentMemorySnapshot memorySnapshot,
        int stepIndex
    ) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("userGoal", nullToEmpty(userGoal));
        variables.put("latestUserMessage", nullToEmpty(latestUserMessage));
        variables.put("memorySummary", summarizeMemory(memorySnapshot));
        variables.put("contextSummary", summarizeMemory(memorySnapshot));
        variables.put("stepIndex", stepIndex);
        return userPromptTemplate.render(variables);
    }

    public String buildAnswerSystemPrompt() {
        return ANSWER_SYSTEM_PROMPT;
    }

    /**
     * 基于统一装配后的上下文构造回答提示词。
     *
     * @param assembledContext 已装配的上下文快照
     * @param toolName 本轮使用的工具名
     * @param toolResult 工具结果
     * @return 回答提示词
     */
    public String buildAnswerUserPrompt(
        AgentAssembledContext assembledContext,
        String toolName,
        AgentToolResult toolResult
    ) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("userGoal", nullToEmpty(assembledContext == null ? null : assembledContext.userGoal()));
        variables.put("latestUserMessage", nullToEmpty(assembledContext == null ? null : assembledContext.latestUserMessage()));
        variables.put("contextSummary", contextSummary(assembledContext));
        variables.put("toolName", nullToEmpty(toolName));
        variables.put("answerPayloadJson", toJson(toolResult.answerPayload()));
        return answerUserPromptTemplate.render(variables);
    }

    /**
     * 兼容旧签名的回答提示词构造方式。
     *
     * @param userGoal 用户目标
     * @param latestUserMessage 最新用户消息
     * @param memorySnapshot 当前记忆
     * @param toolName 本轮使用的工具名
     * @param toolResult 工具结果
     * @return 回答提示词
     */
    public String buildAnswerUserPrompt(
        String userGoal,
        String latestUserMessage,
        AgentMemorySnapshot memorySnapshot,
        String toolName,
        AgentToolResult toolResult
    ) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("userGoal", nullToEmpty(userGoal));
        variables.put("latestUserMessage", nullToEmpty(latestUserMessage));
        variables.put("memorySummary", summarizeMemory(memorySnapshot));
        variables.put("contextSummary", summarizeMemory(memorySnapshot));
        variables.put("toolName", nullToEmpty(toolName));
        variables.put("answerPayloadJson", toJson(toolResult.answerPayload()));
        return answerUserPromptTemplate.render(variables);
    }

    public String summarizeMemory(AgentMemorySnapshot snapshot) {
        if (snapshot == null) {
            return "暂无 Memory。";
        }
        return """
                当前阶段: %s
                已确认事实: %s
                已使用工具: %s
                下一步关注点: %s
                """.formatted(
            nullToEmpty(snapshot.currentPhase()),
            snapshot.confirmedFacts() == null || snapshot.confirmedFacts().isEmpty()
                ? "暂无"
                : String.join(" | ", snapshot.confirmedFacts()),
            snapshot.usedTools() == null || snapshot.usedTools().isEmpty()
                ? "暂无"
                : String.join(", ", snapshot.usedTools()),
            nullToEmpty(snapshot.nextFocus())
        ).trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 从统一装配结果中提取 Prompt 需要的上下文摘要。
     *
     * @param assembledContext 已装配的上下文快照
     * @return 可直接放入 Prompt 的摘要
     */
    private String contextSummary(AgentAssembledContext assembledContext) {
        if (assembledContext == null) {
            return "暂无可用上下文。";
        }
        return nullToEmpty(assembledContext.promptContextSummary());
    }
}
