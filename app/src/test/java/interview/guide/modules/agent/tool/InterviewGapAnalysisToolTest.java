package interview.guide.modules.agent.tool;

import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewGapAnalysisToolTest {

    @Mock
    private InterviewToolContextService interviewToolContextService;
    @Mock
    private InterviewGapAnalyzer interviewGapAnalyzer;

    private InterviewGapAnalysisTool tool;

    @BeforeEach
    void setUp() {
        tool = new InterviewGapAnalysisTool(interviewToolContextService, interviewGapAnalyzer);
    }

    @Test
    @DisplayName("should expose stable tool metadata")
    void shouldExposeStableToolMetadata() {
        assertThat(InterviewGapAnalysisTool.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(tool.name()).isEqualTo("analyze_interview_gaps");
        assertThat(tool.description()).isEqualTo("分析指定或最近一次已评估面试的低分维度、重复改进项和练习优先级。输入: { sessionId, resumeId }");
        assertThat(tool.requiredAnyOfInputs()).containsExactly(List.of("sessionId", "resumeId"));
        assertThat(tool.allowedInputs()).containsExactly("sessionId", "resumeId");
        assertThat(tool.riskLevel()).isEqualTo(AgentToolRiskLevel.READ_ONLY);
    }

    @Test
    @DisplayName("should use fallback analysis source and persist only stable gap facts")
    void shouldUseFallbackAnalysisSourceAndPersistOnlyStableGapFacts() {
        InterviewDetailDTO detail = detail("gap-session-001", 68);
        InterviewSessionEntity session = session("gap-session-001", 42L, InterviewSessionEntity.SessionStatus.EVALUATED, 68);
        InterviewToolContextService.AnalysisSource source = new InterviewToolContextService.AnalysisSource(
            42L,
            "gap-session-001",
            session,
            detail,
            true,
            "latest_evaluated_session"
        );
        InterviewGapAnalyzer.InterviewGapAnalysis analysis = new InterviewGapAnalyzer.InterviewGapAnalysis(
            true,
            "综合得分 68，识别到 2 个低分维度和 1 条重复改进项",
            List.of(
                new InterviewGapAnalyzer.CategoryInsight("数据库", 58, 2, "该分类平均得分 58，属于相对薄弱项"),
                new InterviewGapAnalyzer.CategoryInsight("系统设计", 66, 1, "该分类平均得分 66，属于相对薄弱项")
            ),
            List.of("需要补强索引设计"),
            List.of("数据库基础", "系统设计"),
            List.of("优先复盘数据库题", "整理系统设计回答结构")
        );
        AgentToolContext context = context(42L);

        when(interviewToolContextService.loadGapAnalysisSource(Map.of(), context)).thenReturn(source);
        when(interviewGapAnalyzer.analyze(detail)).thenReturn(analysis);

        AgentToolResult result = tool.execute(Map.of(), context);

        assertThat(result.summary()).isEqualTo("综合得分 68，识别到 2 个低分维度和 1 条重复改进项");
        Map<String, Object> expectedAnswerPayload = new LinkedHashMap<>();
        expectedAnswerPayload.put("resumeId", 42L);
        expectedAnswerPayload.put("selectedSessionId", "gap-session-001");
        expectedAnswerPayload.put("available", true);
        expectedAnswerPayload.put("overallScore", 68);
        expectedAnswerPayload.put("summary", "综合得分 68，识别到 2 个低分维度和 1 条重复改进项");
        expectedAnswerPayload.put("lowCategories", List.of(
            categoryPayload("数据库", 58, 2, "该分类平均得分 58，属于相对薄弱项"),
            categoryPayload("系统设计", 66, 1, "该分类平均得分 66，属于相对薄弱项")
        ));
        expectedAnswerPayload.put("repeatedImprovements", List.of("需要补强索引设计"));
        expectedAnswerPayload.put("knowledgeGapTags", List.of("数据库基础", "系统设计"));
        expectedAnswerPayload.put("practicePriorities", List.of("优先复盘数据库题", "整理系统设计回答结构"));
        assertThat(result.answerPayload()).containsExactlyEntriesOf(expectedAnswerPayload);
        assertThat(result.debugPayload()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "selectedSessionId", "gap-session-001",
            "usedFallback", true,
            "fallbackReason", "latest_evaluated_session",
            "detailAvailable", true
        ));
        assertThat(result.confirmedFacts()).containsExactly("低分维度: 数据库、系统设计");
        assertThat(result.confirmedFacts()).allSatisfy(fact -> {
            assertThat(fact).doesNotContain("优先");
            assertThat(fact).doesNotContain("建议");
            assertThat(fact).doesNotContain("会话ID");
            assertThat(fact).doesNotContain("得分");
        });

        verify(interviewToolContextService).loadGapAnalysisSource(Map.of(), context);
        verify(interviewGapAnalyzer).analyze(detail);
    }

    @Test
    @DisplayName("should omit confirmed facts when analysis has no weak category names")
    void shouldOmitConfirmedFactsWhenAnalysisHasNoWeakCategoryNames() {
        InterviewDetailDTO detail = detail("gap-session-003", 88);
        InterviewSessionEntity session = session("gap-session-003", 42L, InterviewSessionEntity.SessionStatus.EVALUATED, 88);
        InterviewToolContextService.AnalysisSource source = new InterviewToolContextService.AnalysisSource(
            42L,
            "gap-session-003",
            session,
            detail,
            false,
            null
        );
        InterviewGapAnalyzer.InterviewGapAnalysis analysis = new InterviewGapAnalyzer.InterviewGapAnalysis(
            true,
            "已完成分析，但暂未识别到明确低分维度",
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
        AgentToolContext context = context(42L);

        when(interviewToolContextService.loadGapAnalysisSource(Map.of("sessionId", "gap-session-003"), context)).thenReturn(source);
        when(interviewGapAnalyzer.analyze(detail)).thenReturn(analysis);

        AgentToolResult result = tool.execute(Map.of("sessionId", "gap-session-003"), context);

        assertThat(result.confirmedFacts()).isEmpty();
    }

    @Test
    @DisplayName("should return stable unavailable result when detail is missing")
    void shouldReturnStableUnavailableResultWhenDetailIsMissing() {
        InterviewSessionEntity session = session("gap-session-002", 42L, InterviewSessionEntity.SessionStatus.COMPLETED, null);
        InterviewToolContextService.AnalysisSource source = new InterviewToolContextService.AnalysisSource(
            42L,
            "gap-session-002",
            session,
            null,
            false,
            "session_not_evaluated"
        );
        AgentToolContext context = context(42L);

        when(interviewToolContextService.loadGapAnalysisSource(Map.of("sessionId", "gap-session-002"), context)).thenReturn(source);

        AgentToolResult result = tool.execute(Map.of("sessionId", "gap-session-002"), context);

        assertThat(result.summary()).isEqualTo("当前暂无可用评估结果，暂不输出短板分析。");
        Map<String, Object> expectedAnswerPayload = new LinkedHashMap<>();
        expectedAnswerPayload.put("resumeId", 42L);
        expectedAnswerPayload.put("selectedSessionId", "gap-session-002");
        expectedAnswerPayload.put("available", false);
        expectedAnswerPayload.put("overallScore", null);
        expectedAnswerPayload.put("summary", "当前暂无可用评估结果，暂不输出短板分析。");
        expectedAnswerPayload.put("lowCategories", List.of());
        expectedAnswerPayload.put("repeatedImprovements", List.of());
        expectedAnswerPayload.put("knowledgeGapTags", List.of());
        expectedAnswerPayload.put("practicePriorities", List.of());
        assertThat(result.answerPayload()).containsExactlyEntriesOf(expectedAnswerPayload);
        assertThat(result.debugPayload()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "selectedSessionId", "gap-session-002",
            "usedFallback", false,
            "fallbackReason", "session_not_evaluated",
            "detailAvailable", false
        ));
        assertThat(result.confirmedFacts()).isEmpty();

        verify(interviewToolContextService).loadGapAnalysisSource(Map.of("sessionId", "gap-session-002"), context);
        verify(interviewGapAnalyzer, never()).analyze(any());
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
            List.of()
        );
    }

    private Map<String, Object> categoryPayload(String category, int averageScore, int answerCount, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", category);
        payload.put("averageScore", averageScore);
        payload.put("answerCount", answerCount);
        payload.put("reason", reason);
        return payload;
    }
}
