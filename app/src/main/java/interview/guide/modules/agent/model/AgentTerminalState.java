package interview.guide.modules.agent.model;

/**
 * Agent 当前 turn 的统一终态语义。
 * 该状态只回答“这一轮最终以什么语义收口”，不替代细粒度 stopReason。
 */
public enum AgentTerminalState {
    SUCCESS,
    DEGRADED,
    EXHAUSTED,
    WAITING_APPROVAL,
    FAILED
}
