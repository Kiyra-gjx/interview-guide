package interview.guide.modules.agent.model;

import interview.guide.modules.agent.guardrail.AgentGuardrailResult;

import java.util.List;

/**
 * Agent 对话响应。
 */
public record AgentChatResponse(
    String sessionId,
    String turnId,
    AgentTurnStatus turnStatus,
    AgentCompletionMode completionMode,
    String reply,
    AgentMemorySnapshot memory,
    List<AgentTraceDTO> traceSteps,
    List<AgentGuardrailResult> guardrailResults,
    List<AgentMessageDTO> messagesDelta
) {

    public AgentChatResponse {
        traceSteps = traceSteps == null ? List.of() : List.copyOf(traceSteps);
        guardrailResults = guardrailResults == null ? List.of() : List.copyOf(guardrailResults);
        messagesDelta = messagesDelta == null ? List.of() : List.copyOf(messagesDelta);
    }
}
