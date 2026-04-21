package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
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
 * Agent trace 持久化服务。
 * 负责记录直接回复、工具调用、降级分支等执行轨迹，便于后续排障和展示。
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

    /**
     * 估算会话下一条 trace 的 stepIndex。
     */
    public int estimateNextStepIndex(String sessionId) {
        return traceRepository.findTopBySession_SessionIdOrderByStepIndexDesc(sessionId)
            .map(AgentStepTraceEntity::getStepIndex)
            .orElse(0) + 1;
    }

    /**
     * 记录“直接回复”路径的 trace。
     */
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

    /**
     * 记录“工具决策被拒绝并降级”路径的 trace。
     */
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

    /**
     * 为工具调用创建一条 RUNNING 状态的 trace。
     */
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

    /**
     * 将工具执行结果回填到 trace。
     */
    @Transactional
    public void completeToolStep(AgentStepTraceEntity trace, AgentToolResult result) {
        trace.setToolOutputJson(writeJson(result.tracePayload()));
        trace.setObservationSummary(clip(result.summary(), SUMMARY_LIMIT));
        trace.setStatus(AgentExecutionState.COMPLETED);
        traceRepository.save(trace);
    }

    /**
     * 记录工具执行失败结果，并保存面向用户的降级回复。
     */
    @Transactional
    public void failToolStep(AgentStepTraceEntity trace, Exception error, String reply) {
        trace.setToolOutputJson(writeJson(Map.of("reply", blankToEmpty(reply))));
        trace.setObservationSummary("Tool execution failed and fell back to a direct reply");
        trace.setStatus(AgentExecutionState.FAILED);
        trace.setErrorMessage(sanitize(error));
        traceRepository.save(trace);
    }

    /**
     * 查询会话的完整 trace 列表。
     */
    public List<AgentTraceDTO> getTrace(String sessionId) {
        requireSession(sessionId);
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

    /**
     * 查询指定 turn 的 trace 增量。
     */
    public List<AgentTraceDTO> getTurnTrace(String turnId) {
        getTurnEntity(turnId);
        return traceRepository.findByTurn_TurnIdOrderByStepIndexAsc(turnId).stream()
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

    /**
     * 构造一条新的 trace 实体，并在持久化阶段分配 stepIndex。
     * 这样可以把远程调用放在事务外，只在真正落库时做顺序控制。
     */
    private AgentStepTraceEntity newTrace(
        String turnId,
        String decisionSummary,
        String selectedTool,
        Map<String, Object> toolInput
    ) {
        AgentTurnEntity turn = getTurnEntity(turnId);
        AgentSessionEntity session = turn.getSession();

        // 通过锁住 session，保证同一会话下 stepIndex 分配稳定递增。
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

    /**
     * 根据 turnId 读取 turn 实体。
     */
    private AgentTurnEntity getTurnEntity(String turnId) {
        return turnRepository.findByTurnId(turnId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TURN_NOT_FOUND, "未找到 Agent turn: " + turnId));
    }

    /**
     * 对会话加锁，确保 trace 顺序号分配串行化。
     */
    private void lockSession(String sessionId) {
        sessionRepository.findBySessionIdForUpdate(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    private void requireSession(String sessionId) {
        sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    /**
     * 计算下一条 stepIndex。
     */
    private int nextStepIndex(String sessionId) {
        return traceRepository.findTopBySession_SessionIdOrderByStepIndexDesc(sessionId)
            .map(AgentStepTraceEntity::getStepIndex)
            .orElse(0) + 1;
    }

    /**
     * 将对象序列化为 JSON，用于存 trace 的输入输出快照。
     */
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

    /**
     * 清洗异常信息并控制长度。
     */
    private String sanitize(Exception error) {
        if (error == null || error.getMessage() == null) {
            return "unknown_error";
        }
        String message = error.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
        return clip(message, ERROR_LIMIT);
    }

    /**
     * 截断字符串，避免 trace 字段过长。
     */
    private String clip(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength
            ? normalized.substring(0, maxLength) + "..."
            : normalized;
    }

    /**
     * 将空值统一转为空字符串，方便 trace JSON 输出。
     */
    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
