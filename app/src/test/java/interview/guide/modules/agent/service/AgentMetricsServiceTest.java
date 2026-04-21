package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentCompletionMode;
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
}
