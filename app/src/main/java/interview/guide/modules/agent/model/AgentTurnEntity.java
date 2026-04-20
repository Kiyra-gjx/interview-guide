package interview.guide.modules.agent.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 单轮执行实体。
 */
@Entity
@Table(
    name = "agent_turns",
    indexes = {
        @Index(name = "idx_agent_turn_session", columnList = "session_id"),
        @Index(name = "idx_agent_turn_session_status", columnList = "session_id, status"),
        @Index(name = "idx_agent_turn_lease", columnList = "lease_expires_at"),
        @Index(name = "idx_agent_turn_created", columnList = "created_at")
    },
    uniqueConstraints = @UniqueConstraint(name = "uk_agent_turn_turn_id", columnNames = "turn_id")
)
@Getter
@Setter
@NoArgsConstructor
public class AgentTurnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "turn_id", nullable = false, unique = true, length = 36)
    private String turnId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AgentSessionEntity session;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AgentTurnStatus status = AgentTurnStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_mode", length = 20)
    private AgentCompletionMode completionMode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @OneToMany(mappedBy = "turn")
    @OrderBy("messageOrder ASC")
    private List<AgentMessageEntity> messages = new ArrayList<>();

    @OneToMany(mappedBy = "turn")
    @OrderBy("stepIndex ASC")
    private List<AgentStepTraceEntity> traces = new ArrayList<>();

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
