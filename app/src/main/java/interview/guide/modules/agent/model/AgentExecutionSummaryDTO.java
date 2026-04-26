package interview.guide.modules.agent.model;

/**
 * Agent 执行摘要。
 * 对外暴露本轮是否启用了多步、预算消耗到哪里，以及最终为何停止。
 */
public record AgentExecutionSummaryDTO(
    boolean multiStepEnabled,
    int maxSteps,
    int executedSteps,
    int remainingSteps,
    long maxDurationMillis,
    long elapsedMillis,
    long remainingDurationMillis,
    int maxEstimatedModelTokens,
    int estimatedModelTokensUsed,
    int remainingEstimatedModelTokens,
    AgentLoopStopReason stopReason
) {

    public AgentExecutionSummaryDTO {
        maxSteps = Math.max(0, maxSteps);
        executedSteps = Math.max(0, executedSteps);
        remainingSteps = Math.max(0, remainingSteps);
        maxDurationMillis = Math.max(0L, maxDurationMillis);
        elapsedMillis = Math.max(0L, elapsedMillis);
        remainingDurationMillis = Math.max(0L, remainingDurationMillis);
        maxEstimatedModelTokens = Math.max(0, maxEstimatedModelTokens);
        estimatedModelTokensUsed = Math.max(0, estimatedModelTokensUsed);
        remainingEstimatedModelTokens = Math.max(0, remainingEstimatedModelTokens);
    }
}
