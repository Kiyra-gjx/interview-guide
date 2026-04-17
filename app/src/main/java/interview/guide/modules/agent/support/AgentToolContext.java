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
    String latestUserMessage
) {
}
