package interview.guide.modules.agent.tool;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tool 注册中心。
 */
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<AgentTool> tools;
    private Map<String, AgentTool> toolMap;

    @PostConstruct
    void init() {
        Map<String, AgentTool> registry = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            registry.put(tool.name(), tool);
        }
        this.toolMap = Map.copyOf(registry);
    }

    public AgentTool getRequiredTool(String toolName) {
        AgentTool tool = toolMap.get(toolName);
        if (tool == null) {
            throw new BusinessException(ErrorCode.AGENT_TOOL_NOT_FOUND, "未找到 Tool: " + toolName);
        }
        return tool;
    }

    public Optional<AgentTool> findTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolMap.get(toolName.trim()));
    }

    public String describeTools() {
        return tools.stream()
            .map(tool -> "- " + tool.name() + ": " + tool.description())
            .collect(Collectors.joining("\n"));
    }
}
