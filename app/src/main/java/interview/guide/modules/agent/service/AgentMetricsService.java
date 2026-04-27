package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentExecutionSummaryDTO;
import interview.guide.modules.agent.model.AgentLoopStopReason;
import interview.guide.modules.agent.model.AgentTerminalState;
import io.micrometer.core.instrument.DistributionSummary;
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
    private static final String EXECUTION_SUMMARY_METRIC = "agent.execution.summary";
    private static final String EXECUTION_STEPS_METRIC = "agent.execution.steps";
    private static final String EXECUTION_BUDGET_EXHAUSTED_METRIC = "agent.execution.budget.exhausted";

    private final MeterRegistry meterRegistry;

    public Timer.Sample startTurnLatency() {
        return Timer.start(meterRegistry);
    }

    public void recordTurnStarted() {
        meterRegistry.counter(TURN_TOTAL_METRIC).increment();
    }

    public void recordTurnCompleted(AgentCompletionMode completionMode) {
        String outcome = switch (completionMode) {
            case DEGRADED -> "degraded";
            case WAITING_APPROVAL -> "waiting_approval";
            case SUCCESS -> "success";
        };
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

    /**
     * 记录本轮执行摘要，补充多步预算相关观测维度。
     */
    public void recordExecutionSummary(AgentExecutionSummaryDTO executionSummary) {
        if (executionSummary == null) {
            return;
        }
        meterRegistry.counter(
            EXECUTION_SUMMARY_METRIC,
            "multiStep", Boolean.toString(executionSummary.multiStepEnabled()),
            "stopReason", normalizeStopReason(executionSummary.stopReason()),
            "budgetStopReason", normalizeBudgetStopReason(executionSummary.budgetStopReason()),
            "terminalState", normalizeTerminalState(executionSummary.terminalState())
        ).increment();

        DistributionSummary.builder(EXECUTION_STEPS_METRIC)
            .description("Executed step count of completed agent turns")
            .register(meterRegistry)
            .record(executionSummary.executedSteps());

        // exhausted 指标只统计“本轮确实因为预算边界而停下”的情况，
        // 不把正常收口但恰好打满预算的场景混进来。
        String exhaustedBudget = resolveExhaustedBudget(executionSummary.stopReason());
        if (exhaustedBudget != null) {
            meterRegistry.counter(EXECUTION_BUDGET_EXHAUSTED_METRIC, "budget", exhaustedBudget).increment();
        }
    }

    private String normalizeToolName(String toolName) {
        return toolName == null || toolName.isBlank() ? "unknown_tool" : toolName.trim();
    }

    private String normalizeStopReason(AgentLoopStopReason stopReason) {
        return stopReason == null ? "UNKNOWN" : stopReason.name();
    }

    private String normalizeBudgetStopReason(AgentLoopStopReason budgetStopReason) {
        return budgetStopReason == null ? "NONE" : budgetStopReason.name();
    }

    private String normalizeTerminalState(AgentTerminalState terminalState) {
        return terminalState == null ? "UNKNOWN" : terminalState.name();
    }

    private String resolveExhaustedBudget(AgentLoopStopReason stopReason) {
        if (stopReason == AgentLoopStopReason.STEP_BUDGET_EXHAUSTED) {
            return "step";
        }
        if (stopReason == AgentLoopStopReason.TIME_BUDGET_EXHAUSTED) {
            return "time";
        }
        if (stopReason == AgentLoopStopReason.TOKEN_BUDGET_EXHAUSTED) {
            return "token";
        }
        return null;
    }
}
