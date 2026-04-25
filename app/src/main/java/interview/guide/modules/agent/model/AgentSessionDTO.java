package interview.guide.modules.agent.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 会话元数据 DTO。
 * Stage 4 工作台只消费会话级基础信息，消息展示统一通过 turn/detail 读模型提供。
 */
public record AgentSessionDTO(
    String sessionId,
    String title,
    String goal,
    Long resumeId,
    List<Long> knowledgeBaseIds,
    AgentExecutionState status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
