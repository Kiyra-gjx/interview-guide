package interview.guide.modules.agent.model;

import java.util.List;

/**
 * Agent Memory 快照。
 */
public record AgentMemorySnapshot(
    String userGoal,
    String currentPhase,
    List<String> confirmedFacts,
    List<String> usedTools,
    String nextFocus
) {
}
