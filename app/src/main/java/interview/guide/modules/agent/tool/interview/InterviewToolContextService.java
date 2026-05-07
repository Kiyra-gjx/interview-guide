package interview.guide.modules.agent.tool.interview;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Interview 工具共享上下文解析服务。
 * 统一处理 sessionId/resumeId 兜底、权限一致性校验和最近可用面试选择。
 */
@Service
@RequiredArgsConstructor
public class InterviewToolContextService {

    private static final int DEFAULT_HISTORY_LIMIT = 5;
    private static final int MIN_HISTORY_LIMIT = 1;
    private static final int MAX_HISTORY_LIMIT = 10;
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    private final InterviewPersistenceService interviewPersistenceService;
    private final InterviewHistoryService interviewHistoryService;

    /**
     * 解析历史概览所需的数据源。
     */
    public HistorySummarySource loadHistorySummarySource(Map<String, Object> input, AgentToolContext context) {
        // 1. 历史概览只需要 resumeId；模型没传时允许从 Agent 会话上下文兜底。
        Long explicitResumeId = readOptionalLong(input, "resumeId");
        Long contextResumeId = contextResumeId(context);
        Long resumeId = requireResolvedResumeId(explicitResumeId != null ? explicitResumeId : contextResumeId, "resumeId");
        boolean usedFallback = explicitResumeId == null && contextResumeId != null;
        int limit = readHistoryLimit(input);

        // 2. 面试列表统一按创建时间倒序归一化，再按 limit 裁出最近窗口。
        List<InterviewSessionEntity> allSessions = normalizeSessions(interviewPersistenceService.findByResumeId(resumeId));
        List<InterviewSessionEntity> recentSessions = allSessions.stream()
            .limit(limit)
            .toList();

        // 3. 返回时同时带上 fallback 信息，方便 trace/workbench 解释数据来源。
        return new HistorySummarySource(
            resumeId,
            recentSessions,
            findLatestEvaluatedConclusion(allSessions),
            usedFallback,
            usedFallback ? "resume_id_from_context" : null,
            limit,
            allSessions.size(),
            countEvaluatedSessions(allSessions),
            countUnfinishedSessions(allSessions)
        );
    }

    /**
     * 解析短板分析所需的数据源。
     */
    public AnalysisSource loadGapAnalysisSource(Map<String, Object> input, AgentToolContext context) {
        String sessionId = readOptionalString(input, "sessionId");
        if (sessionId != null) {
            // 1. 显式 sessionId 优先，并校验它确实属于传入的 resumeId。
            Long explicitResumeId = readOptionalLong(input, "resumeId");
            InterviewSessionEntity session = requireOwnedSession(sessionId, explicitResumeId);
            Long resumeId = sessionResumeId(session);
            if (!isEvaluated(session)) {
                return new AnalysisSource(resumeId, sessionId, session, null, false, "session_not_evaluated");
            }
            return buildDetailSource(resumeId, session, false, null);
        }

        // 2. 未指定 sessionId 时，从 resumeId 下选择最近一次已评估面试作为分析对象。
        Long resumeId = requireResolvedResumeId(resolveResumeId(input, context), "resumeId");
        InterviewSessionEntity session = normalizeSessions(interviewPersistenceService.findByResumeId(resumeId)).stream()
            .filter(this::isEvaluated)
            .findFirst()
            .orElse(null);
        if (session == null) {
            return AnalysisSource.empty(resumeId, true, "no_evaluated_session");
        }
        return buildDetailSource(resumeId, session, true, "latest_evaluated_session");
    }

    /**
     * 解析追问建议所需的数据源。
     */
    public AnalysisSource loadFollowUpSource(Map<String, Object> input, AgentToolContext context) {
        String sessionId = readOptionalString(input, "sessionId");
        if (sessionId != null) {
            // 1. 显式 sessionId 场景下，允许未评估但已有题目数据的面试进入追问规划。
            Long explicitResumeId = readOptionalLong(input, "resumeId");
            InterviewSessionEntity session = requireOwnedSession(sessionId, explicitResumeId);
            Long resumeId = sessionResumeId(session);
            if (!isEvaluated(session) && !hasQuestionData(session)) {
                return new AnalysisSource(resumeId, sessionId, session, null, false, "session_has_no_question_data");
            }
            return buildDetailSource(resumeId, session, false, null);
        }

        // 2. 自动选择时优先用已评估面试，因为它能提供分数和改进建议。
        Long resumeId = requireResolvedResumeId(resolveResumeId(input, context), "resumeId");
        List<InterviewSessionEntity> sessions = normalizeSessions(interviewPersistenceService.findByResumeId(resumeId));

        InterviewSessionEntity evaluatedSession = sessions.stream()
            .filter(this::isEvaluated)
            .findFirst()
            .orElse(null);
        if (evaluatedSession != null) {
            return buildDetailSource(resumeId, evaluatedSession, true, "latest_evaluated_session");
        }

        // 3. 没有已评估面试时，再退到最近一次有题目数据的面试，至少能生成围绕题目的追问。
        InterviewSessionEntity questionSession = sessions.stream()
            .filter(this::hasQuestionData)
            .findFirst()
            .orElse(null);
        if (questionSession == null) {
            return AnalysisSource.empty(resumeId, true, "no_session_with_questions");
        }
        return buildDetailSource(resumeId, questionSession, true, "latest_session_with_questions");
    }

    private AnalysisSource buildDetailSource(
        Long resumeId,
        InterviewSessionEntity session,
        boolean usedFallback,
        String fallbackReason
    ) {
        String sessionId = session.getSessionId();
        InterviewDetailDTO detail = interviewHistoryService.getInterviewDetail(sessionId);
        return new AnalysisSource(resumeId, sessionId, session, detail, usedFallback, fallbackReason);
    }

    private InterviewSessionEntity requireOwnedSession(String sessionId, Long resumeId) {
        InterviewSessionEntity session = interviewPersistenceService.findBySessionIdWithResume(sessionId)
            .orElseThrow(() -> invalidInput("sessionId", "引用的面试会话不存在"));
        if (resumeId != null && !resumeId.equals(sessionResumeId(session))) {
            throw invalidInput("resumeId", "sessionId 与 resumeId 不匹配");
        }
        return session;
    }

    private Long requireResolvedResumeId(Long resumeId, String fieldName) {
        if (resumeId == null) {
            throw invalidInput(fieldName, fieldName + " 缺失");
        }
        return resumeId;
    }

    private Long resolveResumeId(Map<String, Object> input, AgentToolContext context) {
        Long explicitResumeId = readOptionalLong(input, "resumeId");
        if (explicitResumeId != null) {
            return explicitResumeId;
        }
        return contextResumeId(context);
    }

    private Long contextResumeId(AgentToolContext context) {
        return context == null ? null : context.resumeId();
    }

    private Long readOptionalLong(Map<String, Object> input, String fieldName) {
        Object value = safeInput(input).get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return parseNumberAsLong(number, fieldName);
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ex) {
                throw invalidInput(fieldName, fieldName + " 不是合法数字");
            }
        }
        throw invalidInput(fieldName, fieldName + " 类型无效");
    }

    private Long parseNumberAsLong(Number number, String fieldName) {
        if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)) {
            throw invalidInput(fieldName, fieldName + " 不是合法整数");
        }
        if (number instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw invalidInput(fieldName, fieldName + " 不是合法整数");
        }

        BigDecimal decimal = toBigDecimal(number, fieldName);
        if (decimal.compareTo(LONG_MIN) < 0 || decimal.compareTo(LONG_MAX) > 0) {
            throw invalidInput(fieldName, fieldName + " 超出范围");
        }
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw invalidInput(fieldName, fieldName + " 不是合法整数");
        }
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException ex) {
            throw invalidInput(fieldName, fieldName + " 超出范围");
        }
    }

    private BigDecimal toBigDecimal(Number number, String fieldName) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException ex) {
            throw invalidInput(fieldName, fieldName + " 不是合法整数");
        }
    }

    private String readOptionalString(Map<String, Object> input, String fieldName) {
        Object value = safeInput(input).get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        throw invalidInput(fieldName, fieldName + " 类型无效");
    }

    private int readHistoryLimit(Map<String, Object> input) {
        Long limit = readOptionalLong(input, "limit");
        if (limit == null) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return (int) Math.max(MIN_HISTORY_LIMIT, Math.min(MAX_HISTORY_LIMIT, limit));
    }

    private Map<String, Object> safeInput(Map<String, Object> input) {
        return input == null ? Map.of() : input;
    }

    private List<InterviewSessionEntity> normalizeSessions(List<InterviewSessionEntity> sessions) {
        return sessions == null ? List.of() : List.copyOf(sessions);
    }

    private Long sessionResumeId(InterviewSessionEntity session) {
        return session.getResume() == null ? null : session.getResume().getId();
    }

    private LatestEvaluatedConclusion findLatestEvaluatedConclusion(List<InterviewSessionEntity> sessions) {
        return sessions.stream()
            .filter(this::isEvaluated)
            .map(this::toLatestEvaluatedConclusion)
            .filter(conclusion -> conclusion != null)
            .findFirst()
            .orElse(null);
    }

    private LatestEvaluatedConclusion toLatestEvaluatedConclusion(InterviewSessionEntity session) {
        if (session == null) {
            return null;
        }
        Integer overallScore = session.getOverallScore();
        String overallFeedback = normalizeText(session.getOverallFeedback());
        if (overallScore == null && overallFeedback == null) {
            return null;
        }
        return new LatestEvaluatedConclusion(
            session.getSessionId(),
            overallScore,
            overallFeedback,
            session.getCompletedAt()
        );
    }

    private boolean isEvaluated(InterviewSessionEntity session) {
        return session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED;
    }

    private boolean hasQuestionData(InterviewSessionEntity session) {
        String questionsJson = session.getQuestionsJson();
        return questionsJson != null && !questionsJson.isBlank();
    }

    private int countEvaluatedSessions(List<InterviewSessionEntity> sessions) {
        return (int) sessions.stream()
            .filter(this::isEvaluated)
            .count();
    }

    private int countUnfinishedSessions(List<InterviewSessionEntity> sessions) {
        return (int) sessions.stream()
            .filter(session -> session.getStatus() == InterviewSessionEntity.SessionStatus.CREATED
                || session.getStatus() == InterviewSessionEntity.SessionStatus.IN_PROGRESS)
            .count();
    }

    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BusinessException invalidInput(String fieldName, String message) {
        return new BusinessException(ErrorCode.AGENT_INVALID_INPUT, fieldName + ": " + message);
    }

    /**
     * 历史概览数据源。
     */
    public record HistorySummarySource(
        Long resumeId,
        List<InterviewSessionEntity> sessions,
        LatestEvaluatedConclusion latestEvaluatedConclusion,
        boolean usedFallback,
        String fallbackReason,
        int limit,
        int totalInterviews,
        int evaluatedInterviews,
        int unfinishedInterviews
    ) {
    }

    /**
     * 最近一次已评估面试的稳定结论。
     */
    public record LatestEvaluatedConclusion(
        String sessionId,
        Integer overallScore,
        String overallFeedback,
        LocalDateTime completedAt
    ) {
    }

    /**
     * 分析类工具数据源。
     */
    public record AnalysisSource(
        Long resumeId,
        String sessionId,
        InterviewSessionEntity session,
        InterviewDetailDTO detail,
        boolean usedFallback,
        String fallbackReason
    ) {
        public static AnalysisSource empty(Long resumeId, boolean usedFallback, String fallbackReason) {
            return new AnalysisSource(resumeId, null, null, null, usedFallback, fallbackReason);
        }
    }
}
