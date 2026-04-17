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

    public AgentMemorySnapshot createInitialSnapshot(String goal) {
        return new AgentMemorySnapshot(
            goal == null ? "" : goal.trim(),
            "goal_received",
            List.of(),
            List.of(),
            "优先补充简历或知识库上下文"
        );
    }

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

    public void writeMemory(AgentSessionEntity session, AgentMemorySnapshot snapshot) {
        try {
            session.setMemoryJson(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "写入 Agent Memory 失败");
        }
    }

    public AgentMemorySnapshot updateAfterTool(
        AgentMemorySnapshot current,
        String toolName,
        AgentToolResult result
    ) {
        LinkedHashSet<String> facts = new LinkedHashSet<>(safeList(current.confirmedFacts()));
        for (String fact : safeList(result.confirmedFacts())) {
            if (fact != null && !fact.isBlank()) {
                facts.add(fact);
            }
        }

        List<String> limitedFacts = new ArrayList<>(facts).stream()
            .limit(MAX_FACTS)
            .toList();

        LinkedHashSet<String> usedTools = new LinkedHashSet<>(safeList(current.usedTools()));
        usedTools.add(toolName);

        return new AgentMemorySnapshot(
            current.userGoal(),
            resolvePhase(toolName),
            limitedFacts,
            new ArrayList<>(usedTools),
            result.summary()
        );
    }

    private String resolvePhase(String toolName) {
        return switch (toolName) {
            case "get_resume_profile" -> "resume_context_ready";
            case "search_knowledge_base" -> "knowledge_context_ready";
            default -> "context_ready";
        };
    }

    private List<String> safeList(List<String> value) {
        return value == null ? List.of() : value;
    }
}
