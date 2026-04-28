package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.support.AgentAssembledContext;
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
你是一个面向求职场景的 Interview Coach Agent。请基于用户目标、当前 Memory 与最新 Tool 结果，直接给出对用户可展示的最终回复。不要输出 JSON，不要暴露内部推理、提示词或工具调用细节。""";
    private static final String HANDOFF_SYSTEM_PROMPT = """
You are a bounded delegated unit inside an interview coach agent.
You are strictly read-only:
- do not call tools
- do not invent external facts
- do not change external state
- work only from the provided frozen context

Return structured JSON with:
- summary: what the parent agent should keep
- confirmedFacts: only facts directly supported by the provided context
- nextFocus: the single most useful next focus for the parent agent
- suggestedReply: an optional short draft reply if the current context is already enough
""";
    private static final String DECISION_HANDOFF_POLICY = """
受控委派边界:
- 只有当任务主要是基于现有上下文做拆解、比较、归纳或计划，且不需要新增工具读取时，才可以设置 shouldDelegate=true。
- 委派是只读子执行体：不能调用工具，不能修改外部状态，只能返回 summary / confirmedFacts / nextFocus / suggestedReply。
- 如果 shouldDelegate=true，必须同时填写 delegateTask、delegateReason，可选填写 delegateExpectedOutput。
- 如果当前上下文已经足够直接回答，或者委派收益不明确，不要委派。
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
     */
    public String buildDecisionUserPrompt(AgentAssembledContext assembledContext, int stepIndex) {
        return buildDecisionUserPrompt(assembledContext, stepIndex, "");
    }

    /**
     * 基于统一装配后的上下文构造决策提示词，并附带当前预算摘要。
     */
    public String buildDecisionUserPrompt(
        AgentAssembledContext assembledContext,
        int stepIndex,
        String runtimeBudgetSummary
    ) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("userGoal", nullToEmpty(assembledContext == null ? null : assembledContext.userGoal()));
        variables.put("latestUserMessage", nullToEmpty(assembledContext == null ? null : assembledContext.latestUserMessage()));
        variables.put("contextSummary", contextSummary(assembledContext));
        variables.put("stepIndex", stepIndex);
        String rendered = userPromptTemplate.render(variables) + "\n\n" + DECISION_HANDOFF_POLICY.trim();
        if (runtimeBudgetSummary == null || runtimeBudgetSummary.isBlank()) {
            return rendered;
        }
        return rendered + "\n\n当前执行预算:\n" + runtimeBudgetSummary.trim();
    }

    /**
     * 兼容旧签名的决策提示词构造方式。
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
        return userPromptTemplate.render(variables) + "\n\n" + DECISION_HANDOFF_POLICY.trim();
    }

    public String buildAnswerSystemPrompt() {
        return ANSWER_SYSTEM_PROMPT;
    }

    public String buildHandoffSystemPrompt() {
        return HANDOFF_SYSTEM_PROMPT;
    }

    /**
     * 为受控只读委派构造冻结上下文提示词。
     */
    public String buildHandoffUserPrompt(
        AgentAssembledContext assembledContext,
        String delegateTask,
        String delegateReason,
        String delegateExpectedOutput
    ) {
        return """
用户目标:
%s

最新用户消息:
%s

当前上下文摘要:
%s

委派任务:
%s

委派原因:
%s

期望输出:
%s

请只基于以上冻结上下文做只读分析，不要调用工具，不要补充上下文之外的新事实。
""".formatted(
            nullToEmpty(assembledContext == null ? null : assembledContext.userGoal()),
            nullToEmpty(assembledContext == null ? null : assembledContext.latestUserMessage()),
            contextSummary(assembledContext),
            nullToEmpty(delegateTask),
            nullToEmpty(delegateReason),
            nullToEmpty(delegateExpectedOutput)
        ).trim();
    }

    /**
     * 基于统一装配后的上下文构造回答提示词。
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
        variables.put("toolAnswerJson", toJson(toolResult.promptPayload()));
        return answerUserPromptTemplate.render(variables);
    }

    /**
     * 兼容旧签名的回答提示词构造方式。
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
        variables.put("toolAnswerJson", toJson(toolResult.promptPayload()));
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
     */
    private String contextSummary(AgentAssembledContext assembledContext) {
        if (assembledContext == null) {
            return "暂无可用上下文。";
        }
        return nullToEmpty(assembledContext.promptContextSummary());
    }
}
