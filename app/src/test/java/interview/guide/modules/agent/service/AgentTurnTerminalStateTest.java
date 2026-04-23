package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentMessageEntity;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.repository.AgentMessageRepository;
import interview.guide.modules.agent.repository.AgentSessionRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTurnTerminalStateTest {

    @Mock
    private AgentSessionRepository sessionRepository;
    @Mock
    private AgentMessageRepository messageRepository;
    @Mock
    private AgentTurnRepository turnRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private AgentMemoryService memoryService;
    @Mock
    private ResumeRepository resumeRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private AgentSessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new AgentSessionService(
            sessionRepository,
            messageRepository,
            turnRepository,
            resumeRepository,
            knowledgeBaseRepository,
            objectMapper,
            memoryService
        );
    }

    @Test
    @DisplayName("should not overwrite a completed turn when failTurn is called")
    void shouldNotOverwriteCompletedTurnWhenFailTurnIsCalled() {
        AgentTurnEntity completedTurn = createTurn("turn-completed", AgentTurnStatus.COMPLETED);

        when(turnRepository.findByTurnId("turn-completed")).thenReturn(Optional.of(completedTurn));

        AgentTurnEntity result = sessionService.failTurn("turn-completed", new RuntimeException("boom"));

        assertThat(result.getStatus()).isEqualTo(AgentTurnStatus.COMPLETED);
        verify(sessionRepository, never()).findBySessionIdForUpdate(any());
        verify(turnRepository, never()).save(any(AgentTurnEntity.class));
    }

    @Test
    @DisplayName("should reject completion when the turn has already been aborted")
    void shouldRejectCompletionWhenTurnHasAlreadyBeenAborted() {
        AgentTurnEntity abortedTurn = createTurn("turn-aborted", AgentTurnStatus.ABORTED);

        when(turnRepository.findByTurnId("turn-aborted")).thenReturn(Optional.of(abortedTurn));

        assertThatThrownBy(() -> sessionService.completeTurn(
            "turn-aborted",
            "reply",
            new AgentMemorySnapshot("goal", "phase", java.util.List.of(), java.util.List.of(), "summary"),
            AgentCompletionMode.SUCCESS
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_TURN_EXPIRED.getCode()));

        verify(sessionRepository, never()).findBySessionIdForUpdate(any());
        verify(turnRepository, never()).save(any(AgentTurnEntity.class));
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("should lock session before turn when completing a running turn")
    void shouldLockSessionBeforeTurnWhenCompletingRunningTurn() {
        AgentSessionEntity session = createSession("session-running");
        AgentTurnEntity runningTurn = createTurn("turn-running", AgentTurnStatus.RUNNING, session);
        AgentMemorySnapshot snapshot = new AgentMemorySnapshot(
            "goal",
            "phase",
            java.util.List.of("fact"),
            java.util.List.of(),
            "summary"
        );

        when(turnRepository.findByTurnId("turn-running")).thenReturn(Optional.of(runningTurn));
        when(sessionRepository.findBySessionIdForUpdate("session-running")).thenReturn(Optional.of(session));
        when(turnRepository.findByTurnIdForUpdate("turn-running")).thenReturn(Optional.of(runningTurn));
        when(messageRepository.findTopBySession_SessionIdOrderByMessageOrderDesc("session-running")).thenReturn(Optional.empty());
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.save(session)).thenReturn(session);
        when(turnRepository.save(runningTurn)).thenReturn(runningTurn);

        AgentTurnEntity completedTurn = sessionService.completeTurn(
            "turn-running",
            "reply",
            snapshot,
            AgentCompletionMode.SUCCESS
        );

        InOrder inOrder = inOrder(turnRepository, sessionRepository);
        inOrder.verify(turnRepository).findByTurnId("turn-running");
        inOrder.verify(sessionRepository).findBySessionIdForUpdate("session-running");
        inOrder.verify(turnRepository).findByTurnIdForUpdate("turn-running");

        assertThat(completedTurn.getStatus()).isEqualTo(AgentTurnStatus.COMPLETED);
        assertThat(completedTurn.getCompletionMode()).isEqualTo(AgentCompletionMode.SUCCESS);
        verify(memoryService).writeMemory(session, snapshot);
        verify(messageRepository).save(any());
    }

    @Test
    @DisplayName("should lock session before turn when parking a turn for approval")
    void shouldLockSessionBeforeTurnWhenParkingTurnForApproval() {
        AgentSessionEntity session = createSession("session-approval");
        AgentTurnEntity runningTurn = createTurn("turn-approval", AgentTurnStatus.RUNNING, session);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);

        when(turnRepository.findByTurnId("turn-approval")).thenReturn(Optional.of(runningTurn));
        when(sessionRepository.findBySessionIdForUpdate("session-approval")).thenReturn(Optional.of(session));
        when(turnRepository.findByTurnIdForUpdate("turn-approval")).thenReturn(Optional.of(runningTurn));
        when(messageRepository.findTopBySession_SessionIdOrderByMessageOrderDesc("session-approval")).thenReturn(Optional.empty());
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.save(session)).thenReturn(session);
        when(turnRepository.save(runningTurn)).thenReturn(runningTurn);

        AgentTurnEntity waitingTurn = sessionService.waitForApproval(
            "turn-approval",
            "这个动作需要先审批。",
            expiresAt,
            AgentCompletionMode.WAITING_APPROVAL
        );

        InOrder inOrder = inOrder(turnRepository, sessionRepository);
        inOrder.verify(turnRepository).findByTurnId("turn-approval");
        inOrder.verify(sessionRepository).findBySessionIdForUpdate("session-approval");
        inOrder.verify(turnRepository).findByTurnIdForUpdate("turn-approval");

        assertThat(waitingTurn.getStatus()).isEqualTo(AgentTurnStatus.WAITING_APPROVAL);
        assertThat(waitingTurn.getCompletionMode()).isEqualTo(AgentCompletionMode.WAITING_APPROVAL);
        assertThat(waitingTurn.getLeaseExpiresAt()).isEqualTo(expiresAt);
        verify(memoryService, never()).writeMemory(any(), any());
        verify(messageRepository).save(any());
    }

    @Test
    @DisplayName("should lock session before turn when resuming an approved turn")
    void shouldLockSessionBeforeTurnWhenResumingApprovedTurn() {
        AgentSessionEntity session = createSession("session-resume");
        AgentTurnEntity waitingTurn = createTurn("turn-resume", AgentTurnStatus.WAITING_APPROVAL, session);

        when(turnRepository.findByTurnId("turn-resume")).thenReturn(Optional.of(waitingTurn));
        when(sessionRepository.findBySessionIdForUpdate("session-resume")).thenReturn(Optional.of(session));
        when(turnRepository.findByTurnIdForUpdate("turn-resume")).thenReturn(Optional.of(waitingTurn));
        when(sessionRepository.save(session)).thenReturn(session);
        when(turnRepository.save(waitingTurn)).thenReturn(waitingTurn);

        AgentTurnEntity resumedTurn = sessionService.resumeTurnFromApproval("turn-resume");

        InOrder inOrder = inOrder(turnRepository, sessionRepository);
        inOrder.verify(turnRepository).findByTurnId("turn-resume");
        inOrder.verify(sessionRepository).findBySessionIdForUpdate("session-resume");
        inOrder.verify(turnRepository).findByTurnIdForUpdate("turn-resume");

        assertThat(resumedTurn.getStatus()).isEqualTo(AgentTurnStatus.RUNNING);
        assertThat(resumedTurn.getCompletionMode()).isNull();
        assertThat(resumedTurn.getLeaseExpiresAt()).isNotNull();
        verify(messageRepository, never()).save(any());
        verify(memoryService, never()).writeMemory(any(), any());
    }

    @Test
    @DisplayName("should reclaim an approved turn when the previous execution lease has expired")
    void shouldReclaimApprovedTurnWhenPreviousExecutionLeaseExpired() {
        AgentSessionEntity session = createSession("session-approved-reclaim");
        AgentTurnEntity runningTurn = createTurn("turn-approved-reclaim", AgentTurnStatus.RUNNING, session);
        runningTurn.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(turnRepository.findByTurnId("turn-approved-reclaim")).thenReturn(Optional.of(runningTurn));
        when(sessionRepository.findBySessionIdForUpdate("session-approved-reclaim")).thenReturn(Optional.of(session));
        when(turnRepository.findByTurnIdForUpdate("turn-approved-reclaim")).thenReturn(Optional.of(runningTurn));
        when(sessionRepository.save(session)).thenReturn(session);
        when(turnRepository.save(runningTurn)).thenReturn(runningTurn);

        AgentSessionService.ApprovedTurnClaim claim = sessionService.claimTurnForApprovedExecution("turn-approved-reclaim");

        InOrder inOrder = inOrder(turnRepository, sessionRepository);
        inOrder.verify(turnRepository).findByTurnId("turn-approved-reclaim");
        inOrder.verify(sessionRepository).findBySessionIdForUpdate("session-approved-reclaim");
        inOrder.verify(turnRepository).findByTurnIdForUpdate("turn-approved-reclaim");

        assertThat(claim.claimed()).isTrue();
        assertThat(claim.turn().getStatus()).isEqualTo(AgentTurnStatus.RUNNING);
        assertThat(claim.turn().getLeaseExpiresAt()).isAfter(LocalDateTime.now());
        verify(messageRepository, never()).save(any());
        verify(memoryService, never()).writeMemory(any(), any());
    }

    @Test
    @DisplayName("should lock session before turn when failing a running turn")
    void shouldLockSessionBeforeTurnWhenFailingRunningTurn() {
        AgentSessionEntity session = createSession("session-fail");
        AgentTurnEntity runningTurn = createTurn("turn-fail", AgentTurnStatus.RUNNING, session);

        when(turnRepository.findByTurnId("turn-fail")).thenReturn(Optional.of(runningTurn));
        when(sessionRepository.findBySessionIdForUpdate("session-fail")).thenReturn(Optional.of(session));
        when(turnRepository.findByTurnIdForUpdate("turn-fail")).thenReturn(Optional.of(runningTurn));
        when(sessionRepository.save(session)).thenReturn(session);
        when(turnRepository.save(runningTurn)).thenReturn(runningTurn);

        AgentTurnEntity failedTurn = sessionService.failTurn("turn-fail", new RuntimeException("boom"));

        InOrder inOrder = inOrder(turnRepository, sessionRepository);
        inOrder.verify(turnRepository).findByTurnId("turn-fail");
        inOrder.verify(sessionRepository).findBySessionIdForUpdate("session-fail");
        inOrder.verify(turnRepository).findByTurnIdForUpdate("turn-fail");

        assertThat(failedTurn.getStatus()).isEqualTo(AgentTurnStatus.FAILED);
        assertThat(failedTurn.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("should persist a fallback assistant reply when failing a running turn")
    void shouldPersistFallbackAssistantReplyWhenFailingRunningTurn() {
        AgentSessionEntity session = createSession("session-fail-reply");
        AgentTurnEntity runningTurn = createTurn("turn-fail-reply", AgentTurnStatus.RUNNING, session);

        when(turnRepository.findByTurnId("turn-fail-reply")).thenReturn(Optional.of(runningTurn));
        when(sessionRepository.findBySessionIdForUpdate("session-fail-reply")).thenReturn(Optional.of(session));
        when(turnRepository.findByTurnIdForUpdate("turn-fail-reply")).thenReturn(Optional.of(runningTurn));
        when(messageRepository.findTopBySession_SessionIdOrderByMessageOrderDesc("session-fail-reply")).thenReturn(Optional.empty());
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.save(session)).thenReturn(session);
        when(turnRepository.save(runningTurn)).thenReturn(runningTurn);

        AgentTurnEntity failedTurn = sessionService.failTurn(
            "turn-fail-reply",
            new RuntimeException("boom"),
            "approved replay blocked"
        );

        assertThat(failedTurn.getStatus()).isEqualTo(AgentTurnStatus.FAILED);
        verify(messageRepository).save(any());
    }

    @Test
    @DisplayName("should not duplicate a fallback assistant reply when it already exists on the turn")
    void shouldNotDuplicateFallbackAssistantReplyWhenItAlreadyExistsOnTheTurn() {
        AgentSessionEntity session = createSession("session-fail-reply-existing");
        AgentTurnEntity runningTurn = createTurn("turn-fail-reply-existing", AgentTurnStatus.RUNNING, session);
        AgentMessageEntity existingReply = new AgentMessageEntity();
        existingReply.setSession(session);
        existingReply.setTurn(runningTurn);
        existingReply.setRole(AgentMessageEntity.MessageRole.ASSISTANT);
        existingReply.setContent("approved replay blocked");
        existingReply.setMessageOrder(2);

        when(turnRepository.findByTurnId("turn-fail-reply-existing")).thenReturn(Optional.of(runningTurn));
        when(sessionRepository.findBySessionIdForUpdate("session-fail-reply-existing")).thenReturn(Optional.of(session));
        when(turnRepository.findByTurnIdForUpdate("turn-fail-reply-existing")).thenReturn(Optional.of(runningTurn));
        when(messageRepository.findTopBySession_SessionIdOrderByMessageOrderDesc("session-fail-reply-existing"))
            .thenReturn(Optional.of(existingReply));
        when(sessionRepository.save(session)).thenReturn(session);
        when(turnRepository.save(runningTurn)).thenReturn(runningTurn);

        AgentTurnEntity failedTurn = sessionService.failTurn(
            "turn-fail-reply-existing",
            new RuntimeException("boom"),
            "approved replay blocked"
        );

        assertThat(failedTurn.getStatus()).isEqualTo(AgentTurnStatus.FAILED);
        verify(messageRepository, never()).save(any());
    }

    private AgentSessionEntity createSession(String sessionId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    private AgentTurnEntity createTurn(String turnId, AgentTurnStatus status) {
        return createTurn(turnId, status, createSession("session-" + turnId));
    }

    private AgentTurnEntity createTurn(String turnId, AgentTurnStatus status, AgentSessionEntity session) {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setSession(session);
        turn.setStatus(status);
        if (status == AgentTurnStatus.COMPLETED
            || status == AgentTurnStatus.FAILED
            || status == AgentTurnStatus.ABORTED) {
            turn.setFinishedAt(LocalDateTime.now());
        }
        return turn;
    }
}
