package interview.guide.modules.agent.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Agent 单步执行轨迹。
 */
@Entity
@Table(name = "agent_step_traces", indexes = {
    @Index(name = "idx_agent_trace_session", columnList = "session_id"),
    @Index(name = "idx_agent_trace_session_step", columnList = "session_id, stepIndex")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentStepTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AgentSessionEntity session;

    @Column(nullable = false)
    private Integer stepIndex;

    @Column(length = 500)
    private String decisionSummary;

    @Column(length = 100)
    private String selectedTool;

    @Column(columnDefinition = "TEXT")
    private String toolInputJson;

    @Column(columnDefinition = "TEXT")
    private String toolOutputJson;

    @Column(length = 500)
    private String observationSummary;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AgentExecutionState status = AgentExecutionState.CREATED;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
