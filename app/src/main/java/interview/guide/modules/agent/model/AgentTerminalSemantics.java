package interview.guide.modules.agent.model;

/**
 * Agent 统一终态语义描述。
 * 用来把 completionMode、stopReason 映射成稳定的终态、是否可恢复以及恢复提示。
 */
public record AgentTerminalSemantics(
    AgentTerminalState terminalState,
    boolean recoverable,
    String recoveryHint
) {

    public AgentTerminalSemantics {
        recoveryHint = recoveryHint == null || recoveryHint.isBlank() ? null : recoveryHint.trim();
    }

    /**
     * 根据当前收口模式和停止原因推导统一终态语义。
     */
    public static AgentTerminalSemantics from(
        AgentCompletionMode completionMode,
        AgentLoopStopReason stopReason
    ) {
        return from(completionMode, stopReason, null);
    }

    /**
     * 在默认语义之上允许调用方覆盖恢复提示。
     */
    public static AgentTerminalSemantics from(
        AgentCompletionMode completionMode,
        AgentLoopStopReason stopReason,
        String recoveryHintOverride
    ) {
        AgentTerminalState terminalState = resolveTerminalState(completionMode, stopReason);
        boolean recoverable = terminalState == AgentTerminalState.WAITING_APPROVAL;
        String recoveryHint = recoveryHintOverride == null || recoveryHintOverride.isBlank()
            ? defaultRecoveryHint(terminalState, stopReason)
            : recoveryHintOverride.trim();
        return new AgentTerminalSemantics(terminalState, recoverable, recoveryHint);
    }

    private static AgentTerminalState resolveTerminalState(
        AgentCompletionMode completionMode,
        AgentLoopStopReason stopReason
    ) {
        if (stopReason == AgentLoopStopReason.UNHANDLED_ERROR) {
            return AgentTerminalState.FAILED;
        }
        if (stopReason == AgentLoopStopReason.STEP_BUDGET_EXHAUSTED
            || stopReason == AgentLoopStopReason.TIME_BUDGET_EXHAUSTED
            || stopReason == AgentLoopStopReason.TOKEN_BUDGET_EXHAUSTED) {
            return AgentTerminalState.EXHAUSTED;
        }
        if (completionMode == AgentCompletionMode.WAITING_APPROVAL
            || stopReason == AgentLoopStopReason.PENDING_APPROVAL) {
            return AgentTerminalState.WAITING_APPROVAL;
        }
        if (completionMode == AgentCompletionMode.SUCCESS) {
            return AgentTerminalState.SUCCESS;
        }
        return AgentTerminalState.DEGRADED;
    }

    private static String defaultRecoveryHint(
        AgentTerminalState terminalState,
        AgentLoopStopReason stopReason
    ) {
        if (terminalState == AgentTerminalState.WAITING_APPROVAL) {
            return "审批通过后当前 turn 可继续执行；拒绝或过期会终止本轮。";
        }
        if (terminalState == AgentTerminalState.EXHAUSTED) {
            return "当前 turn 已按预算边界停止；如需继续，请发起新一轮请求。";
        }
        if (terminalState == AgentTerminalState.FAILED) {
            return "当前 turn 已失败；请先检查错误原因，再重新发起新一轮请求。";
        }
        if (stopReason == AgentLoopStopReason.INPUT_GUARDRAIL_BLOCKED) {
            return "当前请求已被安全策略拦截；请调整请求后重新发起。";
        }
        if (stopReason == AgentLoopStopReason.APPROVAL_REJECTED) {
            return "当前高风险动作已被拒绝；如需继续，请修改请求后重新发起。";
        }
        if (stopReason == AgentLoopStopReason.APPROVAL_EXPIRED) {
            return "当前审批已过期；如需继续，请重新发起新一轮请求。";
        }
        if (terminalState == AgentTerminalState.DEGRADED) {
            return "当前 turn 已降级收口；建议先查看 trace 原因，再决定是否重新发起。";
        }
        return null;
    }
}
