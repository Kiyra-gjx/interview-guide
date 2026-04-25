package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentToolOutputDTO;
import interview.guide.modules.agent.model.AgentToolOutputNormalizationDTO;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
     * 将工具步骤推进到等待审批状态。
     */
    @Transactional
    public void markToolStepWaitingApproval(
        AgentStepTraceEntity trace,
        AgentApprovalDTO approval,
        String reply,
        AgentMemorySnapshot memoryAfter,
        List<AgentGuardrailResult> guardrailResults
    ) {
        trace.setToolOutputJson(writeJson(buildApprovalTracePayload(
            "approval_pending",
            "高风险工具已进入审批，等待显式决策",
            reply,
            approval
        )));
        trace.setObservationSummary("高风险工具已进入审批，等待显式决策");
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
        trace.setGuardrailResultsJson(writeGuardrailResultsJson(guardrailResults));
        trace.setStatus(AgentExecutionState.WAITING_APPROVAL);
        trace.setErrorMessage(null);
        traceRepository.save(trace);
    }

    /**
     * 将等待审批的工具步骤推进到 rejected 终态。
     */
    @Transactional
    public void markToolStepApprovalRejected(
        AgentStepTraceEntity trace,
        AgentApprovalDTO approval,
        String reply,
        AgentMemorySnapshot memoryAfter
    ) {
        markToolStepApprovalTerminal(
            trace,
            approval,
            reply,
            memoryAfter,
            "approval_rejected",
            "审批已拒绝，本轮未执行高风险工具"
        );
    }

    /**
     * 将等待审批的工具步骤推进到 expired 终态。
     */
    @Transactional
    public void markToolStepApprovalExpired(
        AgentStepTraceEntity trace,
        AgentApprovalDTO approval,
        String reply,
        AgentMemorySnapshot memoryAfter
    ) {
        markToolStepApprovalTerminal(
            trace,
            approval,
            reply,
            memoryAfter,
            "approval_expired",
            "审批已过期，本轮未执行高风险工具"
        );
    }

    /**
     * 将审批通过后的工具执行结果回填到原 trace。
     */
    @Transactional
    public void markApprovedToolExecutionStarted(AgentStepTraceEntity trace, AgentApprovalDTO approval) {
        trace.setToolOutputJson(writeJson(buildApprovalTracePayload(
            "approval_execution_started",
            "审批已通过，开始执行高风险工具",
            "",
            approval
        )));
        trace.setObservationSummary("审批已通过，开始执行高风险工具");
        trace.setStatus(AgentExecutionState.RUNNING);
        trace.setErrorMessage(null);
        traceRepository.save(trace);
    }

    @Transactional
    public void completeApprovedToolStep(
        AgentStepTraceEntity trace,
        AgentApprovalDTO approval,
        AgentToolResult result,
        AgentMemorySnapshot memoryAfter,
        String reply,
        List<AgentGuardrailResult> guardrailResults,
        AgentCompletionMode completionMode
    ) {
        trace.setToolOutputJson(writeJson(buildApprovedToolTracePayload(result, reply, approval, completionMode)));
        trace.setObservationSummary(clip(result.summary(), SUMMARY_LIMIT));
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
        trace.setGuardrailResultsJson(writeGuardrailResultsJson(guardrailResults));
        trace.setStatus(AgentExecutionState.COMPLETED);
        trace.setErrorMessage(null);
        traceRepository.save(trace);
    }

    /**
     * 审批通过后如果工具执行或后处理失败，也要保留审批结果与失败原因。
     */
    @Transactional
    public void failApprovedToolStep(
        AgentStepTraceEntity trace,
        AgentApprovalDTO approval,
        Exception error,
        String reply,
        AgentMemorySnapshot memoryAfter,
        String failureKind,
        String observationSummary
    ) {
        trace.setToolOutputJson(writeJson(buildApprovalTracePayload(
            failureKind,
            observationSummary,
            reply,
            approval,
            AgentCompletionMode.DEGRADED
        )));
        trace.setObservationSummary(observationSummary);
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
        trace.setStatus(AgentExecutionState.FAILED);
        trace.setErrorMessage(sanitize(error));
        traceRepository.save(trace);
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
    public String readLatestReply(String turnId) {
        getTurnEntity(turnId);
        return traceRepository.findByTurn_TurnIdOrderByStepIndexAsc(turnId).stream()
            .map(AgentStepTraceEntity::getToolOutputJson)
            .map(this::readReplyFromToolOutput)
            .filter(reply -> reply != null && !reply.isBlank())
            .reduce((first, second) -> second)
            .orElse("");
    }

    public ApprovedExecutionRecovery readApprovedExecutionRecovery(AgentStepTraceEntity trace) {
        if (trace == null) {
            return new ApprovedExecutionRecovery(AgentExecutionState.CREATED, "", null, null);
        }
        return new ApprovedExecutionRecovery(
            trace.getStatus(),
            readReplyFromToolOutput(trace.getToolOutputJson()),
            readMemorySnapshot(trace.getMemoryAfterJson()),
            readCompletionMode(trace.getToolOutputJson())
        );
    }

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
            readToolOutput(trace.getToolOutputJson()),
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

    private String readReplyFromToolOutput(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {
            });
            Object reply = payload.get("reply");
            return reply == null ? "" : reply.toString().trim();
        } catch (Exception e) {
            log.warn("Agent trace reply read failed: json={}, error={}", clip(json, 120), e.getMessage());
            return "";
        }
    }

    /**
     * 从持久化的 trace JSON 中恢复统一 Tool 输出视图。
     * 兼容新字段名与历史 legacy 字段名，避免旧 trace 无法读取。
     */
    private AgentToolOutputDTO readToolOutput(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            // .1 先读取原始 JSON，并优先按新字段名解析；历史字段名作为兼容回退。
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (payload == null || payload.isEmpty()) {
                return null;
            }
            return new AgentToolOutputDTO(
                readString(payload.get("kind")),
                readString(payload.get("summary")),
                readString(payload.get("reply")),
                readObjectMap(firstNonNull(payload.get("answer"), payload.get("answerPayload"))),
                readObjectMap(firstNonNull(payload.get("debug"), payload.get("debugPayload"))),
                readStringList(firstNonNull(payload.get("facts"), payload.get("confirmedFacts"))),
                readNormalization(payload.get("normalization"))
            );
        } catch (Exception e) {
            // .2 解析失败时返回显式占位结构，避免上层把“读取失败”误判成“没有输出”。
            log.warn("Agent trace toolOutput read failed: json={}, error={}", clip(json, 120), e.getMessage());
            return new AgentToolOutputDTO(
                "tool_output_unavailable",
                "tool_output_read_failed",
                "",
                Map.of(),
                Map.of(),
                List.of(),
                new AgentToolOutputNormalizationDTO(false, false, false, false)
            );
        }
    }

    private AgentCompletionMode readCompletionMode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {
            });
            Object completionMode = payload.get("completionMode");
            if (completionMode == null) {
                return null;
            }
            String normalized = completionMode.toString().trim();
            return normalized.isEmpty() ? null : AgentCompletionMode.valueOf(normalized);
        } catch (Exception e) {
            log.warn("Agent trace completionMode read failed: json={}, error={}", clip(json, 120), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildReplyTracePayload(
        String kind,
        String summary,
        String reply
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("summary", blankToEmpty(summary));
        payload.put("reply", blankToEmpty(reply));
        payload.put("answer", Map.of());
        payload.put("debug", Map.of());
        payload.put("facts", List.of());
        payload.put("normalization", emptyNormalizationPayload());
        return payload;
    }

    private Map<String, Object> buildApprovalTracePayload(
        String kind,
        String summary,
        String reply,
        AgentApprovalDTO approval
    ) {
        return buildApprovalTracePayload(kind, summary, reply, approval, null);
    }

    private Map<String, Object> buildApprovalTracePayload(
        String kind,
        String summary,
        String reply,
        AgentApprovalDTO approval,
        AgentCompletionMode completionMode
    ) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("summary", blankToEmpty(summary));
        payload.put("reply", blankToEmpty(reply));
        payload.put("approval", approvalPayload(approval));
        payload.put("answer", Map.of());
        payload.put("debug", Map.of());
        payload.put("facts", List.of());
        payload.put("normalization", emptyNormalizationPayload());
        if (completionMode != null) {
            payload.put("completionMode", completionMode.name());
        }
        return payload;
    }

    private Map<String, Object> buildApprovedToolTracePayload(
        AgentToolResult result,
        String reply,
        AgentApprovalDTO approval,
        AgentCompletionMode completionMode
    ) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(result.tracePayload(reply));
        payload.put("approval", approvalPayload(approval));
        if (completionMode != null) {
            payload.put("completionMode", completionMode.name());
        }
        return payload;
    }

    private Map<String, Object> approvalPayload(AgentApprovalDTO approval) {
        if (approval == null) {
            return Map.of();
        }
        return Map.of(
            "approvalId", blankToEmpty(approval.approvalId()),
            "status", approval.status() == null ? "" : approval.status().name(),
            "riskLevel", approval.riskLevel() == null ? "" : approval.riskLevel().name(),
            "expiresAt", approval.expiresAt() == null ? "" : approval.expiresAt().toString()
        );
    }

    /**
     * 为非 Tool 结果类 trace 构造默认的归一化标记。
     */
    private Map<String, Object> emptyNormalizationPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summaryTruncated", false);
        payload.put("answerTruncated", false);
        payload.put("debugTruncated", false);
        payload.put("factsTruncated", false);
        return payload;
    }

    /**
     * 把可空对象安全转为字符串。
     */
    private String readString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /**
     * 把 JSON Map 深拷贝为只读结构，避免响应层误改解析结果。
     */
    private Map<String, Object> readObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copied.put(String.valueOf(entry.getKey()), copyJsonValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
    }

    /**
     * 把 JSON 数组安全转换为字符串列表。
     */
    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> copied = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = item.toString().trim();
            if (!text.isEmpty()) {
                copied.add(text);
            }
        }
        return Collections.unmodifiableList(copied);
    }

    /**
     * 读取持久化的归一化元数据；缺失时退回默认值。
     */
    private AgentToolOutputNormalizationDTO readNormalization(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new AgentToolOutputNormalizationDTO(false, false, false, false);
        }
        return new AgentToolOutputNormalizationDTO(
            readBoolean(map, "summaryTruncated"),
            readBoolean(map, "answerTruncated"),
            readBoolean(map, "debugTruncated"),
            readBoolean(map, "factsTruncated")
        );
    }

    /**
     * 从宽松 JSON 值里读取布尔标记。
     */
    private boolean readBoolean(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return false;
    }

    /**
     * 深拷贝 JSON 树，保证 DTO 暴露出去的是稳定快照。
     */
    private Object copyJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copied.put(String.valueOf(entry.getKey()), copyJsonValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            for (Object item : list) {
                copied.add(copyJsonValue(item));
            }
            return Collections.unmodifiableList(copied);
        }
        return value;
    }

    /**
     * 返回两个候选值中的第一个非空值。
     */
    private Object firstNonNull(Object preferred, Object fallback) {
        return preferred != null ? preferred : fallback;
    }

    private void markToolStepApprovalTerminal(
        AgentStepTraceEntity trace,
        AgentApprovalDTO approval,
        String reply,
        AgentMemorySnapshot memoryAfter,
        String kind,
        String summary
    ) {
        trace.setToolOutputJson(writeJson(buildApprovalTracePayload(kind, summary, reply, approval)));
        trace.setObservationSummary(summary);
        trace.setMemoryAfterJson(writeMemorySnapshotJson(memoryAfter));
        trace.setStatus(AgentExecutionState.FAILED);
        trace.setErrorMessage(approval == null ? null : clip(approval.reason(), ERROR_LIMIT));
        traceRepository.save(trace);
    }

    public record ApprovedExecutionRecovery(
        AgentExecutionState status,
        String reply,
        AgentMemorySnapshot memoryAfter,
        AgentCompletionMode completionMode
    ) {
    }
}
