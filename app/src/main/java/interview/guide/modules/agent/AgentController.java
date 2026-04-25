package interview.guide.modules.agent;

import interview.guide.common.result.Result;
import interview.guide.modules.agent.model.*;
import interview.guide.modules.agent.service.AgentApprovalService;
import interview.guide.modules.agent.service.AgentMemoryService;
import interview.guide.modules.agent.service.AgentOrchestrator;
import interview.guide.modules.agent.service.AgentSessionService;
import interview.guide.modules.agent.service.AgentTraceService;
import interview.guide.modules.agent.service.AgentWorkbenchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 控制器。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AgentSessionService sessionService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentTraceService traceService;
    private final AgentMemoryService memoryService;
    private final AgentApprovalService approvalService;
    private final AgentWorkbenchService workbenchService;

    @PostMapping("/api/agent/sessions")
    public Result<AgentSessionDTO> createSession(@Valid @RequestBody CreateAgentSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    @GetMapping("/api/agent/sessions/{sessionId}")
    public Result<AgentSessionDTO> getSession(@PathVariable String sessionId) {
        return Result.success(sessionService.getSession(sessionId));
    }

    @PostMapping("/api/agent/sessions/{sessionId}/chat")
    public Result<AgentChatResponse> chat(
        @PathVariable String sessionId,
        @Valid @RequestBody AgentChatRequest request
    ) {
        log.info("收到 Agent chat 请求: sessionId={}", sessionId);
        return Result.success(agentOrchestrator.chat(sessionId, request));
    }

    @GetMapping("/api/agent/sessions/{sessionId}/trace")
    public Result<List<AgentTraceDTO>> getTrace(@PathVariable String sessionId) {
        return Result.success(traceService.getTrace(sessionId));
    }

    /**
     * 查询会话下的 turn 摘要列表。
     * 主要供工作台左侧 turn 时间线与状态总览使用。
     */
    @GetMapping("/api/agent/sessions/{sessionId}/turns")
    public Result<List<AgentTurnSummaryDTO>> getTurns(@PathVariable String sessionId) {
        return Result.success(workbenchService.getSessionTurns(sessionId));
    }

    /**
     * 查询单个 turn 的工作台明细。
     * 这里一次性返回消息、trace、审批与 guardrail 聚合结果，避免前端自行拼接多份 turn 数据。
     */
    @GetMapping("/api/agent/turns/{turnId}")
    public Result<AgentTurnDetailDTO> getTurnDetail(@PathVariable String turnId) {
        return Result.success(workbenchService.getTurnDetail(turnId));
    }

    @GetMapping("/api/agent/sessions/{sessionId}/memory")
    public Result<AgentMemorySnapshot> getMemory(@PathVariable String sessionId) {
        return Result.success(memoryService.readMemory(sessionService.getSessionEntity(sessionId)));
    }

    /**
     * 查询指定会话下的全部审批记录。
     * 主要供工作台刷新审批列表和历史状态展示使用。
     */
    @GetMapping("/api/agent/sessions/{sessionId}/approvals")
    public Result<List<AgentApprovalDTO>> getApprovals(@PathVariable String sessionId) {
        sessionService.getSessionEntity(sessionId);
        return Result.success(approvalService.getSessionApprovals(sessionId));
    }

    /**
     * 批准一条待审批动作，并触发后续恢复执行或结果恢复。
     */
    @PostMapping("/api/agent/approvals/{approvalId}/approve")
    public Result<AgentChatResponse> approveApproval(@PathVariable String approvalId) {
        return Result.success(agentOrchestrator.approveApproval(approvalId));
    }

    /**
     * 拒绝一条待审批动作，并把对应 turn 收敛到降级终态。
     */
    @PostMapping("/api/agent/approvals/{approvalId}/reject")
    public Result<AgentChatResponse> rejectApproval(@PathVariable String approvalId) {
        return Result.success(agentOrchestrator.rejectApproval(approvalId));
    }
}
