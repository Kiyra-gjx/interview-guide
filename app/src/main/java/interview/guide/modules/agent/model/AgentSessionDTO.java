package interview.guide.modules.agent.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 会话 DTO。
 */
public record AgentSessionDTO(
    String sessionId,
    String title,
    String goal,
    Long resumeId,
    List<Long> knowledgeBaseIds,
    AgentExecutionState status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<AgentMessageDTO> messages
) {
}
