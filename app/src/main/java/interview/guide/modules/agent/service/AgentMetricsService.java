package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentCompletionMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 核心指标采集。
 */
@Service
@RequiredArgsConstructor
public class AgentMetricsService {

    private static final String TURN_TOTAL_METRIC = "agent.turn.total";
    private static final String TURN_OUTCOME_METRIC = "agent.turn.outcome";
    private static final String TURN_RECLAIMED_METRIC = "agent.turn.reclaimed";
    private static final String TURN_LATENCY_METRIC = "agent.turn.latency";
    private static final String TOOL_EXECUTION_METRIC = "agent.tool.execution";

    private final MeterRegistry meterRegistry;

    public Timer.Sample startTurnLatency() {
        return Timer.start(meterRegistry);
    }

    public void recordTurnStarted() {
        meterRegistry.counter(TURN_TOTAL_METRIC).increment();
    }

    public void recordTurnCompleted(AgentCompletionMode completionMode) {
        String outcome = completionMode == AgentCompletionMode.DEGRADED ? "degraded" : "success";
        meterRegistry.counter(TURN_OUTCOME_METRIC, "outcome", outcome).increment();
    }

    public void recordTurnFailed() {
        meterRegistry.counter(TURN_OUTCOME_METRIC, "outcome", "failed").increment();
    }

    public void recordTurnReclaimed(int reclaimedCount) {
        if (reclaimedCount <= 0) {
            return;
        }
        meterRegistry.counter(TURN_RECLAIMED_METRIC, "reason", "lease_timeout").increment(reclaimedCount);
    }

    public void recordToolExecution(String toolName, boolean success) {
        meterRegistry.counter(
            TOOL_EXECUTION_METRIC,
            "tool", normalizeToolName(toolName),
            "outcome", success ? "success" : "failed"
        ).increment();
    }

    public void stopTurnLatency(Timer.Sample sample, String outcome) {
        if (sample == null) {
            return;
        }
        sample.stop(Timer.builder(TURN_LATENCY_METRIC)
            .description("Latency of completed agent turns")
            .publishPercentiles(0.95)
            .tag("outcome", outcome)
            .register(meterRegistry));
    }

    private String normalizeToolName(String toolName) {
        return toolName == null || toolName.isBlank() ? "unknown_tool" : toolName.trim();
    }
}
