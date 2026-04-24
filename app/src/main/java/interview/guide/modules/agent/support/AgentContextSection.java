package interview.guide.modules.agent.support;

/**
 * 上下文装配后的一段稳定快照。
 *
 * @param key 分段键
 * @param label 分段展示名
 * @param priority 分段优先级，数值越大越优先
 * @param content 最终保留的内容
 * @param status 分段状态
 * @param reason 分段的装配原因或裁剪原因
 * @param originalLength 裁剪前内容长度
 * @param includedLength 裁剪后内容长度
 */
public record AgentContextSection(
    String key,
    String label,
    int priority,
    String content,
    AgentContextSectionStatus status,
    String reason,
    int originalLength,
    int includedLength
) {
}
