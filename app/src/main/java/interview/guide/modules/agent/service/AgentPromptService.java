package interview.guide.modules.agent.service;

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
        variables.put("stepIndex", stepIndex);
        return userPromptTemplate.render(variables);
    }

    public String buildAnswerSystemPrompt() {
        return ANSWER_SYSTEM_PROMPT;
    }

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
        variables.put("toolName", nullToEmpty(toolName));
        variables.put("toolResultJson", toJson(toolResult.output()));
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
}
