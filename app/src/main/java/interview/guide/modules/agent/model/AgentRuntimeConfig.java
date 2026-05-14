package interview.guide.modules.agent.model;

/**
 * Agent 运行时配置。
 * 目前主要用于受控多步执行的预算开关与上限声明。
 */
public record AgentRuntimeConfig(
    Boolean multiStepEnabled,
    Integer maxSteps,
    Long maxDurationMillis,
    Integer maxEstimatedModelTokens,
    String preferredProviderId
) {
}
