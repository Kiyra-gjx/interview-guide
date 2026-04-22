package interview.guide.modules.agent.model;

import interview.guide.modules.agent.guardrail.AgentGuardrailResult;

import java.util.List;
import java.time.LocalDateTime;

/**
 * Agent 执行轨迹 DTO。
 */
public record AgentTraceDTO(
    Integer stepIndex,
    String decisionSummary,
    String selectedTool,
    String toolInputJson,
    String toolOutputJson,
    String observationSummary,
    AgentMemorySnapshot memoryBefore,
    AgentMemorySnapshot memoryAfter,
    List<AgentGuardrailResult> guardrailResults,
    AgentExecutionState status,
    String errorMessage,
    LocalDateTime createdAt
) {

    public AgentTraceDTO {
        guardrailResults = guardrailResults == null ? List.of() : List.copyOf(guardrailResults);
    }
}
