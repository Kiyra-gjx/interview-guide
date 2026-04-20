package interview.guide.modules.agent.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 会话实体。
 */
@Entity
@Table(name = "agent_sessions", indexes = {
    @Index(name = "idx_agent_session_session", columnList = "sessionId", unique = true),
    @Index(name = "idx_agent_session_updated", columnList = "updatedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String sessionId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String goal;

    private Long resumeId;

    @Column(columnDefinition = "TEXT")
    private String knowledgeBaseIdsJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AgentExecutionState status = AgentExecutionState.CREATED;

    @Column(columnDefinition = "TEXT")
    private String memoryJson;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("messageOrder ASC")
    private List<AgentMessageEntity> messages = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepIndex ASC")
    private List<AgentStepTraceEntity> traces = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<AgentTurnEntity> turns = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
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
