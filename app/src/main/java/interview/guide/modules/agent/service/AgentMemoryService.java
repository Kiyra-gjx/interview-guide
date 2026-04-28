package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.support.AgentToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Agent Memory 服务。
 */
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    private static final int MAX_FACTS = 8;

    private final ObjectMapper objectMapper;

    /**
     * 创建初始 memory 快照。
     */
    public AgentMemorySnapshot createInitialSnapshot(String goal) {
        return new AgentMemorySnapshot(
            goal == null ? "" : goal.trim(),
            "goal_received",
            List.of(),
            List.of(),
            "优先补充简历或知识库上下文"
        );
    }

    /**
     * 读取当前会话的 memory 快照。
     */
    public AgentMemorySnapshot readMemory(AgentSessionEntity session) {
        if (session.getMemoryJson() == null || session.getMemoryJson().isBlank()) {
            return createInitialSnapshot(session.getGoal());
        }
        try {
            return objectMapper.readValue(session.getMemoryJson(), AgentMemorySnapshot.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "读取 Agent Memory 失败");
        }
    }

    /**
     * 把 memory 快照写回到会话实体。
     */
    public void writeMemory(AgentSessionEntity session, AgentMemorySnapshot snapshot) {
        try {
            session.setMemoryJson(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "写入 Agent Memory 失败");
        }
    }

    /**
     * 在工具执行完成后更新 memory 快照。
     */
    public AgentMemorySnapshot updateAfterTool(
        AgentMemorySnapshot current,
        String toolName,
        AgentToolResult result
    ) {
        AgentToolResult.MemoryProjection currentProjection = AgentToolResult.memoryProjection(
            current.nextFocus(),
            current.confirmedFacts(),
            MAX_FACTS
        );
        AgentToolResult.MemoryProjection toolProjection = result.memoryProjection();

        // .1 先把已有 memory 和新 tool result 都压到同一套 summary / facts 契约下，再做合并。
        LinkedHashSet<String> facts = new LinkedHashSet<>(safeList(currentProjection.facts()));
        for (String fact : safeList(toolProjection.facts())) {
            if (fact != null && !fact.isBlank()) {
                facts.add(fact);
            }
        }

        // .2 memory 仍保留自己的总量上限，但单条事实和单次 tool 写回都已经过统一归一化。
        List<String> limitedFacts = new ArrayList<>(facts).stream()
            .limit(MAX_FACTS)
            .toList();

        LinkedHashSet<String> usedTools = new LinkedHashSet<>(safeList(current.usedTools()));
        usedTools.add(toolName);
        String nextFocus = resolveNextFocus(toolProjection.summary(), result.answerPayload());

        return new AgentMemorySnapshot(
            current.userGoal(),
            resolvePhase(toolName),
            limitedFacts,
            new ArrayList<>(usedTools),
            nextFocus
        );
    }

    /**
     * 根据工具名推进 memory 阶段。
     */
    private String resolvePhase(String toolName) {
        return switch (toolName) {
            case "get_resume_profile" -> "resume_context_ready";
            case "search_knowledge_base" -> "knowledge_context_ready";
            case "get_interview_history_summary" -> "interview_history_ready";
            case "analyze_interview_gaps" -> "interview_gap_ready";
            case "suggest_follow_up_questions" -> "follow_up_ready";
            case "subagent_handoff" -> "delegated_context_ready";
            default -> "context_ready";
        };
    }

    /**
     * 兜底空列表，避免重复判空。
     */
    private String resolveNextFocus(String defaultNextFocus, java.util.Map<String, Object> answerPayload) {
        if (answerPayload == null || answerPayload.isEmpty()) {
            return defaultNextFocus;
        }
        Object explicitNextFocus = answerPayload.get("nextFocus");
        if (explicitNextFocus instanceof String nextFocusText && !nextFocusText.isBlank()) {
            return nextFocusText.trim();
        }
        return defaultNextFocus;
    }

    private List<String> safeList(List<String> value) {
        return value == null ? List.of() : value;
    }
}
