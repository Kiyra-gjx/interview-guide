package interview.guide.modules.agent.model;

import java.util.Map;

/**
 * Agent 决策输出。
 */
public record AgentDecisionDTO(
    Boolean shouldUseTool,
    String toolName,
    Map<String, Object> toolInput,
    String decisionSummary,
    String finalAnswer
) {
}
