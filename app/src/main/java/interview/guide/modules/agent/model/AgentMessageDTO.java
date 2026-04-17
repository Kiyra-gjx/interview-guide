package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/**
 * Agent 消息 DTO。
 */
public record AgentMessageDTO(
    String role,
    String content,
    Integer messageOrder,
    LocalDateTime createdAt
) {
}
