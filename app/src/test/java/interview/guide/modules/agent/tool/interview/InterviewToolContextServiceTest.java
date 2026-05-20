package interview.guide.modules.agent.tool.interview;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewToolContextServiceTest {

    @Mock
    private InterviewPersistenceService interviewPersistenceService;
    @Mock
    private InterviewHistoryService interviewHistoryService;

    private InterviewToolContextService service;

    @BeforeEach
    void setUp() {
        service = new InterviewToolContextService(interviewPersistenceService, interviewHistoryService);
    }

    @Test
    @DisplayName("should fall back to the bound resume id when history summary input omits resumeId")
    void shouldFallBackToBoundResumeIdWhenHistorySummaryInputOmitsResumeId() {
        InterviewSessionEntity latestSession = session("history-session", 42L, InterviewSessionEntity.SessionStatus.EVALUATED);
        AgentToolContext context = context(42L);

        when(interviewPersistenceService.findByResumeId(42L)).thenReturn(List.of(latestSession));

        var source = service.loadHistorySummarySource(Map.of(), context);

        assertThat(source.resumeId()).isEqualTo(42L);
        assertThat(source.sessions()).containsExactly(latestSession);
        assertThat(source.usedFallback()).isTrue();
        assertThat(source.fallbackReason()).isEqualTo("resume_id_from_context");
        assertThat(source.limit()).isEqualTo(5);
        verify(interviewPersistenceService).findByResumeId(42L);
    }

    @Test
    @DisplayName("should expose the latest evaluated conclusion from full history even when it falls outside the recent slice")
    void shouldExposeTheLatestEvaluatedConclusionFromFullHistoryEvenWhenItFallsOutsideTheRecentSlice() {
        InterviewSessionEntity latestInProgress = session(
            "history-in-progress",
            42L,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS,
            LocalDateTime.of(2026, 4, 24, 12, 0),
            "[{\"questionIndex\":0}]"
        );
        InterviewSessionEntity latestCreated = session(
            "history-created",
            42L,
            InterviewSessionEntity.SessionStatus.CREATED,
            LocalDateTime.of(2026, 4, 24, 11, 0),
            "[{\"questionIndex\":0}]"
        );
        InterviewSessionEntity latestEvaluated = session(
            "history-evaluated",
            42L,
            InterviewSessionEntity.SessionStatus.EVALUATED,
            LocalDateTime.of(2026, 4, 23, 10, 0),
            "[{\"questionIndex\":0}]"
        );
        latestEvaluated.setOverallScore(71);
        latestEvaluated.setOverallFeedback("基础表达清晰，但系统设计展开不够深入。");
        latestEvaluated.setCompletedAt(LocalDateTime.of(2026, 4, 23, 10, 45));

        when(interviewPersistenceService.findByResumeId(42L)).thenReturn(List.of(
            latestInProgress,
            latestCreated,
            latestEvaluated
        ));

        var source = service.loadHistorySummarySource(
            Map.of(
                "resumeId", 42L,
                "limit", 2
            ),
            context(null)
        );

        assertThat(source.sessions()).containsExactly(latestInProgress, latestCreated);
        assertThat(source.latestEvaluatedConclusion()).isEqualTo(
            new InterviewToolContextService.LatestEvaluatedConclusion(
                "history-evaluated",
                71,
                "基础表达清晰，但系统设计展开不够深入。",
                LocalDateTime.of(2026, 4, 23, 10, 45)
            )
        );
    }

    @Test
    @DisplayName("should resolve the latest evaluated session when gap analysis does not provide a session id")
    void shouldResolveLatestEvaluatedSessionWhenGapAnalysisOmitsSessionId() {
        InterviewSessionEntity latestUnevaluated = session(
            "session-created",
            42L,
            InterviewSessionEntity.SessionStatus.COMPLETED,
            LocalDateTime.of(2026, 4, 23, 12, 0),
            "[{\"questionIndex\":0}]"
        );
        InterviewSessionEntity latestEvaluated = session(
            "session-evaluated-latest",
            42L,
            InterviewSessionEntity.SessionStatus.EVALUATED,
            LocalDateTime.of(2026, 4, 23, 11, 0),
            "[{\"questionIndex\":0}]"
        );
        InterviewSessionEntity olderEvaluated = session(
            "session-evaluated-older",
            42L,
            InterviewSessionEntity.SessionStatus.EVALUATED,
            LocalDateTime.of(2026, 4, 22, 11, 0),
            "[{\"questionIndex\":0}]"
        );
        InterviewDetailDTO detail = detail("session-evaluated-latest");

        when(interviewPersistenceService.findByResumeId(42L)).thenReturn(List.of(
            latestUnevaluated,
            latestEvaluated,
            olderEvaluated
        ));
        when(interviewHistoryService.getInterviewDetail("session-evaluated-latest")).thenReturn(detail);

        var source = service.loadGapAnalysisSource(Map.of(), context(42L));

        assertThat(source.resumeId()).isEqualTo(42L);
        assertThat(source.sessionId()).isEqualTo("session-evaluated-latest");
        assertThat(source.session()).isSameAs(latestEvaluated);
        assertThat(source.detail()).isSameAs(detail);
        assertThat(source.usedFallback()).isTrue();
        assertThat(source.fallbackReason()).isEqualTo("latest_evaluated_session");
    }

    @Test
    @DisplayName("should fall back to the latest question-bearing session for follow-up when no evaluated session exists")
    void shouldFallBackToLatestQuestionBearingSessionForFollowUp() {
        InterviewSessionEntity latestWithoutQuestions = session(
            "session-no-questions",
            42L,
            InterviewSessionEntity.SessionStatus.CREATED,
            LocalDateTime.of(2026, 4, 23, 12, 0),
            null
        );
        InterviewSessionEntity latestWithQuestions = session(
            "session-with-questions",
            42L,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS,
            LocalDateTime.of(2026, 4, 23, 11, 0),
            "[{\"questionIndex\":0}]"
        );
        InterviewDetailDTO detail = detail("session-with-questions");

        when(interviewPersistenceService.findByResumeId(42L)).thenReturn(List.of(
            latestWithoutQuestions,
            latestWithQuestions
        ));
        when(interviewHistoryService.getInterviewDetail("session-with-questions")).thenReturn(detail);

        var source = service.loadFollowUpSource(Map.of(), context(42L));

        assertThat(source.resumeId()).isEqualTo(42L);
        assertThat(source.sessionId()).isEqualTo("session-with-questions");
        assertThat(source.session()).isSameAs(latestWithQuestions);
        assertThat(source.detail()).isSameAs(detail);
        assertThat(source.usedFallback()).isTrue();
        assertThat(source.fallbackReason()).isEqualTo("latest_session_with_questions");
    }

    @Test
    @DisplayName("should clamp history limit and return only the requested number of sessions")
    void shouldClampHistoryLimitAndReturnOnlyRequestedNumberOfSessions() {
        List<InterviewSessionEntity> sessions = List.of(
            session("history-01", 42L, InterviewSessionEntity.SessionStatus.IN_PROGRESS),
            session("history-02", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-03", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-04", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-05", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-06", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-07", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-08", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-09", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-10", 42L, InterviewSessionEntity.SessionStatus.EVALUATED),
            session("history-11", 42L, InterviewSessionEntity.SessionStatus.CREATED),
            session("history-12", 42L, InterviewSessionEntity.SessionStatus.EVALUATED)
        );

        when(interviewPersistenceService.findByResumeId(42L)).thenReturn(sessions);

        var source = service.loadHistorySummarySource(
            Map.of(
                "resumeId", 42L,
                "limit", 20
            ),
            context(null)
        );

        assertThat(source.resumeId()).isEqualTo(42L);
        assertThat(source.limit()).isEqualTo(10);
        assertThat(source.usedFallback()).isFalse();
        assertThat(source.fallbackReason()).isNull();
        assertThat(source.totalInterviews()).isEqualTo(12);
        assertThat(source.evaluatedInterviews()).isEqualTo(10);
        assertThat(source.unfinishedInterviews()).isEqualTo(2);
        assertThat(source.sessions()).hasSize(10);
        assertThat(source.sessions().stream().map(InterviewSessionEntity::getSessionId))
            .containsExactly(
                "history-01",
                "history-02",
                "history-03",
                "history-04",
                "history-05",
                "history-06",
                "history-07",
                "history-08",
                "history-09",
                "history-10"
            );
    }

    @Test
    @DisplayName("should reject fractional numeric resume id")
    void shouldRejectFractionalNumericResumeId() {
        assertThatThrownBy(() -> service.loadHistorySummarySource(
            Map.of("resumeId", new BigDecimal("42.9")),
            context(null)
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_INVALID_INPUT.getCode()))
            .hasMessageContaining("resumeId");
    }

    @Test
    @DisplayName("should reject fractional numeric limit")
    void shouldRejectFractionalNumericLimit() {
        assertThatThrownBy(() -> service.loadHistorySummarySource(
            Map.of(
                "resumeId", 42L,
                "limit", new BigDecimal("2.7")
            ),
            context(null)
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_INVALID_INPUT.getCode()))
            .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("should reject oversized numeric value")
    void shouldRejectOversizedNumericValue() {
        assertThatThrownBy(() -> service.loadHistorySummarySource(
            Map.of("resumeId", new BigInteger("9223372036854775808")),
            context(null)
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_INVALID_INPUT.getCode()))
            .hasMessageContaining("resumeId");
    }

    @Test
    @DisplayName("should return null detail for explicit gap analysis session when it is not evaluated")
    void shouldReturnNullDetailForExplicitGapAnalysisSessionWhenItIsNotEvaluated() {
        InterviewSessionEntity session = session(
            "session-not-evaluated",
            42L,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS,
            LocalDateTime.of(2026, 4, 23, 12, 0),
            "[{\"questionIndex\":0}]"
        );

        when(interviewPersistenceService.findBySessionIdWithResume("session-not-evaluated")).thenReturn(Optional.of(session));

        var source = service.loadGapAnalysisSource(
            Map.of("sessionId", "session-not-evaluated"),
            context(42L)
        );

        assertThat(source.resumeId()).isEqualTo(42L);
        assertThat(source.sessionId()).isEqualTo("session-not-evaluated");
        assertThat(source.session()).isSameAs(session);
        assertThat(source.detail()).isNull();
        assertThat(source.usedFallback()).isFalse();
        assertThat(source.fallbackReason()).isEqualTo("session_not_evaluated");
    }

    @Test
    @DisplayName("should return a stable empty source when gap analysis auto-selection finds no evaluated session")
    void shouldReturnStableEmptySourceWhenGapAnalysisAutoSelectionFindsNoEvaluatedSession() {
        when(interviewPersistenceService.findByResumeId(42L)).thenReturn(List.of(
            session("session-created", 42L, InterviewSessionEntity.SessionStatus.CREATED),
            session("session-completed", 42L, InterviewSessionEntity.SessionStatus.COMPLETED)
        ));

        var source = service.loadGapAnalysisSource(Map.of(), context(42L));

        assertThat(source.resumeId()).isEqualTo(42L);
        assertThat(source.sessionId()).isNull();
        assertThat(source.session()).isNull();
        assertThat(source.detail()).isNull();
        assertThat(source.usedFallback()).isTrue();
        assertThat(source.fallbackReason()).isEqualTo("no_evaluated_session");
    }

    @Test
    @DisplayName("should reject mismatched session and resume ownership")
    void shouldRejectMismatchedSessionAndResumeOwnership() {
        InterviewSessionEntity session = session(
            "session-mismatch",
            101L,
            InterviewSessionEntity.SessionStatus.EVALUATED,
            LocalDateTime.of(2026, 4, 23, 10, 0),
            "[{\"questionIndex\":0}]"
        );

        when(interviewPersistenceService.findBySessionIdWithResume("session-mismatch")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.loadGapAnalysisSource(
            Map.of(
                "sessionId", "session-mismatch",
                "resumeId", 202L
            ),
            context(null)
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_INVALID_INPUT.getCode()))
            .hasMessageContaining("resumeId");
    }

    @Test
    @DisplayName("should let explicit session id win over conflicting context resume id when input omits resume id")
    void shouldLetExplicitSessionIdWinOverConflictingContextResumeIdWhenInputOmitsResumeId() {
        InterviewSessionEntity session = session(
            "session-explicit",
            101L,
            InterviewSessionEntity.SessionStatus.EVALUATED,
            LocalDateTime.of(2026, 4, 23, 10, 0),
            "[{\"questionIndex\":0}]"
        );
        InterviewDetailDTO detail = detail("session-explicit");

        when(interviewPersistenceService.findBySessionIdWithResume("session-explicit")).thenReturn(Optional.of(session));
        when(interviewHistoryService.getInterviewDetail("session-explicit")).thenReturn(detail);

        var source = service.loadGapAnalysisSource(
            Map.of("sessionId", "session-explicit"),
            context(202L)
        );

        assertThat(source.resumeId()).isEqualTo(101L);
        assertThat(source.sessionId()).isEqualTo("session-explicit");
        assertThat(source.session()).isSameAs(session);
        assertThat(source.detail()).isSameAs(detail);
        assertThat(source.usedFallback()).isFalse();
        assertThat(source.fallbackReason()).isNull();
        verify(interviewPersistenceService).findBySessionIdWithResume("session-explicit");
        verify(interviewPersistenceService, never()).findBySessionId("session-explicit");
    }

    private AgentToolContext context(Long resumeId) {
        return new AgentToolContext("agent-session", resumeId, List.of(), null, "latest user message");
    }

    private InterviewSessionEntity session(String sessionId, Long resumeId, InterviewSessionEntity.SessionStatus status) {
        return session(
            sessionId,
            resumeId,
            status,
            LocalDateTime.of(2026, 4, 23, 10, 0),
            "[{\"questionIndex\":0}]"
        );
    }

    private InterviewSessionEntity session(
        String sessionId,
        Long resumeId,
        InterviewSessionEntity.SessionStatus status,
        LocalDateTime createdAt,
        String questionsJson
    ) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(resumeId);
        resume.setResumeText("resume text");

        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setResume(resume);
        session.setStatus(status);
        session.setCreatedAt(createdAt);
        session.setQuestionsJson(questionsJson);
        return session;
    }

    private InterviewDetailDTO detail(String sessionId) {
        return new InterviewDetailDTO(
            1L,
            sessionId,
            1,
            InterviewSessionEntity.SessionStatus.EVALUATED.name(),
            null,
            null,
            85,
            "overall feedback",
            LocalDateTime.of(2026, 4, 23, 10, 0),
            LocalDateTime.of(2026, 4, 23, 10, 30),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
}
