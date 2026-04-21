package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
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

        assertThatThrownBy(() -> traceService.recordDirectReply("missing-turn", "decision", "reply"))
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
        AgentToolResult result = new AgentToolResult(
            "summary",
            Map.of("answer", "业务结果"),
            Map.of("retrievalQuery", "debug query"),
            java.util.List.of("fact-1")
        );
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

        traceService.completeToolStep(trace, result);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(payloadCaptor.capture());
        verify(traceRepository).save(trace);

        assertThat(trace.getToolOutputJson()).isEqualTo("{\"ok\":true}");
        assertThat(trace.getObservationSummary()).isEqualTo("summary");
        assertThat(trace.getStatus()).isEqualTo(interview.guide.modules.agent.model.AgentExecutionState.COMPLETED);
        assertThat(payloadCaptor.getValue()).isEqualTo(result.tracePayload());
    }
}
