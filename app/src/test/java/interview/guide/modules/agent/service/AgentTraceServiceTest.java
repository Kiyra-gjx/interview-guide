package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailAction;
import interview.guide.modules.agent.guardrail.AgentGuardrailCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailResolution;
import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.guardrail.AgentGuardrailStage;
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
import static org.mockito.ArgumentMatchers.eq;
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

        assertThatThrownBy(() -> traceService.recordDirectReply(
            "missing-turn",
            "decision",
            "reply",
            memory,
            memory,
            java.util.List.of()
        ))
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
        String finalReply = "最终给用户的回复";
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

        traceService.completeToolStep(trace, result, memoryAfter, finalReply, java.util.List.of());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper, times(2)).writeValueAsString(payloadCaptor.capture());
        verify(traceRepository).save(trace);

        assertThat(trace.getToolOutputJson()).isEqualTo("{\"ok\":true}");
        assertThat(trace.getMemoryAfterJson()).isEqualTo("{\"ok\":true}");
        assertThat(trace.getObservationSummary()).isEqualTo("summary");
        assertThat(trace.getStatus()).isEqualTo(AgentExecutionState.COMPLETED);
        assertThat(payloadCaptor.getAllValues()).contains(result.tracePayload(finalReply), memoryAfter);
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
    @DisplayName("should expose persisted guardrail results from turn trace lookups")
    void shouldExposePersistedGuardrailResultsFromTurnTraceLookups() throws Exception {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId("session-guardrail");
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId("turn-guardrail");
        turn.setSession(session);
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setTurn(turn);
        trace.setSession(session);
        trace.setStepIndex(1);
        trace.setStatus(AgentExecutionState.FAILED);
        trace.setCreatedAt(java.time.LocalDateTime.now());
        trace.setGuardrailResultsJson("[{\"code\":\"TOOL_REQUIRES_APPROVAL\"}]");
        AgentGuardrailResult guardrailResult = new AgentGuardrailResult(
            AgentGuardrailStage.TOOL,
            AgentGuardrailCode.TOOL_REQUIRES_APPROVAL,
            AgentGuardrailAction.REJECT,
            AgentGuardrailResolution.BLOCK_TOOL_CALL,
            "高风险工具在审批能力落地前不能自动执行"
        );

        when(turnRepository.findByTurnId("turn-guardrail")).thenReturn(Optional.of(turn));
        when(traceRepository.findByTurn_TurnIdOrderByStepIndexAsc("turn-guardrail")).thenReturn(java.util.List.of(trace));
        when(objectMapper.readValue(eq("[{\"code\":\"TOOL_REQUIRES_APPROVAL\"}]"), any(tools.jackson.core.type.TypeReference.class)))
            .thenReturn(java.util.List.of(guardrailResult));

        java.util.List<AgentTraceDTO> traceDTOs = traceService.getTurnTrace("turn-guardrail");

        assertThat(traceDTOs).hasSize(1);
        assertThat(traceDTOs.getFirst().guardrailResults()).containsExactly(guardrailResult);
    }

    @Test
    @DisplayName("should expose guardrail write failure placeholder instead of dropping it silently")
    void shouldExposeGuardrailWriteFailurePlaceholderInsteadOfDroppingItSilently() {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId("session-guardrail-write-failure");
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId("turn-guardrail-write-failure");
        turn.setSession(session);
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setTurn(turn);
        trace.setSession(session);
        trace.setStepIndex(1);
        trace.setStatus(AgentExecutionState.COMPLETED);
        trace.setCreatedAt(java.time.LocalDateTime.now());
        trace.setGuardrailResultsJson("[{\"reason\":\"guardrail_results_write_failed\"}]");

        when(turnRepository.findByTurnId("turn-guardrail-write-failure")).thenReturn(Optional.of(turn));
        when(traceRepository.findByTurn_TurnIdOrderByStepIndexAsc("turn-guardrail-write-failure")).thenReturn(java.util.List.of(trace));

        java.util.List<AgentTraceDTO> traceDTOs = traceService.getTurnTrace("turn-guardrail-write-failure");

        assertThat(traceDTOs).hasSize(1);
        assertThat(traceDTOs.getFirst().guardrailResults()).hasSize(1);
        assertThat(traceDTOs.getFirst().guardrailResults().getFirst().reason()).isEqualTo("guardrail_results_write_failed");
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

    @Test
    @DisplayName("should normalize legacy layered payload names from persisted trace output")
    void shouldNormalizeLegacyLayeredPayloadNamesFromPersistedTraceOutput() throws Exception {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId("session-tool-output");
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId("turn-tool-output");
        turn.setSession(session);
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setTurn(turn);
        trace.setSession(session);
        trace.setStepIndex(1);
        trace.setToolOutputJson("{legacy-tool-output}");
        trace.setStatus(AgentExecutionState.COMPLETED);
        trace.setCreatedAt(java.time.LocalDateTime.now());

        when(turnRepository.findByTurnId("turn-tool-output")).thenReturn(Optional.of(turn));
        when(traceRepository.findByTurn_TurnIdOrderByStepIndexAsc("turn-tool-output")).thenReturn(java.util.List.of(trace));
        when(objectMapper.readValue(eq("{legacy-tool-output}"), any(tools.jackson.core.type.TypeReference.class)))
            .thenReturn(Map.of(
                "kind", "tool_result",
                "summary", "summary",
                "reply", "reply",
                "answerPayload", Map.of("answer", "业务结果"),
                "debugPayload", Map.of("retrievalQuery", "debug query"),
                "confirmedFacts", java.util.List.of("fact-1")
            ));

        java.util.List<AgentTraceDTO> traceDTOs = traceService.getTurnTrace("turn-tool-output");

        assertThat(traceDTOs).hasSize(1);
        assertThat(traceDTOs.getFirst().toolOutput()).isNotNull();
        assertThat(traceDTOs.getFirst().toolOutput().answer()).containsEntry("answer", "业务结果");
        assertThat(traceDTOs.getFirst().toolOutput().debug()).containsEntry("retrievalQuery", "debug query");
        assertThat(traceDTOs.getFirst().toolOutput().facts()).containsExactly("fact-1");
    }
}
