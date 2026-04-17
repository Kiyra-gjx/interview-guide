package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.repository.AgentStepTraceRepository;
import interview.guide.modules.agent.support.AgentToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Agent 轨迹服务。
 */
@Service
@RequiredArgsConstructor
public class AgentTraceService {

    private final AgentStepTraceRepository traceRepository;
    private final ObjectMapper objectMapper;

    public int nextStepIndex(String sessionId) {
        return (int) traceRepository.countBySession_SessionId(sessionId) + 1;
    }

    @Transactional
    public AgentStepTraceEntity startStep(
        AgentSessionEntity session,
        int stepIndex,
        String decisionSummary,
        String selectedTool,
        Map<String, Object> toolInput
    ) {
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setSession(session);
        trace.setStepIndex(stepIndex);
        trace.setDecisionSummary(decisionSummary);
        trace.setSelectedTool(selectedTool);
        trace.setToolInputJson(writeJson(toolInput));
        trace.setStatus(AgentExecutionState.RUNNING);
        return traceRepository.save(trace);
    }

    @Transactional
    public void completeStep(AgentStepTraceEntity trace, AgentToolResult result) {
        trace.setToolOutputJson(writeJson(result.output()));
        trace.setObservationSummary(result.summary());
        trace.setStatus(AgentExecutionState.COMPLETED);
        traceRepository.save(trace);
    }

    @Transactional
    public void failStep(AgentStepTraceEntity trace, Exception error) {
        trace.setStatus(AgentExecutionState.FAILED);
        trace.setErrorMessage(sanitize(error));
        traceRepository.save(trace);
    }

    @Transactional
    public void recordDecisionFailure(
        AgentSessionEntity session,
        int stepIndex,
        String decisionSummary,
        Exception error
    ) {
        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setSession(session);
        trace.setStepIndex(stepIndex);
        trace.setDecisionSummary(decisionSummary);
        trace.setSelectedTool("decision_fallback");
        trace.setStatus(AgentExecutionState.FAILED);
        trace.setErrorMessage(sanitize(error));
        traceRepository.save(trace);
    }

    public List<AgentTraceDTO> getTrace(String sessionId) {
        return traceRepository.findBySession_SessionIdOrderByStepIndexAsc(sessionId).stream()
            .map(trace -> new AgentTraceDTO(
                trace.getStepIndex(),
                trace.getDecisionSummary(),
                trace.getSelectedTool(),
                trace.getToolInputJson(),
                trace.getToolOutputJson(),
                trace.getObservationSummary(),
                trace.getStatus(),
                trace.getErrorMessage(),
                trace.getCreatedAt()
            ))
            .toList();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"json_write_failed\"}";
        }
    }

    private String sanitize(Exception error) {
        if (error == null || error.getMessage() == null) {
            return "unknown_error";
        }
        String message = error.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }
}
