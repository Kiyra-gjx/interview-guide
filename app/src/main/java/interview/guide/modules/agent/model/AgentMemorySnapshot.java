package interview.guide.modules.agent.model;

import java.util.ArrayList;
import java.util.Collections;
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

    public AgentMemorySnapshot {
        confirmedFacts = confirmedFacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(confirmedFacts));
        usedTools = usedTools == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(usedTools));
    }
}
