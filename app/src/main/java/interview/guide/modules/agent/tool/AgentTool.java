package interview.guide.modules.agent.tool;

import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Agent Tool 抽象。
 */
public interface AgentTool {

    String name();

    String description();

    default List<String> requiredInputs() {
        return List.of();
    }

    /**
     * 每个分组表示“至少提供一个”的输入约束。
     * 例如 [[sessionId, resumeId]] 表示 sessionId 和 resumeId 二选一。
     */
    default List<List<String>> requiredAnyOfInputs() {
        return List.of();
    }

    default List<String> allowedInputs() {
        LinkedHashSet<String> allowed = new LinkedHashSet<>(requiredInputs());
        List<List<String>> anyOfGroups = requiredAnyOfInputs();
        if (anyOfGroups != null) {
            for (List<String> group : anyOfGroups) {
                if (group != null) {
                    allowed.addAll(group);
                }
            }
        }
        return List.copyOf(allowed);
    }

    AgentToolRiskLevel riskLevel();

    AgentToolResult execute(Map<String, Object> input, AgentToolContext context);
}
