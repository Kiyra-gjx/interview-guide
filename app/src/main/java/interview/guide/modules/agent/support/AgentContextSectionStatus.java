package interview.guide.modules.agent.support;

/**
 * 单个上下文分段在装配后的状态。
 */
public enum AgentContextSectionStatus {

    /**
     * 分段完整保留。
     */
    INCLUDED,

    /**
     * 分段因预算限制被裁剪。
     */
    TRUNCATED,

    /**
     * 分段因空值或预算不足被省略。
     */
    OMITTED
}
