package interview.guide.modules.agent.model;

import java.util.List;

/**
 * Agent 对话响应。
 */
public record AgentChatResponse(
    String sessionId,
    String reply,
    AgentMemorySnapshot memory,
    List<AgentTraceDTO> traceSteps,
    List<AgentMessageDTO> messages
) {
}
