package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Agent 对话请求。
 */
public record AgentChatRequest(
    @NotBlank(message = "message 不能为空")
    String message
) {
}
