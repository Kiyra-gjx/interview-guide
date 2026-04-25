package interview.guide.modules.agent;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import interview.guide.modules.agent.model.AgentTurnDetailDTO;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.model.AgentTurnSummaryDTO;
import interview.guide.modules.agent.service.AgentMemoryService;
import interview.guide.modules.agent.service.AgentApprovalService;
import interview.guide.modules.agent.service.AgentOrchestrator;
import interview.guide.modules.agent.service.AgentSessionService;
import interview.guide.modules.agent.service.AgentTraceService;
import interview.guide.modules.agent.service.AgentWorkbenchService;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    private AgentSessionService sessionService;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private AgentTraceService traceService;
    @Mock
    private AgentMemoryService memoryService;
    @Mock
    private AgentApprovalService approvalService;
    @Mock
    private AgentWorkbenchService workbenchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController(
            sessionService,
            agentOrchestrator,
            traceService,
            memoryService,
            approvalService,
            workbenchService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("should return http 409 when /chat hits a turn conflict")
    void shouldReturnConflictForChatTurnConflict() throws Exception {
        String sessionId = "session-conflict";
        when(agentOrchestrator.chat(eq(sessionId), any()))
            .thenThrow(new BusinessException(ErrorCode.AGENT_TURN_CONFLICT, "当前会话已有运行中的 turn"));

        mockMvc.perform(post("/api/agent/sessions/{sessionId}/chat", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hello\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_TURN_CONFLICT.getCode()))
            .andExpect(jsonPath("$.message").value("当前会话已有运行中的 turn"));
    }

    @Test
    @DisplayName("should return http 404 when /trace requests a missing session")
    void shouldReturnNotFoundForMissingTraceSession() throws Exception {
        String sessionId = "missing-session";
        when(traceService.getTrace(sessionId))
            .thenThrow(new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "Agent 会话不存在"));

        mockMvc.perform(get("/api/agent/sessions/{sessionId}/trace", sessionId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_SESSION_NOT_FOUND.getCode()))
            .andExpect(jsonPath("$.message").value("Agent 会话不存在"));
    }

    @Test
    @DisplayName("should return http 404 when /approve targets a missing approval")
    void shouldReturnNotFoundForMissingApprovalApprove() throws Exception {
        String approvalId = "missing-approval";
        when(agentOrchestrator.approveApproval(approvalId))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "审批记录不存在"));

        mockMvc.perform(post("/api/agent/approvals/{approvalId}/approve", approvalId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()))
            .andExpect(jsonPath("$.message").value("审批记录不存在"));
    }

    @Test
    @DisplayName("should return session approvals through the approval endpoint")
    void shouldReturnSessionApprovals() throws Exception {
        String sessionId = "session-approval-list";
        when(approvalService.getSessionApprovals(sessionId)).thenReturn(java.util.List.of(
            new AgentApprovalDTO(
                "approval-1",
                sessionId,
                "turn-1",
                "delete_resume",
                AgentToolRiskLevel.REQUIRES_APPROVAL,
                AgentApprovalStatus.PENDING,
                "高风险工具必须先审批后执行",
                java.time.LocalDateTime.parse("2026-04-22T20:00:00"),
                null,
                java.time.LocalDateTime.parse("2026-04-22T19:50:00")
            )
        ));

        mockMvc.perform(get("/api/agent/sessions/{sessionId}/approvals", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].approvalId").value("approval-1"))
            .andExpect(jsonPath("$.data[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data[0].selectedTool").value("delete_resume"));
    }

    @Test
    @DisplayName("should return session turn summaries for the workbench")
    void shouldReturnSessionTurnSummariesForWorkbench() throws Exception {
        String sessionId = "session-turns";
        when(workbenchService.getSessionTurns(sessionId)).thenReturn(java.util.List.of(
            new AgentTurnSummaryDTO(
                "turn-1",
                AgentTurnStatus.COMPLETED,
                interview.guide.modules.agent.model.AgentCompletionMode.SUCCESS,
                "先分析我的简历重点",
                "已经整理出三个重点方向。",
                null,
                java.time.LocalDateTime.parse("2026-04-25T10:00:00"),
                java.time.LocalDateTime.parse("2026-04-25T10:00:01"),
                java.time.LocalDateTime.parse("2026-04-25T10:00:09")
            )
        ));

        mockMvc.perform(get("/api/agent/sessions/{sessionId}/turns", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].turnId").value("turn-1"))
            .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
            .andExpect(jsonPath("$.data[0].assistantReplyPreview").value("已经整理出三个重点方向。"));
    }

    @Test
    @DisplayName("should return turn detail for the workbench")
    void shouldReturnTurnDetailForWorkbench() throws Exception {
        String turnId = "turn-detail";
        when(workbenchService.getTurnDetail(turnId)).thenReturn(new AgentTurnDetailDTO(
            new AgentTurnSummaryDTO(
                turnId,
                AgentTurnStatus.WAITING_APPROVAL,
                interview.guide.modules.agent.model.AgentCompletionMode.WAITING_APPROVAL,
                "帮我删除当前简历",
                "高风险操作，等待审批。",
                null,
                java.time.LocalDateTime.parse("2026-04-25T11:00:00"),
                java.time.LocalDateTime.parse("2026-04-25T11:00:01"),
                null
            ),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of()
        ));

        mockMvc.perform(get("/api/agent/turns/{turnId}", turnId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.turn.turnId").value(turnId))
            .andExpect(jsonPath("$.data.turn.status").value("WAITING_APPROVAL"))
            .andExpect(jsonPath("$.data.turn.assistantReplyPreview").value("高风险操作，等待审批。"));
    }
}
