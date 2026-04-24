package interview.guide.modules.agent.tool;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.interview.FollowUpQuestionPlanner;
import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer;
import interview.guide.modules.agent.tool.interview.InterviewToolContextService;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowUpQuestionSuggestionToolTest {

    @Mock
    private InterviewToolContextService interviewToolContextService;
    @Mock
    private InterviewGapAnalyzer interviewGapAnalyzer;
    @Mock
    private FollowUpQuestionPlanner followUpQuestionPlanner;

    private FollowUpQuestionSuggestionTool tool;

    @BeforeEach
    void setUp() {
        tool = new FollowUpQuestionSuggestionTool(
            interviewToolContextService,
            interviewGapAnalyzer,
            followUpQuestionPlanner
        );
    }

    @Test
    @DisplayName("should expose stable tool metadata")
    void shouldExposeStableToolMetadata() {
        assertThat(FollowUpQuestionSuggestionTool.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(tool.name()).isEqualTo("suggest_follow_up_questions");
        assertThat(tool.description()).isEqualTo("根据最近面试表现生成 1 到 5 个具体追问建议。输入: { sessionId, resumeId, focusCategory, maxCount }");
        assertThat(tool.requiredAnyOfInputs()).containsExactly(List.of("sessionId", "resumeId"));
        assertThat(tool.allowedInputs()).containsExactly("sessionId", "resumeId", "focusCategory", "maxCount");
        assertThat(tool.riskLevel()).isEqualTo(AgentToolRiskLevel.READ_ONLY);
    }

    @Test
    @DisplayName("should use fallback source clamp max count and keep volatile planning facts out of memory")
    void shouldUseFallbackSourceClampMaxCountAndKeepVolatilePlanningFactsOutOfMemory() {
        InterviewDetailDTO detail = detail("follow-up-session-001", 72);
        InterviewSessionEntity session = session("follow-up-session-001", 42L, InterviewSessionEntity.SessionStatus.EVALUATED, 72);
        InterviewToolContextService.AnalysisSource source = new InterviewToolContextService.AnalysisSource(
            42L,
            "follow-up-session-001",
            session,
            detail,
            true,
            "latest_evaluated_session"
        );
        InterviewGapAnalyzer.InterviewGapAnalysis analysis = new InterviewGapAnalyzer.InterviewGapAnalysis(
            true,
            "summary",
            List.of(new InterviewGapAnalyzer.CategoryInsight("数据库", 58, 2, "该分类平均得分 58，属于相对薄弱项")),
            List.of("需要补强索引设计"),
            List.of("数据库基础"),
            List.of("优先复盘数据库题")
        );
        List<FollowUpQuestionPlanner.FollowUpSuggestion> suggestions = List.of(
            new FollowUpQuestionPlanner.FollowUpSuggestion("问题 1", "项目经验", "原因 1", "关注点 1"),
            new FollowUpQuestionPlanner.FollowUpSuggestion("问题 2", "数据库", "原因 2", "关注点 2"),
            new FollowUpQuestionPlanner.FollowUpSuggestion("问题 3", "系统设计", "原因 3", "关注点 3"),
            new FollowUpQuestionPlanner.FollowUpSuggestion("问题 4", "Java 基础", "原因 4", "关注点 4"),
            new FollowUpQuestionPlanner.FollowUpSuggestion("问题 5", "沟通表达", "原因 5", "关注点 5")
        );
        AgentToolContext context = context(42L);
        Map<String, Object> input = Map.of(
            "focusCategory", "项目经验",
            "maxCount", "7"
        );

        when(interviewToolContextService.loadFollowUpSource(input, context)).thenReturn(source);
        when(interviewGapAnalyzer.analyze(detail)).thenReturn(analysis);
        when(followUpQuestionPlanner.plan(detail, analysis, "项目经验", 5)).thenReturn(suggestions);

        AgentToolResult result = tool.execute(input, context);

        assertThat(result.summary()).isEqualTo("已生成可继续练习的追问建议。");
        Map<String, Object> expectedAnswerPayload = new LinkedHashMap<>();
        expectedAnswerPayload.put("resumeId", 42L);
        expectedAnswerPayload.put("selectedSessionId", "follow-up-session-001");
        expectedAnswerPayload.put("focusCategory", "项目经验");
        expectedAnswerPayload.put("maxCount", 5);
        expectedAnswerPayload.put("available", true);
        expectedAnswerPayload.put("suggestions", List.of(
            suggestionPayload("问题 1", "项目经验", "原因 1", "关注点 1"),
            suggestionPayload("问题 2", "数据库", "原因 2", "关注点 2"),
            suggestionPayload("问题 3", "系统设计", "原因 3", "关注点 3"),
            suggestionPayload("问题 4", "Java 基础", "原因 4", "关注点 4"),
            suggestionPayload("问题 5", "沟通表达", "原因 5", "关注点 5")
        ));
        assertThat(result.answerPayload()).containsExactlyEntriesOf(expectedAnswerPayload);
        assertThat(result.debugPayload()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "selectedSessionId", "follow-up-session-001",
            "usedFallback", true,
            "fallbackReason", "latest_evaluated_session",
            "focusCategory", "项目经验"
        ));
        assertThat(result.confirmedFacts()).isEmpty();

        verify(interviewToolContextService).loadFollowUpSource(input, context);
        verify(interviewGapAnalyzer).analyze(detail);
        verify(followUpQuestionPlanner).plan(detail, analysis, "项目经验", 5);
    }

    @Test
    @DisplayName("should return unavailable result when planner produces no suggestions")
    void shouldReturnUnavailableResultWhenPlannerProducesNoSuggestions() {
        InterviewDetailDTO detail = detail("follow-up-session-empty", 72);
        InterviewSessionEntity session = session("follow-up-session-empty", 42L, InterviewSessionEntity.SessionStatus.EVALUATED, 72);
        InterviewToolContextService.AnalysisSource source = new InterviewToolContextService.AnalysisSource(
            42L,
            "follow-up-session-empty",
            session,
            detail,
            true,
            "latest_evaluated_session"
        );
        InterviewGapAnalyzer.InterviewGapAnalysis analysis = new InterviewGapAnalyzer.InterviewGapAnalysis(
            true,
            "summary",
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
        AgentToolContext context = context(42L);
        Map<String, Object> input = Map.of("focusCategory", "项目经验");

        when(interviewToolContextService.loadFollowUpSource(input, context)).thenReturn(source);
        when(interviewGapAnalyzer.analyze(detail)).thenReturn(analysis);
        when(followUpQuestionPlanner.plan(detail, analysis, "项目经验", 3)).thenReturn(List.of());

        AgentToolResult result = tool.execute(input, context);

        Map<String, Object> expectedAnswerPayload = new LinkedHashMap<>();
        expectedAnswerPayload.put("resumeId", 42L);
        expectedAnswerPayload.put("selectedSessionId", "follow-up-session-empty");
        expectedAnswerPayload.put("focusCategory", "项目经验");
        expectedAnswerPayload.put("maxCount", 3);
        expectedAnswerPayload.put("available", false);
        expectedAnswerPayload.put("suggestions", List.of());

        assertThat(result.summary()).isEqualTo("当前暂无可继续练习的追问建议。");
        assertThat(result.answerPayload()).containsExactlyEntriesOf(expectedAnswerPayload);
        assertThat(result.debugPayload()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "selectedSessionId", "follow-up-session-empty",
            "usedFallback", true,
            "fallbackReason", "latest_evaluated_session",
            "focusCategory", "项目经验"
        ));
        assertThat(result.confirmedFacts()).isEmpty();

        verify(interviewToolContextService).loadFollowUpSource(input, context);
        verify(interviewGapAnalyzer).analyze(detail);
        verify(followUpQuestionPlanner).plan(detail, analysis, "项目经验", 3);
    }

    @Test
    @DisplayName("should return stable unavailable result when follow-up detail is missing")
    void shouldReturnStableUnavailableResultWhenFollowUpDetailIsMissing() {
        InterviewSessionEntity session = session("follow-up-session-002", 42L, InterviewSessionEntity.SessionStatus.CREATED, null);
        InterviewToolContextService.AnalysisSource source = new InterviewToolContextService.AnalysisSource(
            42L,
            "follow-up-session-002",
            session,
            null,
            false,
            "session_has_no_question_data"
        );
        AgentToolContext context = context(42L);
        Map<String, Object> input = Map.of("sessionId", "follow-up-session-002");

        when(interviewToolContextService.loadFollowUpSource(input, context)).thenReturn(source);

        AgentToolResult result = tool.execute(input, context);

        assertThat(result.summary()).isEqualTo("当前暂无可继续练习的追问建议。");
        Map<String, Object> expectedAnswerPayload = new LinkedHashMap<>();
        expectedAnswerPayload.put("resumeId", 42L);
        expectedAnswerPayload.put("selectedSessionId", "follow-up-session-002");
        expectedAnswerPayload.put("focusCategory", null);
        expectedAnswerPayload.put("maxCount", 3);
        expectedAnswerPayload.put("available", false);
        expectedAnswerPayload.put("suggestions", List.of());
        assertThat(result.answerPayload()).containsExactlyEntriesOf(expectedAnswerPayload);
        Map<String, Object> expectedDebugPayload = new LinkedHashMap<>();
        expectedDebugPayload.put("selectedSessionId", "follow-up-session-002");
        expectedDebugPayload.put("usedFallback", false);
        expectedDebugPayload.put("fallbackReason", "session_has_no_question_data");
        expectedDebugPayload.put("focusCategory", null);
        assertThat(result.debugPayload()).containsExactlyEntriesOf(expectedDebugPayload);
        assertThat(result.confirmedFacts()).isEmpty();

        verify(interviewToolContextService).loadFollowUpSource(input, context);
        verify(interviewGapAnalyzer, never()).analyze(any());
        verify(followUpQuestionPlanner, never()).plan(any(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("should reject fractional max count instead of truncating it")
    void shouldRejectFractionalMaxCountInsteadOfTruncatingIt() {
        assertThatThrownBy(() -> tool.execute(Map.of("maxCount", new BigDecimal("2.5")), context(42L)))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_INVALID_INPUT.getCode()))
            .hasMessageContaining("maxCount");
    }

    private AgentToolContext context(Long resumeId) {
        return new AgentToolContext("agent-session", resumeId, List.of(), null, "latest user message");
    }

    private InterviewSessionEntity session(
        String sessionId,
        Long resumeId,
        InterviewSessionEntity.SessionStatus status,
        Integer overallScore
    ) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(resumeId);

        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setResume(resume);
        session.setStatus(status);
        session.setOverallScore(overallScore);
        session.setCreatedAt(LocalDateTime.of(2026, 4, 24, 10, 0));
        return session;
    }

    private InterviewDetailDTO detail(String sessionId, Integer overallScore) {
        return new InterviewDetailDTO(
            1L,
            sessionId,
            2,
            "EVALUATED",
            null,
            null,
            overallScore,
            "overall feedback",
            LocalDateTime.of(2026, 4, 24, 10, 0),
            LocalDateTime.of(2026, 4, 24, 10, 30),
            List.of(),
            List.of(),
            List.of("需要补强索引设计"),
            List.of(),
            List.of(
                new InterviewDetailDTO.AnswerDetailDTO(
                    0,
                    "问题 0",
                    "数据库",
                    "回答 0",
                    58,
                    "反馈 0",
                    "参考 0",
                    List.of("关键点"),
                    LocalDateTime.of(2026, 4, 24, 10, 5)
                ),
                new InterviewDetailDTO.AnswerDetailDTO(
                    1,
                    "问题 1",
                    "系统设计",
                    "回答 1",
                    66,
                    "反馈 1",
                    "参考 1",
                    List.of("关键点"),
                    LocalDateTime.of(2026, 4, 24, 10, 10)
                )
            )
        );
    }

    private Map<String, Object> suggestionPayload(
        String question,
        String focusArea,
        String reason,
        String coachingTip
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("focusArea", focusArea);
        payload.put("reason", reason);
        payload.put("coachingTip", coachingTip);
        return payload;
    }
}
