package interview.guide.modules.agent.model;

/**
 * Agent turn 生命周期状态。
 */
public enum AgentTurnStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    ABORTED
}
