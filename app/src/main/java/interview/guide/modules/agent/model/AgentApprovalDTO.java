package interview.guide.modules.agent.model;

import interview.guide.modules.agent.tool.AgentToolRiskLevel;

import java.time.LocalDateTime;

/**
 * 审批结果对外视图。
 */
public record AgentApprovalDTO(
    String approvalId,
    String sessionId,
    String turnId,
    String selectedTool,
    AgentToolRiskLevel riskLevel,
    AgentApprovalStatus status,
    String reason,
    LocalDateTime expiresAt,
    LocalDateTime decidedAt,
    LocalDateTime createdAt
) {
}
