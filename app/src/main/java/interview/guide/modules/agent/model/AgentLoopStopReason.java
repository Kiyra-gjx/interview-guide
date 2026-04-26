package interview.guide.modules.agent.model;

/**
 * Agent 执行停止原因。
 * 用于表达单步/多步执行最终是如何收口的。
 */
public enum AgentLoopStopReason {
    INPUT_GUARDRAIL_BLOCKED,
    DIRECT_REPLY,
    DEGRADED_REPLY,
    TOOL_COMPLETED_SINGLE_STEP,
    PENDING_APPROVAL,
    STEP_BUDGET_EXHAUSTED,
    TIME_BUDGET_EXHAUSTED,
    TOKEN_BUDGET_EXHAUSTED,
    TOOL_EXECUTION_FAILED,
    TOOL_POST_PROCESSING_FAILED
}
