package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建 Agent 会话请求。
 */
public record CreateAgentSessionRequest(
    String title,
    @NotBlank(message = "goal 不能为空")
    String goal,
    Long resumeId,
    List<Long> knowledgeBaseIds,
    String preferredProviderId
) {
}
