package interview.guide.modules.agent.model;

import interview.guide.modules.agent.guardrail.AgentGuardrailResult;

import java.util.List;

/**
 * Agent Workbench 中单个 turn 的完整只读明细。
 * 这里把 turn 摘要、消息、trace、审批与聚合后的 guardrail 结果一次性收口，便于前端按需展示。
 */
public record AgentTurnDetailDTO(
    AgentTurnSummaryDTO turn,
    List<AgentMessageDTO> messages,
    List<AgentTraceDTO> traceSteps,
    List<AgentApprovalDTO> approvals,
    List<AgentGuardrailResult> guardrailResults
) {

    public AgentTurnDetailDTO {
        // 统一把可空集合投影成只读空列表，避免前端再做重复空值防御。
        messages = messages == null ? List.of() : List.copyOf(messages);
        traceSteps = traceSteps == null ? List.of() : List.copyOf(traceSteps);
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        guardrailResults = guardrailResults == null ? List.of() : List.copyOf(guardrailResults);
    }
}
