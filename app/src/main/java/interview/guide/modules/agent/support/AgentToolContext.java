package interview.guide.modules.agent.support;

import interview.guide.modules.agent.model.AgentMemorySnapshot;

import java.util.List;

/**
 * Tool 执行上下文。
 */
public record AgentToolContext(
    String sessionId,
    Long resumeId,
    List<Long> knowledgeBaseIds,
    AgentMemorySnapshot memorySnapshot,
    String latestUserMessage,
    AgentAssembledContext assembledContext
) {

    /**
     * 兼容旧调用方的构造方式。
     *
     * @param sessionId 当前会话 ID
     * @param resumeId 会话绑定的简历 ID
     * @param knowledgeBaseIds 会话绑定的知识库 ID
     * @param memorySnapshot 当前记忆快照
     * @param latestUserMessage 最新用户消息
     */
    public AgentToolContext(
        String sessionId,
        Long resumeId,
        List<Long> knowledgeBaseIds,
        AgentMemorySnapshot memorySnapshot,
        String latestUserMessage
    ) {
        this(sessionId, resumeId, knowledgeBaseIds, memorySnapshot, latestUserMessage, null);
    }

    /**
     * 基于统一装配结果构造 Tool 上下文。
     *
     * @param assembledContext 已装配的上下文快照
     */
    public AgentToolContext(AgentAssembledContext assembledContext) {
        this(
            assembledContext == null ? null : assembledContext.sessionId(),
            assembledContext == null ? null : assembledContext.resumeId(),
            assembledContext == null ? List.of() : assembledContext.knowledgeBaseIds(),
            assembledContext == null ? null : assembledContext.memorySnapshot(),
            assembledContext == null ? null : assembledContext.latestUserMessage(),
            assembledContext
        );
    }
}
