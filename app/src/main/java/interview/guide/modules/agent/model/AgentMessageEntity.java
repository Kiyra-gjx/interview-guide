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
@Table(name = "agent_messages", indexes = {
    @Index(name = "idx_agent_message_session", columnList = "session_id"),
    @Index(name = "idx_agent_message_order", columnList = "session_id, messageOrder")
})
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private Integer messageOrder;

    @Column(nullable = false, updatable = false)
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
