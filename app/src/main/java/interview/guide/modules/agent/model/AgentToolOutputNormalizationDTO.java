package interview.guide.modules.agent.model;

/**
 * Tool 输出归一化元数据。
 */
public record AgentToolOutputNormalizationDTO(
    boolean summaryTruncated,
    boolean answerTruncated,
    boolean debugTruncated,
    boolean factsTruncated
) {
}
