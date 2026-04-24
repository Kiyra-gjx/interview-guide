package interview.guide.modules.agent.support;

import interview.guide.modules.agent.model.AgentMemorySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统一装配后的 Agent 上下文快照。
 *
 * @param sessionId 当前会话 ID
 * @param userGoal 当前确定的用户目标
 * @param latestUserMessage 最新用户消息
 * @param resumeId 会话绑定的简历 ID
 * @param knowledgeBaseIds 会话绑定的知识库 ID
 * @param memorySnapshot 当前记忆快照
 * @param promptContextSummary 供 Prompt 直接消费的上下文摘要
 * @param budget 本次装配使用的预算信息
 * @param sections 装配后的分段明细
 */
public record AgentAssembledContext(
    String sessionId,
    String userGoal,
    String latestUserMessage,
    Long resumeId,
    List<Long> knowledgeBaseIds,
    AgentMemorySnapshot memorySnapshot,
    String promptContextSummary,
    AgentContextBudget budget,
    List<AgentContextSection> sections
) {

    /**
     * 规范化集合字段，避免调用方拿到可变引用。
     */
    public AgentAssembledContext {
        knowledgeBaseIds = knowledgeBaseIds == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(knowledgeBaseIds));
        sections = sections == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(sections));
    }
}
