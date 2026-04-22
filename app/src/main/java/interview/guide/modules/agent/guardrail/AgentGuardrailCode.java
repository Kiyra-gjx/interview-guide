package interview.guide.modules.agent.guardrail;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Guardrail 规则编码。
 */
@Getter
@RequiredArgsConstructor
public enum AgentGuardrailCode {
    INPUT_INTERNAL_DATA_REQUEST(AgentGuardrailStage.INPUT),
    INPUT_MESSAGE_TOO_LONG(AgentGuardrailStage.INPUT),
    INPUT_CONTROL_CHARACTERS(AgentGuardrailStage.INPUT),
    TOOL_REQUIRES_APPROVAL(AgentGuardrailStage.TOOL),
    TOOL_UNEXPECTED_INPUT(AgentGuardrailStage.TOOL),
    TOOL_MISSING_REQUIRED_INPUT(AgentGuardrailStage.TOOL),
    OUTPUT_EMPTY_REPLY(AgentGuardrailStage.OUTPUT),
    OUTPUT_RAW_JSON_REPLY(AgentGuardrailStage.OUTPUT),
    OUTPUT_SENSITIVE_FIELD_LEAK(AgentGuardrailStage.OUTPUT);

    private final AgentGuardrailStage stage;
}
