package interview.guide.modules.agent.tool;

import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;

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

    AgentToolResult execute(Map<String, Object> input, AgentToolContext context);
}
