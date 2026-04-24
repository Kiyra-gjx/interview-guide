package interview.guide.modules.agent.tool.interview;

import interview.guide.modules.agent.tool.interview.FollowUpQuestionPlanner.FollowUpSuggestion;
import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer.CategoryInsight;
import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer.InterviewGapAnalysis;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FollowUpQuestionPlannerTest {

    private static final String DATABASE = "数据库";
    private static final String SYSTEM_DESIGN = "系统设计";
    private static final String JAVA_CORE = "Java基础";
    private static final String COMMUNICATION = "沟通表达";
    private static final String BEHAVIORAL = "行为面试";

    private final FollowUpQuestionPlanner planner = new FollowUpQuestionPlanner();

    @Test
    @DisplayName("should register planner as spring bean")
    void shouldRegisterPlannerAsSpringBean() {
        assertThat(FollowUpQuestionPlanner.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Test
    @DisplayName("should prioritize focus category then weak categories then answer fallback")
    void shouldPrioritizeFocusCategoryThenWeakCategoriesThenAnswerFallback() {
        InterviewDetailDTO detail = detail(List.of(
            answer(0, DATABASE, 45),
            answer(1, SYSTEM_DESIGN, 60),
            answer(2, JAVA_CORE, 70),
            answer(3, BEHAVIORAL, 88)
        ));
        InterviewGapAnalysis analysis = analysis(List.of(
            insight(DATABASE, 45),
            insight(SYSTEM_DESIGN, 60)
        ));

        List<FollowUpSuggestion> suggestions = planner.plan(detail, analysis, COMMUNICATION, 4);

        assertThat(suggestions)
            .extracting(FollowUpSuggestion::focusArea)
            .containsExactly(COMMUNICATION, DATABASE, SYSTEM_DESIGN, JAVA_CORE);
    }

    @Test
    @DisplayName("should fall back to answer categories when analysis has no low categories")
    void shouldFallBackToAnswerCategoriesWhenAnalysisHasNoLowCategories() {
        InterviewDetailDTO detail = detail(List.of(
            answer(0, JAVA_CORE, 82),
            answer(1, BEHAVIORAL, 88)
        ));
        InterviewGapAnalysis analysis = analysis(List.of());

        List<FollowUpSuggestion> suggestions = planner.plan(detail, analysis, null, 4);

        assertThat(suggestions)
            .extracting(FollowUpSuggestion::focusArea)
            .containsExactly(JAVA_CORE, BEHAVIORAL);
    }

    @Test
    @DisplayName("should return at most max count suggestions")
    void shouldReturnAtMostMaxCountSuggestions() {
        InterviewDetailDTO detail = detail(List.of(
            answer(0, DATABASE, 45),
            answer(1, SYSTEM_DESIGN, 60),
            answer(2, JAVA_CORE, 70)
        ));
        InterviewGapAnalysis analysis = analysis(List.of(
            insight(DATABASE, 45),
            insight(SYSTEM_DESIGN, 60),
            insight(JAVA_CORE, 70)
        ));

        List<FollowUpSuggestion> suggestions = planner.plan(detail, analysis, null, 2);

        assertThat(suggestions).hasSize(2);
    }

    @Test
    @DisplayName("should use specialized database template for mysql case variants")
    void shouldUseSpecializedDatabaseTemplateForMysqlCaseVariants() {
        InterviewDetailDTO detail = detail(List.of(answer(0, "MySQL 调优", 45)));
        InterviewGapAnalysis analysis = analysis(List.of(insight("MySQL 调优", 45)));

        List<FollowUpSuggestion> suggestions = planner.plan(detail, analysis, null, 1);

        assertThat(suggestions).hasSize(1);
        FollowUpSuggestion suggestion = suggestions.getFirst();
        assertThat(suggestion.focusArea()).isEqualTo("MySQL 调优");
        assertThat(suggestion.question()).contains("数据库题");
        assertThat(suggestion.coachingTip()).contains("索引");
    }

    @Test
    @DisplayName("should generate concrete suggestions without generic filler phrases")
    void shouldGenerateConcreteSuggestionsWithoutGenericFillerPhrases() {
        InterviewDetailDTO detail = detail(List.of(answer(0, DATABASE, 45)));
        InterviewGapAnalysis analysis = analysis(List.of(insight(DATABASE, 45)));

        List<FollowUpSuggestion> suggestions = planner.plan(detail, analysis, null, 1);

        assertThat(suggestions).hasSize(1);
        FollowUpSuggestion suggestion = suggestions.getFirst();
        assertThat(suggestion.question()).contains(DATABASE);
        assertThat(suggestion.question()).doesNotContain("再详细说说", "展开讲讲", "具体说说");
        assertThat(suggestion.reason()).isNotBlank();
        assertThat(suggestion.coachingTip()).isNotBlank();
    }

    private InterviewGapAnalysis analysis(List<CategoryInsight> lowCategories) {
        return new InterviewGapAnalysis(
            true,
            "summary",
            lowCategories,
            List.of("需要补强索引优化"),
            List.of("数据库基础"),
            List.of("优先复盘数据库题")
        );
    }

    private CategoryInsight insight(String category, int averageScore) {
        return new CategoryInsight(category, averageScore, 1, "短板：" + category);
    }

    private InterviewDetailDTO detail(List<InterviewDetailDTO.AnswerDetailDTO> answers) {
        return new InterviewDetailDTO(
            1L,
            "session-001",
            answers.size(),
            "EVALUATED",
            null,
            null,
            78,
            "overall feedback",
            LocalDateTime.of(2026, 4, 23, 10, 0),
            LocalDateTime.of(2026, 4, 23, 10, 30),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            answers
        );
    }

    private InterviewDetailDTO.AnswerDetailDTO answer(int index, String category, Integer score) {
        return new InterviewDetailDTO.AnswerDetailDTO(
            index,
            "question " + index,
            category,
            "answer " + index,
            score,
            "feedback " + index,
            "reference " + index,
            List.of("key point"),
            LocalDateTime.of(2026, 4, 23, 10, index)
        );
    }
}
