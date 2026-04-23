package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentApprovalEntity;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.repository.AgentApprovalRepository;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentApprovalServiceTest {

    @Mock
    private AgentApprovalRepository approvalRepository;
    @Mock
    private ObjectMapper objectMapper;

    private AgentApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new AgentApprovalService(approvalRepository, objectMapper);
    }

    @Test
    @DisplayName("should expose expired status when listing session approvals")
    void shouldExposeExpiredStatusWhenListingSessionApprovals() {
        AgentApprovalEntity expiredPendingApproval = createApproval(
            "approval-expired",
            "session-1",
            "turn-1",
            AgentApprovalStatus.PENDING,
            LocalDateTime.now().minusMinutes(1)
        );
        when(approvalRepository.findBySession_SessionIdOrderByCreatedAtDesc("session-1"))
            .thenReturn(List.of(expiredPendingApproval));

        List<AgentApprovalDTO> approvals = approvalService.getSessionApprovals("session-1");

        assertThat(approvals).singleElement()
            .extracting(AgentApprovalDTO::status)
            .isEqualTo(AgentApprovalStatus.EXPIRED);
        verify(approvalRepository, never()).save(expiredPendingApproval);
    }

    @Test
    @DisplayName("should expose expired status when reading a single approval")
    void shouldExposeExpiredStatusWhenReadingSingleApproval() {
        AgentApprovalEntity expiredPendingApproval = createApproval(
            "approval-expired-single",
            "session-2",
            "turn-2",
            AgentApprovalStatus.PENDING,
            LocalDateTime.now().minusMinutes(1)
        );
        when(approvalRepository.findByApprovalId("approval-expired-single"))
            .thenReturn(Optional.of(expiredPendingApproval));

        AgentApprovalDTO approval = approvalService.getApproval("approval-expired-single");

        assertThat(approval.status()).isEqualTo(AgentApprovalStatus.EXPIRED);
        verify(approvalRepository, never()).save(expiredPendingApproval);
    }

    private AgentApprovalEntity createApproval(
        String approvalId,
        String sessionId,
        String turnId,
        AgentApprovalStatus status,
        LocalDateTime expiresAt
    ) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);

        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setSession(session);

        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setTurn(turn);
        trace.setSession(session);

        AgentApprovalEntity approval = new AgentApprovalEntity();
        approval.setApprovalId(approvalId);
        approval.setSession(session);
        approval.setTurn(turn);
        approval.setTrace(trace);
        approval.setSelectedTool("delete_resume");
        approval.setRiskLevel(AgentToolRiskLevel.REQUIRES_APPROVAL);
        approval.setStatus(status);
        approval.setReason("high risk");
        approval.setExpiresAt(expiresAt);
        approval.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        return approval;
    }
}
