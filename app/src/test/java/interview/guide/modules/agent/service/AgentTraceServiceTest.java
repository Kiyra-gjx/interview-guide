package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.repository.AgentSessionRepository;
import interview.guide.modules.agent.repository.AgentStepTraceRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
import interview.guide.modules.agent.support.AgentToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTraceServiceTest {

    @Mock
    private AgentStepTraceRepository traceRepository;
    @Mock
    private AgentSessionRepository sessionRepository;
    @Mock
    private AgentTurnRepository turnRepository;
    @Mock
    private ObjectMapper objectMapper;

    private AgentTraceService traceService;

    @BeforeEach
    void setUp() {
        traceService = new AgentTraceService(
            traceRepository,
            sessionRepository,
            turnRepository,
            objectMapper
        );
    }

    @Test
    @DisplayName("should reject getTrace when the session does not exist")
    void shouldRejectGetTraceWhenSessionDoesNotExist() {
        when(sessionRepository.findBySessionId("missing-session")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> traceService.getTrace("missing-session"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_SESSION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("should expose a consistent turn not found error from trace writes")
    void shouldExposeConsistentTurnNotFoundErrorFromTraceWrites() {
        when(turnRepository.findByTurnId("missing-turn")).thenReturn(Optional.empty());

        AgentMemorySnapshot memory = new AgentMemorySnapshot("goal", "phase", java.util.List.of(), java.util.List.of(), "next");

        assertThatThrownBy(() -> traceService.recordDirectReply("missing-turn", "decision", "reply", memory, memory))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_TURN_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("should reject turn trace lookups when the turn does not exist")
    void shouldRejectTurnTraceLookupWhenTurnDoesNotExist() {
        when(turnRepository.findByTurnId("missing-turn")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> traceService.getTurnTrace("missing-turn"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_TURN_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("should persist layered tool payloads when completing a tool step")
    void shouldPersistLayeredToolPayloadsWhenCompletingToolStep() throws Exception {
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        AgentMemorySnapshot memoryAfter = new AgentMemorySnapshot("goal", "phase", java.util.List.of("fact-1"), java.util.List.of("tool-1"), "next");
        AgentToolResult result = new AgentToolResult(
            "summary",
            Map.of("answer", "业务结果"),
            Map.of("retrievalQuery", "debug query"),
            java.util.List.of("fact-1")
        );
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

        traceService.completeToolStep(trace, result, memoryAfter);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper, times(2)).writeValueAsString(payloadCaptor.capture());
        verify(traceRepository).save(trace);

        assertThat(trace.getToolOutputJson()).isEqualTo("{\"ok\":true}");
        assertThat(trace.getMemoryAfterJson()).isEqualTo("{\"ok\":true}");
        assertThat(trace.getObservationSummary()).isEqualTo("summary");
        assertThat(trace.getStatus()).isEqualTo(AgentExecutionState.COMPLETED);
        assertThat(payloadCaptor.getAllValues()).contains(result.tracePayload(), memoryAfter);
    }

    @Test
    @DisplayName("should expose memory before and after snapshots from turn trace lookups")
    void shouldExposeMemoryBeforeAndAfterSnapshotsFromTurnTraceLookups() throws Exception {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId("session-1");
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId("turn-1");
        turn.setSession(session);
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setTurn(turn);
        trace.setSession(session);
        trace.setStepIndex(1);
        trace.setDecisionSummary("decision");
        trace.setSelectedTool("tool");
        trace.setToolInputJson("{}");
        trace.setToolOutputJson("{}");
        trace.setObservationSummary("observation");
        trace.setStatus(AgentExecutionState.COMPLETED);
        trace.setCreatedAt(java.time.LocalDateTime.now());
        trace.setMemoryBeforeJson("{\"before\":true}");
        trace.setMemoryAfterJson("{\"after\":true}");
        AgentMemorySnapshot memoryBefore = new AgentMemorySnapshot("goal", "before_phase", java.util.List.of("fact-1"), java.util.List.of(), "next-1");
        AgentMemorySnapshot memoryAfter = new AgentMemorySnapshot("goal", "after_phase", java.util.List.of("fact-1", "fact-2"), java.util.List.of("tool"), "next-2");

        when(turnRepository.findByTurnId("turn-1")).thenReturn(Optional.of(turn));
        when(traceRepository.findByTurn_TurnIdOrderByStepIndexAsc("turn-1")).thenReturn(java.util.List.of(trace));
        when(objectMapper.readValue("{\"before\":true}", AgentMemorySnapshot.class)).thenReturn(memoryBefore);
        when(objectMapper.readValue("{\"after\":true}", AgentMemorySnapshot.class)).thenReturn(memoryAfter);

        java.util.List<AgentTraceDTO> traceDTOs = traceService.getTurnTrace("turn-1");

        assertThat(traceDTOs).hasSize(1);
        assertThat(traceDTOs.getFirst().memoryBefore()).isEqualTo(memoryBefore);
        assertThat(traceDTOs.getFirst().memoryAfter()).isEqualTo(memoryAfter);
    }

    @Test
    @DisplayName("should expose an explicit unavailable snapshot when memory deserialization fails")
    void shouldExposeExplicitUnavailableSnapshotWhenMemoryDeserializationFails() throws Exception {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId("session-2");
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId("turn-2");
        turn.setSession(session);
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setTurn(turn);
        trace.setSession(session);
        trace.setStepIndex(1);
        trace.setStatus(AgentExecutionState.COMPLETED);
        trace.setCreatedAt(java.time.LocalDateTime.now());
        trace.setMemoryBeforeJson("{bad json}");

        when(turnRepository.findByTurnId("turn-2")).thenReturn(Optional.of(turn));
        when(traceRepository.findByTurn_TurnIdOrderByStepIndexAsc("turn-2")).thenReturn(java.util.List.of(trace));
        when(objectMapper.readValue("{bad json}", AgentMemorySnapshot.class)).thenThrow(new RuntimeException("boom"));

        java.util.List<AgentTraceDTO> traceDTOs = traceService.getTurnTrace("turn-2");

        assertThat(traceDTOs).hasSize(1);
        assertThat(traceDTOs.getFirst().memoryBefore()).isNotNull();
        assertThat(traceDTOs.getFirst().memoryBefore().currentPhase()).isEqualTo("memory_snapshot_unavailable");
        assertThat(traceDTOs.getFirst().memoryBefore().confirmedFacts()).contains("memory_snapshot_read_failed");
    }
}
