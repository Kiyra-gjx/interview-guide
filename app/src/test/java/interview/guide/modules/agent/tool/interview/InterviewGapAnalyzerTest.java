package interview.guide.modules.agent.tool.interview;

import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer.CategoryInsight;
import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer.InterviewGapAnalysis;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewGapAnalyzerTest {

    private static final String DATABASE = "数据库";
    private static final String SYSTEM_DESIGN = "系统设计";
    private static final String JAVA_CORE = "Java基础";
    private static final String DOMAIN_MODELING = "领域建模";
    private static final String COMMUNICATION = "沟通表达";
    private static final String PROJECT_EXPERIENCE = "项目经验";
    private static final String DATABASE_IMPROVEMENT = "需要补强索引优化和 SQL 调优";
    private static final String UNMATCHED_IMPROVEMENT = "需要补充缓存命中率取舍";

    private final InterviewGapAnalyzer analyzer = new InterviewGapAnalyzer();

    @Test
    @DisplayName("should register analyzer as spring bean")
    void shouldRegisterAnalyzerAsSpringBean() {
        assertThat(InterviewGapAnalyzer.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Test
    @DisplayName("should only keep weak categories in deterministic order")
    void shouldOnlyKeepWeakCategoriesInDeterministicOrder() {
        InterviewDetailDTO detail = detail(
            91,
            List.of(
                answer(0, SYSTEM_DESIGN, 62),
                answer(1, DATABASE, 42),
                answer(2, JAVA_CORE, 83),
                answer(3, COMMUNICATION, 70),
                answer(4, PROJECT_EXPERIENCE, 78),
                answer(5, DATABASE, 48)
            ),
            List.of(
                DATABASE_IMPROVEMENT,
                DATABASE_IMPROVEMENT,
                "回答时补充复杂度分析"
            )
        );

        InterviewGapAnalysis analysis = analyzer.analyze(detail);

        assertThat(analysis.available()).isTrue();
        assertThat(analysis.lowCategories())
            .extracting(CategoryInsight::category)
            .containsExactly(DATABASE, SYSTEM_DESIGN, COMMUNICATION);
        assertThat(analysis.lowCategories())
            .extracting(CategoryInsight::averageScore)
            .containsExactly(45, 62, 70);
        assertThat(analysis.repeatedImprovements())
            .containsExactly(DATABASE_IMPROVEMENT, "回答时补充复杂度分析");
        assertThat(analysis.knowledgeGapTags())
            .containsExactly("数据库基础", "系统设计", "沟通表达", "算法复杂度");
        assertThat(analysis.summary())
            .isEqualTo("综合得分 91，识别到 3 个分类短板和 2 条改进项");
        assertThat(analysis.practicePriorities()).hasSize(5);
        assertThat(analysis.practicePriorities().subList(0, 3))
            .allSatisfy(priority -> assertThat(priority)
                .doesNotContain(JAVA_CORE)
                .doesNotContain(PROJECT_EXPERIENCE));
        assertThat(analysis.practicePriorities().get(0)).contains(DATABASE);
        assertThat(analysis.practicePriorities().get(1)).contains(SYSTEM_DESIGN);
        assertThat(analysis.practicePriorities().get(2)).contains(COMMUNICATION);
    }

    @Test
    @DisplayName("should map knowledge gap tags to business labels and preserve unmatched signals")
    void shouldMapKnowledgeGapTagsToBusinessLabelsAndPreserveUnmatchedSignals() {
        InterviewDetailDTO detail = detail(
            84,
            List.of(
                answer(0, DATABASE, 58),
                answer(1, DOMAIN_MODELING, 61),
                answer(2, PROJECT_EXPERIENCE, 70),
                answer(3, JAVA_CORE, 82)
            ),
            List.of(
                DATABASE_IMPROVEMENT,
                UNMATCHED_IMPROVEMENT
            )
        );

        InterviewGapAnalysis analysis = analyzer.analyze(detail);

        assertThat(analysis.lowCategories())
            .extracting(CategoryInsight::category)
            .containsExactly(DATABASE, DOMAIN_MODELING, PROJECT_EXPERIENCE);
        assertThat(analysis.knowledgeGapTags())
            .containsExactly("数据库基础", DOMAIN_MODELING, "项目量化表达", UNMATCHED_IMPROVEMENT);
    }

    @Test
    @DisplayName("should return unavailable analysis when no evaluated signal exists")
    void shouldReturnUnavailableAnalysisWhenNoEvaluatedSignalExists() {
        InterviewGapAnalysis nullDetailAnalysis = analyzer.analyze(null);
        InterviewGapAnalysis unfinishedAnalysis = analyzer.analyze(detail(null, List.of(answer(0, DATABASE, 40)), List.of()));

        assertThat(nullDetailAnalysis.available()).isFalse();
        assertThat(nullDetailAnalysis.summary()).isEqualTo("评估未完成，暂不输出短板分析");
        assertThat(nullDetailAnalysis.lowCategories()).isEmpty();
        assertThat(nullDetailAnalysis.knowledgeGapTags()).isEmpty();

        assertThat(unfinishedAnalysis.available()).isFalse();
        assertThat(unfinishedAnalysis.summary()).isEqualTo("评估未完成，暂不输出短板分析");
        assertThat(unfinishedAnalysis.practicePriorities()).isEmpty();
    }

    private InterviewDetailDTO detail(
        Integer overallScore,
        List<InterviewDetailDTO.AnswerDetailDTO> answers,
        List<String> improvements
    ) {
        return new InterviewDetailDTO(
            1L,
            "session-001",
            answers.size(),
            "EVALUATED",
            null,
            null,
            overallScore,
            "overall feedback",
            LocalDateTime.of(2026, 4, 23, 10, 0),
            LocalDateTime.of(2026, 4, 23, 10, 30),
            List.of(),
            List.of(),
            improvements,
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
