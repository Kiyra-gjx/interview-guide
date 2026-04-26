package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentExecutionSummaryDTO;
import interview.guide.modules.agent.model.AgentLoopStopReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsServiceTest {

    @Test
    @DisplayName("should record core turn and tool metrics")
    void shouldRecordCoreTurnAndToolMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentMetricsService metricsService = new AgentMetricsService(meterRegistry);
        Timer.Sample sample = metricsService.startTurnLatency();

        metricsService.recordTurnStarted();
        metricsService.recordTurnCompleted(AgentCompletionMode.SUCCESS);
        metricsService.recordTurnFailed();
        metricsService.recordTurnReclaimed(2);
        metricsService.recordToolExecution("get_resume_profile", true);
        metricsService.stopTurnLatency(sample, "success");

        assertThat(meterRegistry.get("agent.turn.total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agent.turn.outcome").tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agent.turn.outcome").tag("outcome", "failed").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agent.turn.reclaimed").tag("reason", "lease_timeout").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("agent.tool.execution").tag("tool", "get_resume_profile").tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agent.turn.latency").tag("outcome", "success").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should record bounded loop metrics with stop reason and executed step count")
    void shouldRecordBoundedLoopMetricsWithStopReasonAndExecutedStepCount() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentMetricsService metricsService = new AgentMetricsService(meterRegistry);

        metricsService.recordExecutionSummary(new AgentExecutionSummaryDTO(
            true,
            3,
            2,
            1,
            15_000L,
            1_200L,
            13_800L,
            4_000,
            1_280,
            2_720,
            AgentLoopStopReason.DIRECT_REPLY
        ));
        metricsService.recordExecutionSummary(new AgentExecutionSummaryDTO(
            true,
            1,
            1,
            0,
            15_000L,
            1_500L,
            13_500L,
            4_000,
            1_600,
            2_400,
            AgentLoopStopReason.STEP_BUDGET_EXHAUSTED
        ));

        assertThat(meterRegistry.get("agent.execution.summary")
            .tag("multiStep", "true")
            .tag("stopReason", "DIRECT_REPLY")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agent.execution.summary")
            .tag("multiStep", "true")
            .tag("stopReason", "STEP_BUDGET_EXHAUSTED")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agent.execution.budget.exhausted")
            .tag("budget", "step")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agent.execution.steps").summary().count()).isEqualTo(2L);
    }
}
