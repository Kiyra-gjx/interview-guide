package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.repository.AgentMessageRepository;
import interview.guide.modules.agent.repository.AgentSessionRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
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

    private AgentSessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new AgentSessionService(
            sessionRepository,
            messageRepository,
            turnRepository,
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
