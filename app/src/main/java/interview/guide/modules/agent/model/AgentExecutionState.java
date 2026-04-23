package interview.guide.modules.agent.model;

/**
 * Agent 当前执行状态。
 */
public enum AgentExecutionState {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED
}
