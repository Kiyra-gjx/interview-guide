package interview.guide.modules.agent.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Agent 会话消息。
 */
@Entity
@Table(
    name = "agent_messages",
    indexes = {
        @Index(name = "idx_agent_message_session", columnList = "session_id"),
        @Index(name = "idx_agent_message_order", columnList = "session_id, message_order"),
        @Index(name = "idx_agent_message_turn", columnList = "turn_id")
    },
    uniqueConstraints = @UniqueConstraint(name = "uk_agent_message_session_order", columnNames = {"session_id", "message_order"})
)
@Getter
@Setter
@NoArgsConstructor
public class AgentMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AgentSessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id")
    private AgentTurnEntity turn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "message_order", nullable = false)
    private Integer messageOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum MessageRole {
        USER,
        ASSISTANT
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public String getRoleString() {
        return role.name().toLowerCase();
    }
}
