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
    String directAnswer
) {

    public AgentDecisionDTO {
        toolName = normalize(toolName);
        decisionSummary = normalize(decisionSummary);
        directAnswer = normalize(directAnswer);
        toolInput = toolInput == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(toolInput));

        // Tool path keeps only the decision contract.
        if (Boolean.TRUE.equals(shouldUseTool)) {
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
