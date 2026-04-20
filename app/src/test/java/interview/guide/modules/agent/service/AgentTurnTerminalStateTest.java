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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

        when(turnRepository.findByTurnIdForUpdate("turn-completed")).thenReturn(Optional.of(completedTurn));

        AgentTurnEntity result = sessionService.failTurn("turn-completed", new RuntimeException("boom"));

        assertThat(result.getStatus()).isEqualTo(AgentTurnStatus.COMPLETED);
        verify(sessionRepository, never()).findBySessionIdForUpdate(any());
        verify(turnRepository, never()).save(any(AgentTurnEntity.class));
    }

    @Test
    @DisplayName("should reject completion when the turn has already been aborted")
    void shouldRejectCompletionWhenTurnHasAlreadyBeenAborted() {
        AgentTurnEntity abortedTurn = createTurn("turn-aborted", AgentTurnStatus.ABORTED);

        when(turnRepository.findByTurnIdForUpdate("turn-aborted")).thenReturn(Optional.of(abortedTurn));

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

    private AgentTurnEntity createTurn(String turnId, AgentTurnStatus status) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId("session-" + turnId);
        session.setUpdatedAt(LocalDateTime.now());

        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setSession(session);
        turn.setStatus(status);
        turn.setFinishedAt(LocalDateTime.now());
        return turn;
    }
}
