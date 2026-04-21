package interview.guide.modules.agent.tool;

import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Test
    @DisplayName("should fail fast when duplicate tool names are registered")
    void shouldFailFastWhenDuplicateToolNamesAreRegistered() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
            tool("duplicate_tool", "tool-1"),
            tool("duplicate_tool", "tool-2")
        ));

        assertThatThrownBy(toolRegistry::init)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("duplicate_tool");
    }

    private AgentTool tool(String name, String description) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
                return new AgentToolResult(
                    "summary",
                    Map.of(),
                    Map.of(),
                    List.of()
                );
            }
        };
    }
}
