package interview.guide.modules.agent.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 决策输出。
 */
public record AgentDecisionDTO(
    Boolean shouldUseTool,
    String toolName,
    Map<String, Object> toolInput,
    String decisionSummary,
    String directAnswer,
    Boolean shouldDelegate,
    String delegateTask,
    String delegateReason,
    String delegateExpectedOutput
) {

    public AgentDecisionDTO(
        Boolean shouldUseTool,
        String toolName,
        Map<String, Object> toolInput,
        String decisionSummary,
        String directAnswer
    ) {
        this(shouldUseTool, toolName, toolInput, decisionSummary, directAnswer, null, null, null, null);
    }

    public AgentDecisionDTO {
        toolName = normalize(toolName);
        decisionSummary = normalize(decisionSummary);
        directAnswer = normalize(directAnswer);
        delegateTask = normalize(delegateTask);
        delegateReason = normalize(delegateReason);
        delegateExpectedOutput = normalize(delegateExpectedOutput);
        toolInput = toolInput == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(toolInput));

        // Tool path keeps only the decision contract.
        if (Boolean.TRUE.equals(shouldUseTool)) {
            directAnswer = null;
        }
        if (!Boolean.TRUE.equals(shouldDelegate)) {
            delegateTask = null;
            delegateReason = null;
            delegateExpectedOutput = null;
        } else {
            directAnswer = null;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
