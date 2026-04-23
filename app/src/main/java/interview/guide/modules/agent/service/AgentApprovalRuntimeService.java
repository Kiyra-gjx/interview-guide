package interview.guide.modules.agent.service;

import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 审批挂起阶段的本地持久化协调器。
 * 只负责把 trace、approval、turn 一次性推进到等待审批状态，
 * 不把远程模型调用或工具执行包进数据库事务。
 */
@Service
@RequiredArgsConstructor
public class AgentApprovalRuntimeService {

    private final AgentTraceService traceService;
    private final AgentApprovalService approvalService;
    private final AgentSessionService sessionService;

    /**
     * 将当前 turn 挂起到等待审批状态，并保证本地状态一次性落库。
     * 调用完成后，trace、approval、turn 会同时处于 WAITING_APPROVAL 语义下。
     */
    @Transactional
    public PendingApprovalTransition parkTurnForApproval(ParkTurnForApprovalRequest request) {
        // 1. 先创建一条 RUNNING 的工具 trace，确保后续审批链路有唯一归属。
        AgentStepTraceEntity trace = traceService.startToolStep(
            request.turnId(),
            request.decisionSummary(),
            request.selectedTool(),
            request.toolInput(),
            request.memory()
        );

        // 2. 提取本次审批最主要的 guardrail 原因，写入审批记录用于解释与展示。
        AgentGuardrailResult primaryGuardrail = request.guardrailResults().isEmpty()
            ? null
            : request.guardrailResults().getFirst();
        AgentApprovalDTO approval = approvalService.createPendingApproval(
            new AgentApprovalService.CreateApprovalRequest(
                request.session(),
                trace.getTurn(),
                trace,
                request.selectedTool(),
                request.riskLevel(),
                request.toolInput(),
                request.latestUserMessage(),
                request.decisionSummary(),
                primaryGuardrail == null ? null : primaryGuardrail.reason()
            )
        );

        // 3. 用同一条 trace 记录“工具已停在等待审批状态”。
        traceService.markToolStepWaitingApproval(
            trace,
            approval,
            request.reply(),
            request.memory(),
            request.guardrailResults()
        );

        // 4. 最后推进 turn 状态并补一条 assistant 回复，让会话层也进入等待审批。
        AgentTurnEntity waitingTurn = sessionService.waitForApproval(
            request.turnId(),
            request.reply(),
            approval.expiresAt(),
            AgentCompletionMode.WAITING_APPROVAL
        );
        return new PendingApprovalTransition(approval, waitingTurn);
    }

    public record ParkTurnForApprovalRequest(
        String turnId,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        String decisionSummary,
        String selectedTool,
        AgentToolRiskLevel riskLevel,
        Map<String, Object> toolInput,
        String reply,
        List<AgentGuardrailResult> guardrailResults
    ) {
        /**
         * 统一收敛空集合，避免调用方在审批挂起链路上重复做 defensive copy。
         */
        public ParkTurnForApprovalRequest {
            toolInput = toolInput == null ? Map.of() : Map.copyOf(toolInput);
            guardrailResults = guardrailResults == null ? List.of() : List.copyOf(guardrailResults);
        }
    }

    /**
     * 挂起审批后的本地持久化结果。
     * `approval` 用于返回给前端，`persistedTurn` 用于复用已落库的等待审批 turn。
     */
    public record PendingApprovalTransition(AgentApprovalDTO approval, AgentTurnEntity persistedTurn) {
    }
}
