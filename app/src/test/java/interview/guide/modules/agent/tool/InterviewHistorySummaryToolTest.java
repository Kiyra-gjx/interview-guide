package interview.guide.modules.agent.tool;

import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.interview.InterviewToolContextService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewHistorySummaryToolTest {

    @Mock
    private InterviewToolContextService interviewToolContextService;

    private InterviewHistorySummaryTool tool;

    @BeforeEach
    void setUp() {
        tool = new InterviewHistorySummaryTool(interviewToolContextService);
    }

    @Test
    @DisplayName("should expose stable tool metadata")
    void shouldExposeStableToolMetadata() {
        assertThat(InterviewHistorySummaryTool.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(tool.name()).isEqualTo("get_interview_history_summary");
        assertThat(tool.description()).isEqualTo("汇总简历最近几次面试的状态、分数、趋势和未完成情况。输入: { resumeId, limit }");
        assertThat(tool.requiredInputs()).containsExactly("resumeId");
        assertThat(tool.allowedInputs()).containsExactly("resumeId", "limit");
        assertThat(tool.riskLevel()).isEqualTo(AgentToolRiskLevel.READ_ONLY);
    }

    @Test
    @DisplayName("should use full-history aggregates while keeping only recent sessions in the payload slice")
    void shouldUseFullHistoryAggregatesWhileKeepingOnlyRecentSessionsInThePayloadSlice() {
        InterviewSessionEntity latestSession = session(
            "session-in-progress",
            42L,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS,
            null,
            LocalDateTime.of(2026, 4, 24, 10, 0)
        );
        InterviewSessionEntity evaluatedSession = session(
            "session-evaluated",
            42L,
            InterviewSessionEntity.SessionStatus.EVALUATED,
            76,
            LocalDateTime.of(2026, 4, 23, 10, 0)
        );
        InterviewToolContextService.HistorySummarySource source = new InterviewToolContextService.HistorySummarySource(
            42L,
            List.of(latestSession, evaluatedSession),
            new InterviewToolContextService.LatestEvaluatedConclusion(
                "session-evaluated",
                76,
                "表达较清晰，但需要更完整地展开项目细节。",
                LocalDateTime.of(2026, 4, 23, 10, 30)
            ),
            true,
            "resume_id_from_context",
            2,
            7,
            4,
            2
        );
        AgentToolContext context = context(42L);

        when(interviewToolContextService.loadHistorySummarySource(Map.of("limit", 2), context)).thenReturn(source);

        AgentToolResult result = tool.execute(Map.of("limit", 2), context);

        assertThat(result.summary()).isEqualTo("累计 7 次面试，已评估 4 次，未完成 2 次；最近 2 次的分数趋势暂时无法判断。");
        Map<String, Object> expectedAnswerPayload = new LinkedHashMap<>();
        expectedAnswerPayload.put("resumeId", 42L);
        expectedAnswerPayload.put("limit", 2);
        expectedAnswerPayload.put("totalInterviews", 7);
        expectedAnswerPayload.put("evaluatedInterviews", 4);
        expectedAnswerPayload.put("unfinishedInterviews", 2);
        expectedAnswerPayload.put("latestSessionStatus", "IN_PROGRESS");
        expectedAnswerPayload.put("scoreTrend", "INSUFFICIENT_DATA");
        expectedAnswerPayload.put("latestEvaluatedConclusion", conclusionPayload(
            "session-evaluated",
            76,
            "表达较清晰，但需要更完整地展开项目细节。",
            LocalDateTime.of(2026, 4, 23, 10, 30)
        ));
        expectedAnswerPayload.put("recentSessions", List.of(
            sessionPayload("session-in-progress", "IN_PROGRESS", null, LocalDateTime.of(2026, 4, 24, 10, 0)),
            sessionPayload("session-evaluated", "EVALUATED", 76, LocalDateTime.of(2026, 4, 23, 10, 0))
        ));
        assertThat(result.answerPayload()).containsExactlyEntriesOf(expectedAnswerPayload);
        assertThat(result.debugPayload()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "usedFallback", true,
            "fallbackReason", "resume_id_from_context"
        ));
        assertThat(result.confirmedFacts()).isEmpty();

        verify(interviewToolContextService).loadHistorySummarySource(Map.of("limit", 2), context);
    }

    @Test
    @DisplayName("should represent null latest status stably instead of crashing")
    void shouldRepresentNullLatestStatusStablyInsteadOfCrashing() {
        InterviewSessionEntity latestSession = session(
            "session-latest-null-status",
            42L,
            null,
            null,
            LocalDateTime.of(2026, 4, 24, 10, 0)
        );
        InterviewSessionEntity evaluatedSession = session(
            "session-evaluated",
            42L,
            InterviewSessionEntity.SessionStatus.EVALUATED,
            76,
            LocalDateTime.of(2026, 4, 23, 10, 0)
        );
        InterviewToolContextService.HistorySummarySource source = new InterviewToolContextService.HistorySummarySource(
            42L,
            List.of(latestSession, evaluatedSession),
            null,
            false,
            null,
            2,
            2,
            1,
            0
        );
        AgentToolContext context = context(42L);

        when(interviewToolContextService.loadHistorySummarySource(Map.of(), context)).thenReturn(source);

        AgentToolResult result = tool.execute(Map.of(), context);

        assertThat(result.summary()).isEqualTo("累计 2 次面试，已评估 1 次，未完成 0 次；最近 2 次的分数趋势暂时无法判断。");
        assertThat(result.answerPayload()).containsEntry("latestSessionStatus", null);
        assertThat(result.answerPayload()).containsEntry("latestEvaluatedConclusion", null);
        assertThat(result.confirmedFacts()).isEmpty();
    }

    @Test
    @DisplayName("should return stable empty history result when no sessions exist")
    void shouldReturnStableEmptyHistoryResultWhenNoSessionsExist() {
        InterviewToolContextService.HistorySummarySource source = new InterviewToolContextService.HistorySummarySource(
            42L,
            List.of(),
            null,
            true,
            "resume_id_from_context",
            5,
            0,
            0,
            0
        );
        AgentToolContext context = context(42L);

        when(interviewToolContextService.loadHistorySummarySource(Map.of(), context)).thenReturn(source);

        AgentToolResult result = tool.execute(Map.of(), context);

        assertThat(result.summary()).isEqualTo("当前没有面试记录，暂时无法判断分数趋势。");
        Map<String, Object> expectedAnswerPayload = new LinkedHashMap<>();
        expectedAnswerPayload.put("resumeId", 42L);
        expectedAnswerPayload.put("limit", 5);
        expectedAnswerPayload.put("totalInterviews", 0);
        expectedAnswerPayload.put("evaluatedInterviews", 0);
        expectedAnswerPayload.put("unfinishedInterviews", 0);
        expectedAnswerPayload.put("latestSessionStatus", null);
        expectedAnswerPayload.put("scoreTrend", "NO_DATA");
        expectedAnswerPayload.put("latestEvaluatedConclusion", null);
        expectedAnswerPayload.put("recentSessions", List.of());
        assertThat(result.answerPayload()).containsExactlyEntriesOf(expectedAnswerPayload);
        assertThat(result.debugPayload()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "usedFallback", true,
            "fallbackReason", "resume_id_from_context"
        ));
        assertThat(result.confirmedFacts()).isEmpty();
    }

    private AgentToolContext context(Long resumeId) {
        return new AgentToolContext("agent-session", resumeId, List.of(), null, "latest user message");
    }

    private InterviewSessionEntity session(
        String sessionId,
        Long resumeId,
        InterviewSessionEntity.SessionStatus status,
        Integer overallScore,
        LocalDateTime createdAt
    ) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(resumeId);

        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setResume(resume);
        session.setStatus(status);
        session.setOverallScore(overallScore);
        session.setCreatedAt(createdAt);
        return session;
    }

    private Map<String, Object> sessionPayload(
        String sessionId,
        String status,
        Integer overallScore,
        LocalDateTime createdAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("status", status);
        payload.put("overallScore", overallScore);
        payload.put("createdAt", createdAt);
        return payload;
    }

    private Map<String, Object> conclusionPayload(
        String sessionId,
        Integer overallScore,
        String overallFeedback,
        LocalDateTime completedAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("overallScore", overallScore);
        payload.put("overallFeedback", overallFeedback);
        payload.put("completedAt", completedAt);
        return payload;
    }
}
