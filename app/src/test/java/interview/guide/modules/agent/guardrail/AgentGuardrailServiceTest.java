package interview.guide.modules.agent.guardrail;

import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.AgentTool;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGuardrailServiceTest {

    private final AgentGuardrailService guardrailService = new AgentGuardrailService();

    @Test
    @DisplayName("should allow normal security explanations without degrading output")
    void shouldAllowNormalSecurityExplanationsWithoutDegradingOutput() {
        AgentGuardrailService.OutputGuardrailDecision decision = guardrailService.evaluateOutput(
            "Bearer Token 通常放在 Authorization 请求头里，格式是 Authorization: Bearer <token>。",
            "fallback"
        );

        assertThat(decision.degraded()).isFalse();
        assertThat(decision.reply()).contains("Authorization").contains("Bearer");
        assertThat(decision.guardrailResults()).isEmpty();
    }

    @Test
    @DisplayName("should block unexpected tool inputs even when the unexpected value is null")
    void shouldBlockUnexpectedToolInputsEvenWhenUnexpectedValueIsNull() {
        Map<String, Object> toolInput = new LinkedHashMap<>();
        toolInput.put("resumeId", 42L);
        toolInput.put("foo", null);

        AgentGuardrailService.ToolGuardrailDecision decision = guardrailService.evaluateTool(
            readOnlyTool(List.of("resumeId")),
            toolInput
        );

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.result().code()).isEqualTo(AgentGuardrailCode.TOOL_UNEXPECTED_INPUT);
        assertThat(decision.result().reason()).contains("foo");
        assertThat(decision.toolInput()).containsEntry("resumeId", 42L).containsKey("foo");
        assertThat(decision.toolInput().get("foo")).isNull();
    }

    @Test
    @DisplayName("should leave undeclared risk tools to the approval policy instead of blocking in guardrail")
    void shouldLeaveUndeclaredRiskToolsToApprovalPolicy() {
        AgentGuardrailService.ToolGuardrailDecision decision = guardrailService.evaluateTool(
            new AgentTool() {
                @Override
                public String name() {
                    return "undeclared_risk_tool";
                }

                @Override
                public String description() {
                    return "tool without explicit risk declaration";
                }

                @Override
                public List<String> requiredInputs() {
                    return List.of("resumeId");
                }

                @Override
                public AgentToolRiskLevel riskLevel() {
                    return null;
                }

                @Override
                public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
                    return new AgentToolResult("summary", Map.of(), Map.of(), List.of());
                }
            },
            Map.of("resumeId", 42L)
        );

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.guardrailResults()).isEmpty();
    }

    private AgentTool readOnlyTool(List<String> requiredInputs) {
        return new AgentTool() {
            @Override
            public String name() {
                return "read_only_tool";
            }

            @Override
            public String description() {
                return "read only tool";
            }

            @Override
            public List<String> requiredInputs() {
                return requiredInputs;
            }

            @Override
            public AgentToolRiskLevel riskLevel() {
                return AgentToolRiskLevel.READ_ONLY;
            }

            @Override
            public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
                return new AgentToolResult("summary", Map.of(), Map.of(), List.of());
            }
        };
    }
}
