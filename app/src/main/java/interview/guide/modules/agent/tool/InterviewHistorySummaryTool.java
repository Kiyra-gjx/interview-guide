package interview.guide.modules.agent.tool;

import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.interview.InterviewToolContextService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇总最近几次面试的历史概况。
 * 输出的是趋势与状态概览，具体短板和追问仍交给专门 Tool 处理。
 */
@Component
@RequiredArgsConstructor
public class InterviewHistorySummaryTool implements AgentTool {

    private static final String TREND_NO_DATA = "NO_DATA";
    private static final String TREND_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    private static final String TREND_IMPROVING = "IMPROVING";
    private static final String TREND_DECLINING = "DECLINING";
    private static final String TREND_STABLE = "STABLE";

    private final InterviewToolContextService interviewToolContextService;

    @Override
    public String name() {
        return "get_interview_history_summary";
    }

    @Override
    public String description() {
        return "汇总简历最近几次面试的状态、分数、趋势和未完成情况。输入: { resumeId, limit }";
    }

    @Override
    public List<String> requiredInputs() {
        return List.of("resumeId");
    }

    @Override
    public List<String> allowedInputs() {
        return List.of("resumeId", "limit");
    }

    @Override
    public AgentToolRiskLevel riskLevel() {
        return AgentToolRiskLevel.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        // 1. 统一解析 resumeId、limit 和最近面试列表，避免每个面试工具重复处理上下文兜底。
        InterviewToolContextService.HistorySummarySource source =
            interviewToolContextService.loadHistorySummarySource(input, context);

        // 2. 没有面试记录时仍返回结构化空结果，让模型和前端都能稳定识别 NO_DATA。
        if (source.sessions().isEmpty()) {
            return new AgentToolResult(
                "当前没有面试记录，暂时无法判断分数趋势。",
                buildEmptyAnswerPayload(source),
                buildDebugPayload(source),
                List.of()
            );
        }

        String latestStatus = latestStatusName(source.sessions().getFirst());
        String scoreTrend = resolveScoreTrend(source.sessions());

        // 3. 有数据时同时返回人类摘要和结构化 payload，最终回答与工作台展示各取所需。
        return new AgentToolResult(
            buildSummary(source, scoreTrend),
            buildAnswerPayload(source, latestStatus, scoreTrend),
            buildDebugPayload(source),
            List.of()
        );
    }

    private String buildSummary(InterviewToolContextService.HistorySummarySource source, String scoreTrend) {
        return "累计 " + source.totalInterviews() + " 次面试，已评估 " + source.evaluatedInterviews()
            + " 次，未完成 " + source.unfinishedInterviews() + " 次；最近 " + source.sessions().size()
            + " 次的" + describeTrend(scoreTrend);
    }

    private Map<String, Object> buildEmptyAnswerPayload(InterviewToolContextService.HistorySummarySource source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumeId", source.resumeId());
        payload.put("limit", source.limit());
        payload.put("totalInterviews", source.totalInterviews());
        payload.put("evaluatedInterviews", source.evaluatedInterviews());
        payload.put("unfinishedInterviews", source.unfinishedInterviews());
        payload.put("latestSessionStatus", null);
        payload.put("scoreTrend", TREND_NO_DATA);
        payload.put("latestEvaluatedConclusion", null);
        payload.put("recentSessions", List.of());
        return payload;
    }

    private Map<String, Object> buildAnswerPayload(
        InterviewToolContextService.HistorySummarySource source,
        String latestStatus,
        String scoreTrend
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumeId", source.resumeId());
        payload.put("limit", source.limit());
        payload.put("totalInterviews", source.totalInterviews());
        payload.put("evaluatedInterviews", source.evaluatedInterviews());
        payload.put("unfinishedInterviews", source.unfinishedInterviews());
        payload.put("latestSessionStatus", latestStatus);
        payload.put("scoreTrend", scoreTrend);
        payload.put("latestEvaluatedConclusion", toLatestEvaluatedConclusionPayload(source.latestEvaluatedConclusion()));
        payload.put("recentSessions", source.sessions().stream().map(this::toRecentSessionPayload).toList());
        return payload;
    }

    private Map<String, Object> buildDebugPayload(InterviewToolContextService.HistorySummarySource source) {
        Map<String, Object> debugPayload = new LinkedHashMap<>();
        debugPayload.put("usedFallback", source.usedFallback());
        debugPayload.put("fallbackReason", source.fallbackReason());
        return debugPayload;
    }

    private Map<String, Object> toRecentSessionPayload(InterviewSessionEntity session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.getSessionId());
        payload.put("status", latestStatusName(session));
        payload.put("overallScore", session.getOverallScore());
        payload.put("createdAt", session.getCreatedAt());
        return payload;
    }

    private Map<String, Object> toLatestEvaluatedConclusionPayload(
        InterviewToolContextService.LatestEvaluatedConclusion conclusion
    ) {
        if (conclusion == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", conclusion.sessionId());
        payload.put("overallScore", conclusion.overallScore());
        payload.put("overallFeedback", conclusion.overallFeedback());
        payload.put("completedAt", conclusion.completedAt());
        return payload;
    }

    private String resolveScoreTrend(List<InterviewSessionEntity> sessions) {
        List<Integer> evaluatedScores = sessions.stream()
            .filter(session -> session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED)
            .map(InterviewSessionEntity::getOverallScore)
            .filter(score -> score != null)
            .toList();

        if (evaluatedScores.isEmpty()) {
            return TREND_NO_DATA;
        }
        if (evaluatedScores.size() < 2) {
            return TREND_INSUFFICIENT_DATA;
        }

        int latestScore = evaluatedScores.get(0);
        int previousScore = evaluatedScores.get(1);
        if (latestScore > previousScore) {
            return TREND_IMPROVING;
        }
        if (latestScore < previousScore) {
            return TREND_DECLINING;
        }
        return TREND_STABLE;
    }

    private String latestStatusName(InterviewSessionEntity session) {
        if (session == null || session.getStatus() == null) {
            return null;
        }
        return session.getStatus().name();
    }

    private String describeTrend(String scoreTrend) {
        return switch (scoreTrend) {
            case TREND_IMPROVING -> "分数趋势较上次有所提升。";
            case TREND_DECLINING -> "分数趋势较上次有所下降。";
            case TREND_STABLE -> "分数趋势基本稳定。";
            case TREND_NO_DATA, TREND_INSUFFICIENT_DATA -> "分数趋势暂时无法判断。";
            default -> "分数趋势暂时无法判断。";
        };
    }
}
