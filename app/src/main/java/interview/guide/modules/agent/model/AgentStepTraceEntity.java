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
@Table(
    name = "agent_step_traces",
    indexes = {
        @Index(name = "idx_agent_trace_session", columnList = "session_id"),
        @Index(name = "idx_agent_trace_session_step", columnList = "session_id, step_index"),
        @Index(name = "idx_agent_trace_turn", columnList = "turn_id")
    },
    uniqueConstraints = @UniqueConstraint(name = "uk_agent_trace_session_step", columnNames = {"session_id", "step_index"})
)
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id")
    private AgentTurnEntity turn;

    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    @Column(name = "decision_summary", length = 500)
    private String decisionSummary;

    @Column(name = "selected_tool", length = 100)
    private String selectedTool;

    @Column(name = "tool_input_json", columnDefinition = "TEXT")
    private String toolInputJson;

    @Column(name = "tool_output_json", columnDefinition = "TEXT")
    private String toolOutputJson;

    @Column(name = "observation_summary", length = 500)
    private String observationSummary;

    @Column(name = "memory_before_json", columnDefinition = "TEXT")
    private String memoryBeforeJson;

    @Column(name = "memory_after_json", columnDefinition = "TEXT")
    private String memoryAfterJson;

    @Column(name = "guardrail_results_json", columnDefinition = "TEXT")
    private String guardrailResultsJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AgentExecutionState status = AgentExecutionState.CREATED;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
