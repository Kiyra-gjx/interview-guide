package interview.guide.modules.agent.model;

import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Agent 审批请求实体。
 */
@Entity
@Table(
    name = "agent_approvals",
    indexes = {
        @Index(name = "idx_agent_approval_session_status", columnList = "session_id, status"),
        @Index(name = "idx_agent_approval_turn", columnList = "turn_id"),
        @Index(name = "idx_agent_approval_expires_at", columnList = "expires_at")
    },
    uniqueConstraints = @UniqueConstraint(name = "uk_agent_approval_approval_id", columnNames = "approval_id")
)
@Getter
@Setter
@NoArgsConstructor
public class AgentApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_id", nullable = false, unique = true, length = 36)
    private String approvalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AgentSessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id", nullable = false)
    private AgentTurnEntity turn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trace_id", nullable = false)
    private AgentStepTraceEntity trace;

    @Column(name = "selected_tool", nullable = false, length = 100)
    private String selectedTool;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 32)
    private AgentToolRiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentApprovalStatus status = AgentApprovalStatus.PENDING;

    @Column(name = "decision_summary", length = 500)
    private String decisionSummary;

    @Column(name = "tool_input_json", columnDefinition = "TEXT")
    private String toolInputJson;

    @Column(name = "latest_user_message", columnDefinition = "TEXT")
    private String latestUserMessage;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
