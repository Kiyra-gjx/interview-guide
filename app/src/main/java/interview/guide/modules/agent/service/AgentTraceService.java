package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.repository.AgentSessionRepository;
import interview.guide.modules.agent.repository.AgentStepTraceRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
import interview.guide.modules.agent.support.AgentToolResult;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Agent trace 持久化服务。
 * 负责记录直接回复、工具调用、降级分支等执行轨迹，便于后续排障和展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceService {

    private static final int SUMMARY_LIMIT = 500;
    private static final int TOOL_LIMIT = 100;
    private static final int ERROR_LIMIT = 1000;
    private static final String GUARDRAIL_RESULTS_WRITE_FAILURE_REASON = "guardrail_results_write_failed";
    private static final String GUARDRAIL_RESULTS_READ_FAILURE_REASON = "guardrail_results_read_failed";
    private static final String MEMORY_SNAPSHOT_WRITE_FAILURE_JSON = """
{"userGoal":"","currentPhase":"memory_snapshot_unavailable","confirmedFacts":["memory_snapshot_write_failed"],"usedTools":[],"nextFocus":"请检查 trace 数据完整性"}
""";
    private static final String GUARDRAIL_RESULTS_WRITE_FAILURE_JSON = """
[{"reason":"guardrail_results_write_failed"}]
""";
    private static final AgentMemorySnapshot MEMORY_SNAPSHOT_READ_FAILURE = new AgentMemorySnapshot(
        "",
        "memory_snapshot_unavailable",
        List.of("memory_snapshot_read_failed"),
        List.of(),
        "请检查 trace 数据完整性"
    );
    private static final List<AgentGuardrailResult> GUARDRAIL_RESULTS_READ_FAILURE = List.of(
        new AgentGuardrailResult(null, null, null, null, GUARDRAIL_RESULTS_READ_FAILURE_REASON)
    );

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
        String reply,
        AgentMemorySnapshot memoryBefore,
        AgentMemorySnapshot memoryAfter,
        List<AgentGuardrailResult> guardrailResults
    ) {
        AgentStepTraceEntity trace = newTrace(turnId, decisionSummary, "direct_answer", null, memoryBefore);
        trace.setToolOutputJson(writeJson(buildReplyTracePayload(
            "direct_reply",
            "已直接生成最终回复",
            reply
        )));
        trace.setObservationSummary(clip(reply, SUMMARY_LIMIT));
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
        trace.setGuardrailResultsJson(writeGuardrailResultsJson(guardrailResults));
        trace.setStatus(AgentExecutionState.COMPLETED);
        return traceRepository.save(trace);
    }

    /**
     * 记录输入 Guardrail 命中后的拒绝轨迹。
     */
    @Transactional
    public AgentStepTraceEntity recordInputGuardrailRejection(
        String turnId,
        String decisionSummary,
        String reply,
        AgentMemorySnapshot memoryBefore,
        AgentMemorySnapshot memoryAfter,
        List<AgentGuardrailResult> guardrailResults
    ) {
        AgentStepTraceEntity trace = newTrace(turnId, decisionSummary, "input_guardrail", null, memoryBefore);
        trace.setToolOutputJson(writeJson(buildReplyTracePayload(
            "input_guardrail_rejection",
            "输入触发安全拦截，已返回安全回复",
            reply
        )));
        trace.setObservationSummary("输入触发安全拦截，已返回安全回复");
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
        trace.setGuardrailResultsJson(writeGuardrailResultsJson(guardrailResults));
        trace.setStatus(AgentExecutionState.FAILED);
        trace.setErrorMessage(resolvePrimaryGuardrailReason(guardrailResults));
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
        String reply,
        AgentMemorySnapshot memoryBefore,
        AgentMemorySnapshot memoryAfter,
        List<AgentGuardrailResult> guardrailResults
    ) {
        AgentStepTraceEntity trace = newTrace(turnId, decisionSummary, selectedTool, toolInput, memoryBefore);
        trace.setToolOutputJson(writeJson(buildReplyTracePayload(
            "rejected_tool_decision",
            "工具决策不可执行，已降级为直接回复",
            reply
        )));
        trace.setObservationSummary("工具决策不可执行，已降级为直接回复");
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
        trace.setGuardrailResultsJson(writeGuardrailResultsJson(guardrailResults));
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
        Map<String, Object> toolInput,
        AgentMemorySnapshot memoryBefore
    ) {
        AgentStepTraceEntity trace = newTrace(turnId, decisionSummary, selectedTool, toolInput, memoryBefore);
        trace.setStatus(AgentExecutionState.RUNNING);
        return traceRepository.save(trace);
    }

    /**
     * 将工具执行结果回填到 trace。
     */
    @Transactional
    public void completeToolStep(
        AgentStepTraceEntity trace,
        AgentToolResult result,
        AgentMemorySnapshot memoryAfter,
        String reply,
        List<AgentGuardrailResult> guardrailResults
    ) {
        trace.setToolOutputJson(writeJson(result.tracePayload(reply)));
        trace.setObservationSummary(clip(result.summary(), SUMMARY_LIMIT));
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
        trace.setGuardrailResultsJson(writeGuardrailResultsJson(guardrailResults));
        trace.setStatus(AgentExecutionState.COMPLETED);
        traceRepository.save(trace);
    }

    /**
     * 记录工具执行失败结果，并保存面向用户的降级回复。
     */
    @Transactional
    public void failToolStep(
        AgentStepTraceEntity trace,
        Exception error,
        String reply,
        AgentMemorySnapshot memoryAfter,
        String failureKind,
        String observationSummary
    ) {
        trace.setToolOutputJson(writeJson(buildReplyTracePayload(
            failureKind,
            observationSummary,
            reply
        )));
        trace.setObservationSummary(observationSummary);
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
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
            .map(this::toTraceDTO)
            .toList();
    }

    /**
     * 查询指定 turn 的 trace 增量。
     */
    public List<AgentTraceDTO> getTurnTrace(String turnId) {
        getTurnEntity(turnId);
        return traceRepository.findByTurn_TurnIdOrderByStepIndexAsc(turnId).stream()
            .map(this::toTraceDTO)
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
        Map<String, Object> toolInput,
        AgentMemorySnapshot memoryBefore
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
        trace.setMemoryBeforeJson(writeMemorySnapshotJson(memoryBefore));
        return trace;
    }

    private AgentTraceDTO toTraceDTO(AgentStepTraceEntity trace) {
        return new AgentTraceDTO(
            trace.getStepIndex(),
            trace.getDecisionSummary(),
            trace.getSelectedTool(),
            trace.getToolInputJson(),
            trace.getToolOutputJson(),
            trace.getObservationSummary(),
            readMemorySnapshot(trace.getMemoryBeforeJson()),
            readMemorySnapshot(trace.getMemoryAfterJson()),
            readGuardrailResults(trace.getGuardrailResultsJson()),
            trace.getStatus(),
            trace.getErrorMessage(),
            trace.getCreatedAt()
        );
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
            log.warn("Agent trace JSON 写入失败: valueType={}, error={}",
                value.getClass().getSimpleName(), e.getMessage());
            return "{\"error\":\"json_write_failed\"}";
        }
    }

    private String writeMemorySnapshotJson(AgentMemorySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.warn("Agent memory snapshot 写入失败: phase={}, error={}",
                snapshot.currentPhase(), e.getMessage());
            return MEMORY_SNAPSHOT_WRITE_FAILURE_JSON;
        }
    }

    private AgentMemorySnapshot readMemorySnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AgentMemorySnapshot.class);
        } catch (Exception e) {
            log.warn("Agent memory snapshot 读取失败: json={}, error={}", clip(json, 120), e.getMessage());
            return MEMORY_SNAPSHOT_READ_FAILURE;
        }
    }

    private String writeGuardrailResultsJson(List<AgentGuardrailResult> guardrailResults) {
        if (guardrailResults == null || guardrailResults.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(guardrailResults);
        } catch (Exception e) {
            log.warn("Agent guardrail 结果写入失败: count={}, error={}", guardrailResults.size(), e.getMessage());
            return GUARDRAIL_RESULTS_WRITE_FAILURE_JSON;
        }
    }

    private List<AgentGuardrailResult> readGuardrailResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        if (json.contains(GUARDRAIL_RESULTS_WRITE_FAILURE_REASON)) {
            return List.of(new AgentGuardrailResult(null, null, null, null, GUARDRAIL_RESULTS_WRITE_FAILURE_REASON));
        }
        try {
            List<AgentGuardrailResult> results = objectMapper.readValue(json, new TypeReference<>() {
            });
            return results == null ? List.of() : List.copyOf(results);
        } catch (Exception e) {
            log.warn("Agent guardrail 结果读取失败: json={}, error={}", clip(json, 120), e.getMessage());
            return GUARDRAIL_RESULTS_READ_FAILURE;
        }
    }

    private String resolvePrimaryGuardrailReason(List<AgentGuardrailResult> guardrailResults) {
        if (guardrailResults == null || guardrailResults.isEmpty()) {
            return null;
        }
        return clip(guardrailResults.getFirst().reason(), ERROR_LIMIT);
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

    private Map<String, Object> buildReplyTracePayload(
        String kind,
        String summary,
        String reply
    ) {
        return Map.of(
            "kind", kind,
            "summary", blankToEmpty(summary),
            "reply", blankToEmpty(reply),
            "answerPayload", Map.of(),
            "debugPayload", Map.of(),
            "confirmedFacts", List.of()
        );
    }
}
