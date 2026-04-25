package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/**
 * Agent Workbench 使用的 turn 摘要视图。
 * 只暴露列表渲染与状态识别所需的最小信息，避免把整条 trace 全量塞进列表接口。
 */
public record AgentTurnSummaryDTO(
    String turnId,
    AgentTurnStatus status,
    AgentCompletionMode completionMode,
    String userMessagePreview,
    String assistantReplyPreview,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt
) {
}
