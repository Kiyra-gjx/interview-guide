package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentMessageEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSessionServiceTest {

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
    @DisplayName("should reject a new turn when the session already has an active running turn")
    void shouldRejectNewTurnWhenActiveRunningTurnExists() {
        String sessionId = "session-conflict";
        AgentSessionEntity session = createSession(sessionId);
        AgentTurnEntity runningTurn = createRunningTurn("turn-running", LocalDateTime.now().plusMinutes(1));

        when(sessionRepository.findBySessionIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(turnRepository.findBySession_SessionIdAndStatusOrderByCreatedAtAsc(sessionId, AgentTurnStatus.RUNNING))
            .thenReturn(List.of(runningTurn), List.of(runningTurn));

        assertThatThrownBy(() -> sessionService.startTurn(sessionId, "hello"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_TURN_CONFLICT.getCode()));

        verify(turnRepository, never()).save(any(AgentTurnEntity.class));
        verify(messageRepository, never()).save(any(AgentMessageEntity.class));
    }

    @Test
    @DisplayName("should abort expired running turns before creating a new turn")
    void shouldAbortExpiredRunningTurnBeforeCreatingNewTurn() {
        String sessionId = "session-expired";
        AgentSessionEntity session = createSession(sessionId);
        AgentTurnEntity expiredTurn = createRunningTurn("turn-expired", LocalDateTime.now().minusMinutes(1));

        when(sessionRepository.findBySessionIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(turnRepository.findBySession_SessionIdAndStatusOrderByCreatedAtAsc(sessionId, AgentTurnStatus.RUNNING))
            .thenReturn(List.of(expiredTurn), List.of());
        when(turnRepository.save(any(AgentTurnEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findTopBySession_SessionIdOrderByMessageOrderDesc(sessionId)).thenReturn(Optional.empty());
        when(messageRepository.save(any(AgentMessageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.save(session)).thenReturn(session);

        AgentSessionService.StartedTurn startedTurn = sessionService.startTurn(sessionId, "hello");

        assertThat(expiredTurn.getStatus()).isEqualTo(AgentTurnStatus.ABORTED);
        assertThat(startedTurn.turnId()).isNotBlank();

        ArgumentCaptor<AgentMessageEntity> messageCaptor = ArgumentCaptor.forClass(AgentMessageEntity.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getTurn()).isNotNull();
        assertThat(messageCaptor.getValue().getRole()).isEqualTo(AgentMessageEntity.MessageRole.USER);
        verify(turnRepository, atLeastOnce()).save(eq(expiredTurn));
    }

    @Test
    @DisplayName("should reject turn message lookups when the turn does not exist")
    void shouldRejectTurnMessageLookupWhenTurnDoesNotExist() {
        when(turnRepository.findByTurnId("missing-turn")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.getTurnMessages("missing-turn"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_TURN_NOT_FOUND.getCode()));
    }

    private AgentSessionEntity createSession(String sessionId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setTitle("session");
        session.setGoal("goal");
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    private AgentTurnEntity createRunningTurn(String turnId, LocalDateTime leaseExpiresAt) {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setStatus(AgentTurnStatus.RUNNING);
        turn.setLeaseExpiresAt(leaseExpiresAt);
        return turn;
    }
}
