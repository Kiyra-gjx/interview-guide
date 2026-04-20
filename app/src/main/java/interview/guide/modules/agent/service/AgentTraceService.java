package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.repository.AgentSessionRepository;
import interview.guide.modules.agent.repository.AgentStepTraceRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
import interview.guide.modules.agent.support.AgentToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Agent trace 服务。
 */
@Service
@RequiredArgsConstructor
public class AgentTraceService {

    private static final int SUMMARY_LIMIT = 500;
    private static final int TOOL_LIMIT = 100;
    private static final int ERROR_LIMIT = 1000;

    private final AgentStepTraceRepository traceRepository;
    private final AgentSessionRepository sessionRepository;
    private final AgentTurnRepository turnRepository;
    private final ObjectMapper objectMapper;

    public int estimateNextStepIndex(String sessionId) {
        return traceRepository.findTopBySession_SessionIdOrderByStepIndexDesc(sessionId)
            .map(AgentStepTraceEntity::getStepIndex)
            .orElse(0) + 1;
    }

    @Transactional
    public AgentStepTraceEntity recordDirectReply(
        String turnId,
        String decisionSummary,
        String reply
    ) {
        AgentStepTraceEntity trace = newTrace(turnId, decisionSummary, "direct_answer", null);
        trace.setToolOutputJson(writeJson(Map.of("reply", blankToEmpty(reply))));
        trace.setObservationSummary(clip(reply, SUMMARY_LIMIT));
        trace.setStatus(AgentExecutionState.COMPLETED);
        return traceRepository.save(trace);
    }

    @Transactional
    public AgentStepTraceEntity recordRejectedToolDecision(
        String turnId,
        String decisionSummary,
        String selectedTool,
        Map<String, Object> toolInput,
        String errorMessage,
        String reply
    ) {
        AgentStepTraceEntity trace = newTrace(turnId, decisionSummary, selectedTool, toolInput);
        trace.setToolOutputJson(writeJson(Map.of("reply", blankToEmpty(reply))));
        trace.setObservationSummary("工具调用已降级为直接回复");
        trace.setStatus(AgentExecutionState.FAILED);
        trace.setErrorMessage(clip(blankToEmpty(errorMessage), ERROR_LIMIT));
        return traceRepository.save(trace);
    }

    @Transactional
    public AgentStepTraceEntity startToolStep(
        String turnId,
        String decisionSummary,
        String selectedTool,
        Map<String, Object> toolInput
    ) {
        AgentStepTraceEntity trace = newTrace(turnId, decisionSummary, selectedTool, toolInput);
        trace.setStatus(AgentExecutionState.RUNNING);
        return traceRepository.save(trace);
    }

    @Transactional
    public void completeToolStep(AgentStepTraceEntity trace, AgentToolResult result) {
        trace.setToolOutputJson(writeJson(result.output()));
        trace.setObservationSummary(clip(result.summary(), SUMMARY_LIMIT));
        trace.setStatus(AgentExecutionState.COMPLETED);
        traceRepository.save(trace);
    }

    @Transactional
    public void failToolStep(AgentStepTraceEntity trace, Exception error, String reply) {
        trace.setToolOutputJson(writeJson(Map.of("reply", blankToEmpty(reply))));
        trace.setObservationSummary("Tool execution failed and fell back to a direct reply");
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

    // Allocate stepIndex at persistence time so remote calls stay outside the transaction.
    private AgentStepTraceEntity newTrace(
        String turnId,
        String decisionSummary,
        String selectedTool,
        Map<String, Object> toolInput
    ) {
        AgentTurnEntity turn = getTurnEntity(turnId);
        AgentSessionEntity session = turn.getSession();
        lockSession(session.getSessionId());

        AgentStepTraceEntity trace = new AgentStepTraceEntity();
        trace.setSession(session);
        trace.setTurn(turn);
        trace.setStepIndex(nextStepIndex(session.getSessionId()));
        trace.setDecisionSummary(clip(decisionSummary, SUMMARY_LIMIT));
        trace.setSelectedTool(clip(selectedTool, TOOL_LIMIT));
        trace.setToolInputJson(writeJson(toolInput));
        return trace;
    }

    private AgentTurnEntity getTurnEntity(String turnId) {
        return turnRepository.findByTurnId(turnId)
            .orElseThrow(() -> new IllegalStateException("Agent turn not found: " + turnId));
    }

    private void lockSession(String sessionId) {
        sessionRepository.findBySessionIdForUpdate(sessionId);
    }

    private int nextStepIndex(String sessionId) {
        return traceRepository.findTopBySession_SessionIdOrderByStepIndexDesc(sessionId)
            .map(AgentStepTraceEntity::getStepIndex)
            .orElse(0) + 1;
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
        return clip(message, ERROR_LIMIT);
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength
            ? normalized.substring(0, maxLength) + "..."
            : normalized;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
