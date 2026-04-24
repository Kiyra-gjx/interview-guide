package interview.guide.modules.agent.service;

import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.support.AgentContextBudget;
import interview.guide.modules.agent.support.AgentContextSection;
import interview.guide.modules.agent.support.AgentContextSectionStatus;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentApprovalRuntimeServiceTest {

    @Mock
    private AgentTraceService traceService;
    @Mock
    private AgentApprovalService approvalService;
    @Mock
    private AgentSessionService sessionService;

    private AgentApprovalRuntimeService runtimeService;

    @BeforeEach
    void setUp() {
        runtimeService = new AgentApprovalRuntimeService(traceService, approvalService, sessionService);
    }

    @Test
    @DisplayName("should persist pending approval through a single runtime workflow")
    void shouldPersistPendingApprovalThroughSingleRuntimeWorkflow() {
        AgentSessionEntity session = createSession("session-approval");
        AgentTurnEntity runningTurn = createTurn("turn-approval", session, AgentTurnStatus.RUNNING);
        AgentTurnEntity waitingTurn = createTurn("turn-approval", session, AgentTurnStatus.WAITING_APPROVAL);
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setTurn(runningTurn);
        AgentMemorySnapshot memory = createMemory();
        AgentAssembledContext assembledContext = createAssembledContext(session, memory, "delete my resume");
        AgentGuardrailResult guardrailResult = new AgentGuardrailResult(null, null, null, null, "approval required");
        AgentApprovalDTO approval = new AgentApprovalDTO(
            "approval-1",
            session.getSessionId(),
            runningTurn.getTurnId(),
            "delete_resume",
            AgentToolRiskLevel.REQUIRES_APPROVAL,
            AgentApprovalStatus.PENDING,
            "approval required",
            LocalDateTime.now().plusMinutes(10),
            null,
            LocalDateTime.now()
        );

        when(traceService.startToolStep(
            eq(runningTurn.getTurnId()),
            eq("need risky tool"),
            eq("delete_resume"),
            eq(Map.<String, Object>of("resumeId", 42L)),
            eq(memory)
        )).thenReturn(trace);
        when(approvalService.createPendingApproval(any())).thenReturn(approval);
        when(sessionService.waitForApproval(
            eq(runningTurn.getTurnId()),
            eq("waiting approval"),
            eq(approval.expiresAt()),
            eq(AgentCompletionMode.WAITING_APPROVAL)
        )).thenReturn(waitingTurn);

        AgentApprovalRuntimeService.PendingApprovalTransition transition = runtimeService.parkTurnForApproval(
            new AgentApprovalRuntimeService.ParkTurnForApprovalRequest(
                runningTurn.getTurnId(),
                session,
                memory,
                "delete my resume",
                assembledContext,
                "need risky tool",
                "delete_resume",
                AgentToolRiskLevel.REQUIRES_APPROVAL,
                Map.<String, Object>of("resumeId", 42L),
                "waiting approval",
                List.of(guardrailResult)
            )
        );

        ArgumentCaptor<AgentApprovalService.CreateApprovalRequest> approvalRequestCaptor = ArgumentCaptor.forClass(
            AgentApprovalService.CreateApprovalRequest.class
        );
        InOrder inOrder = inOrder(traceService, approvalService, sessionService);
        inOrder.verify(traceService).startToolStep(
            eq(runningTurn.getTurnId()),
            eq("need risky tool"),
            eq("delete_resume"),
            eq(Map.<String, Object>of("resumeId", 42L)),
            eq(memory)
        );
        inOrder.verify(approvalService).createPendingApproval(approvalRequestCaptor.capture());
        inOrder.verify(traceService).markToolStepWaitingApproval(
            eq(trace),
            eq(approval),
            eq("waiting approval"),
            eq(memory),
            eq(List.of(guardrailResult))
        );
        inOrder.verify(sessionService).waitForApproval(
            eq(runningTurn.getTurnId()),
            eq("waiting approval"),
            eq(approval.expiresAt()),
            eq(AgentCompletionMode.WAITING_APPROVAL)
        );

        assertThat(approvalRequestCaptor.getValue().assembledContext()).isEqualTo(assembledContext);
        assertThat(transition.approval()).isEqualTo(approval);
        assertThat(transition.persistedTurn()).isEqualTo(waitingTurn);
    }

    @Test
    @DisplayName("should surface persistence failures when parking a turn for approval")
    void shouldSurfacePersistenceFailuresWhenParkingTurnForApproval() {
        AgentSessionEntity session = createSession("session-approval-failure");
        AgentTurnEntity runningTurn = createTurn("turn-approval-failure", session, AgentTurnStatus.RUNNING);
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setTurn(runningTurn);
        AgentMemorySnapshot memory = createMemory();
        AgentAssembledContext assembledContext = createAssembledContext(session, memory, "delete my resume");
        AgentApprovalDTO approval = new AgentApprovalDTO(
            "approval-2",
            session.getSessionId(),
            runningTurn.getTurnId(),
            "delete_resume",
            AgentToolRiskLevel.REQUIRES_APPROVAL,
            AgentApprovalStatus.PENDING,
            "approval required",
            LocalDateTime.now().plusMinutes(10),
            null,
            LocalDateTime.now()
        );

        when(traceService.startToolStep(anyString(), anyString(), anyString(), any(), eq(memory))).thenReturn(trace);
        when(approvalService.createPendingApproval(any())).thenReturn(approval);
        when(sessionService.waitForApproval(anyString(), anyString(), any(), eq(AgentCompletionMode.WAITING_APPROVAL)))
            .thenThrow(new IllegalStateException("cannot park turn"));

        assertThatThrownBy(() -> runtimeService.parkTurnForApproval(
            new AgentApprovalRuntimeService.ParkTurnForApprovalRequest(
                runningTurn.getTurnId(),
                session,
                memory,
                "delete my resume",
                assembledContext,
                "need risky tool",
                "delete_resume",
                AgentToolRiskLevel.REQUIRES_APPROVAL,
                Map.<String, Object>of("resumeId", 42L),
                "waiting approval",
                List.of()
            )
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("cannot park turn");

        verify(traceService).markToolStepWaitingApproval(
            eq(trace),
            eq(approval),
            eq("waiting approval"),
            eq(memory),
            eq(List.of())
        );
    }

    private AgentSessionEntity createSession(String sessionId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setGoal("goal");
        return session;
    }

    private AgentTurnEntity createTurn(String turnId, AgentSessionEntity session, AgentTurnStatus status) {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setSession(session);
        turn.setStatus(status);
        turn.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(10));
        return turn;
    }

    private AgentMemorySnapshot createMemory() {
        return new AgentMemorySnapshot(
            "goal",
            "phase",
            List.of("fact"),
            List.of(),
            "focus"
        );
    }

    private AgentAssembledContext createAssembledContext(
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage
    ) {
        return new AgentAssembledContext(
            session.getSessionId(),
            session.getGoal(),
            latestUserMessage,
            null,
            List.of(),
            memory,
            "上下文摘要",
            new AgentContextBudget(320, 180, 140),
            List.of(
                new AgentContextSection(
                    "latest_user_message",
                    "最新用户消息",
                    100,
                    latestUserMessage,
                    AgentContextSectionStatus.INCLUDED,
                    "included",
                    latestUserMessage.length(),
                    latestUserMessage.length()
                )
            )
        );
    }
}
