package interview.guide.modules.agent.guardrail;

/**
 * 单次 Guardrail 命中结果。
 */
public record AgentGuardrailResult(
    AgentGuardrailStage stage,
    AgentGuardrailCode code,
    AgentGuardrailAction action,
    AgentGuardrailResolution resolution,
    String reason
) {

    public AgentGuardrailResult {
        if (code != null) {
            stage = code.getStage();
        }
        reason = normalize(reason);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
