package interview.guide.modules.agent.support;

/**
 * 上下文装配使用的预算信息。
 *
 * @param totalChars 总预算
 * @param usedChars 已使用字符数
 * @param remainingChars 剩余字符数
 */
public record AgentContextBudget(
    int totalChars,
    int usedChars,
    int remainingChars
) {
}
