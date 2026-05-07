package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentSessionDTO;
import interview.guide.modules.agent.model.AgentMessageEntity;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.model.CreateAgentSessionRequest;
import interview.guide.modules.agent.repository.AgentMessageRepository;
import interview.guide.modules.agent.repository.AgentSessionRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.type.TypeReference;
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

    private static final List<AgentTurnStatus> OPEN_TURN_STATUSES = List.of(
        AgentTurnStatus.RUNNING,
        AgentTurnStatus.WAITING_APPROVAL
    );

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
    @DisplayName("should reject a new turn when the session already has an active running turn")
    void shouldRejectNewTurnWhenActiveRunningTurnExists() {
        String sessionId = "session-conflict";
        AgentSessionEntity session = createSession(sessionId);
        AgentTurnEntity runningTurn = createRunningTurn("turn-running", LocalDateTime.now().plusMinutes(1));

        when(sessionRepository.findBySessionIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(turnRepository.findBySession_SessionIdAndStatusInOrderByCreatedAtAsc(sessionId, OPEN_TURN_STATUSES))
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
        when(turnRepository.findBySession_SessionIdAndStatusInOrderByCreatedAtAsc(sessionId, OPEN_TURN_STATUSES))
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
    @DisplayName("should reject a new turn when the session still has a waiting approval turn")
    void shouldRejectNewTurnWhenWaitingApprovalTurnExists() {
        String sessionId = "session-waiting-approval";
        AgentSessionEntity session = createSession(sessionId);
        AgentTurnEntity waitingTurn = createWaitingApprovalTurn("turn-waiting-approval", LocalDateTime.now().plusMinutes(10));

        when(sessionRepository.findBySessionIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(turnRepository.findBySession_SessionIdAndStatusInOrderByCreatedAtAsc(sessionId, OPEN_TURN_STATUSES))
            .thenReturn(List.of(waitingTurn), List.of(waitingTurn));

        assertThatThrownBy(() -> sessionService.startTurn(sessionId, "hello"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_TURN_CONFLICT.getCode()));

        verify(turnRepository, never()).save(any(AgentTurnEntity.class));
        verify(messageRepository, never()).save(any(AgentMessageEntity.class));
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

    @Test
    @DisplayName("should return session summary without loading message history")
    void shouldReturnSessionSummaryWithoutLoadingMessageHistory() throws Exception {
        String sessionId = "session-summary";
        AgentSessionEntity session = createSession(sessionId);
        session.setStatus(AgentExecutionState.RUNNING);
        session.setKnowledgeBaseIdsJson("[1,2]");

        when(sessionRepository.findBySessionId(sessionId)).thenReturn(Optional.of(session));
        when(objectMapper.readValue(eq("[1,2]"), any(TypeReference.class))).thenReturn(List.of(1L, 2L));

        AgentSessionDTO sessionDTO = sessionService.getSession(sessionId);

        assertThat(sessionDTO.sessionId()).isEqualTo(sessionId);
        assertThat(sessionDTO.knowledgeBaseIds()).containsExactly(1L, 2L);
        verify(messageRepository, never()).findBySession_SessionIdOrderByMessageOrderAsc(sessionId);
    }

    @Test
    @DisplayName("should reject session creation when the resume resource does not exist")
    void shouldRejectSessionCreationWhenResumeResourceDoesNotExist() {
        CreateAgentSessionRequest request = new CreateAgentSessionRequest("title", "goal", 42L, List.of());
        when(resumeRepository.existsById(42L)).thenReturn(false);

        assertThatThrownBy(() -> sessionService.createSession(request))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_INVALID_INPUT.getCode()))
            .hasMessageContaining("resumeId");

        verify(sessionRepository, never()).save(any(AgentSessionEntity.class));
    }

    @Test
    @DisplayName("should reject session creation when any knowledge base resource does not exist")
    void shouldRejectSessionCreationWhenKnowledgeBaseResourceDoesNotExist() {
        CreateAgentSessionRequest request = new CreateAgentSessionRequest("title", "goal", null, List.of(1L, 3L));
        when(knowledgeBaseRepository.findAllById(List.of(1L, 3L))).thenReturn(List.of(kb(1L)));

        assertThatThrownBy(() -> sessionService.createSession(request))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_INVALID_INPUT.getCode()))
            .hasMessageContaining("knowledgeBaseIds");

        verify(sessionRepository, never()).save(any(AgentSessionEntity.class));
    }

    @Test
    @DisplayName("should reject session creation when a knowledge base is not active")
    void shouldRejectSessionCreationWhenKnowledgeBaseIsNotActive() {
        CreateAgentSessionRequest request = new CreateAgentSessionRequest("title", "goal", null, List.of(1L, 2L));
        KnowledgeBaseEntity deleting = kb(2L);
        deleting.setLifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING);
        when(knowledgeBaseRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(kb(1L), deleting));

        assertThatThrownBy(() -> sessionService.createSession(request))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_INVALID_INPUT.getCode()))
            .hasMessageContaining("knowledgeBaseIds");

        verify(sessionRepository, never()).save(any(AgentSessionEntity.class));
    }

    @Test
    @DisplayName("should normalize duplicate knowledge base ids before saving a session")
    void shouldNormalizeDuplicateKnowledgeBaseIdsBeforeSavingSession() throws Exception {
        CreateAgentSessionRequest request = new CreateAgentSessionRequest("title", "goal", null, List.of(1L, 2L, 1L, 2L));
        AgentSessionEntity savedSession = new AgentSessionEntity();
        savedSession.setSessionId("session-created");
        savedSession.setTitle("title");
        savedSession.setGoal("goal");
        savedSession.setStatus(interview.guide.modules.agent.model.AgentExecutionState.CREATED);
        savedSession.setKnowledgeBaseIdsJson("[1,2]");
        savedSession.setCreatedAt(LocalDateTime.now());
        savedSession.setUpdatedAt(LocalDateTime.now());

        when(knowledgeBaseRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(kb(1L), kb(2L)));
        when(objectMapper.writeValueAsString(List.of(1L, 2L))).thenReturn("[1,2]");
        when(objectMapper.readValue(eq("[1,2]"), any(TypeReference.class))).thenReturn(List.of(1L, 2L));
        when(sessionRepository.save(any(AgentSessionEntity.class))).thenReturn(savedSession);

        AgentSessionDTO sessionDTO = sessionService.createSession(request);

        ArgumentCaptor<AgentSessionEntity> sessionCaptor = ArgumentCaptor.forClass(AgentSessionEntity.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getKnowledgeBaseIdsJson()).isEqualTo("[1,2]");
        assertThat(sessionDTO.knowledgeBaseIds()).containsExactly(1L, 2L);
        verify(messageRepository, never()).findBySession_SessionIdOrderByMessageOrderAsc("session-created");
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

    private AgentTurnEntity createWaitingApprovalTurn(String turnId, LocalDateTime leaseExpiresAt) {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setStatus(AgentTurnStatus.WAITING_APPROVAL);
        turn.setLeaseExpiresAt(leaseExpiresAt);
        return turn;
    }

    private KnowledgeBaseEntity kb(Long id) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        return entity;
    }
}
