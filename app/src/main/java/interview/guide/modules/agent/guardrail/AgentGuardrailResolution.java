package interview.guide.modules.agent.guardrail;

/**
 * Guardrail 命中后的收口方式。
 */
public enum AgentGuardrailResolution {
    RETURN_SAFE_REPLY,
    BLOCK_TOOL_CALL,
    REPLACE_WITH_FALLBACK_REPLY,
    WAIT_FOR_APPROVAL
}
