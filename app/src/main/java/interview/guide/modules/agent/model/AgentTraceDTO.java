package interview.guide.modules.agent.model;

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
    AgentExecutionState status,
    String errorMessage,
    LocalDateTime createdAt
) {
}
