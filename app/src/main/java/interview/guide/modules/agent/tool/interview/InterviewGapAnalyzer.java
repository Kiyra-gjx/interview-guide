package interview.guide.modules.agent.tool.interview;

import interview.guide.modules.interview.model.InterviewDetailDTO;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面试短板分析器，基于已加载的面试详情做本地规则分析。
 */
@Component
public class InterviewGapAnalyzer {

    private static final int WEAK_CATEGORY_THRESHOLD = 75;
    private static final int MAX_LOW_CATEGORY_COUNT = 3;
    private static final String UNAVAILABLE_SUMMARY = "评估未完成，暂不输出短板分析";
    private static final String NO_GAP_SUMMARY = "评估已完成，但暂无逐题短板信号";

    /**
     * 生成稳定、轻量的短板分析结果，不依赖外部服务。
     */
    public InterviewGapAnalysis analyze(InterviewDetailDTO detail) {
        if (detail == null || detail.overallScore() == null) {
            return unavailable();
        }

        List<CategoryInsight> lowCategories = buildCategoryInsights(detail.answers());
        List<String> repeatedImprovements = deduplicate(detail.improvements());
        List<String> knowledgeGapTags = buildKnowledgeGapTags(lowCategories, repeatedImprovements);
        List<String> practicePriorities = buildPracticePriorities(lowCategories, repeatedImprovements);

        return new InterviewGapAnalysis(
            true,
            buildSummary(detail.overallScore(), lowCategories, repeatedImprovements),
            lowCategories,
            repeatedImprovements,
            knowledgeGapTags,
            practicePriorities
        );
    }

    private InterviewGapAnalysis unavailable() {
        return new InterviewGapAnalysis(false, UNAVAILABLE_SUMMARY, List.of(), List.of(), List.of(), List.of());
    }

    private List<CategoryInsight> buildCategoryInsights(List<InterviewDetailDTO.AnswerDetailDTO> answers) {
        Map<String, CategoryStats> statsByCategory = new LinkedHashMap<>();
        for (InterviewDetailDTO.AnswerDetailDTO answer : safeList(answers)) {
            if (answer == null || answer.score() == null || isBlank(answer.category())) {
                continue;
            }
            String category = answer.category().trim();
            CategoryStats stats = statsByCategory.computeIfAbsent(
                category,
                ignored -> new CategoryStats(category, statsByCategory.size())
            );
            stats.add(answer.score());
        }

        return statsByCategory.values().stream()
            .filter(CategoryStats::isWeakCategory)
            .sorted(Comparator
                .comparingInt(CategoryStats::averageScore)
                .thenComparingInt(CategoryStats::firstAppearance))
            .limit(MAX_LOW_CATEGORY_COUNT)
            .map(CategoryStats::toInsight)
            .toList();
    }

    private List<String> deduplicate(List<String> improvements) {
        Set<String> ordered = new LinkedHashSet<>();
        for (String improvement : safeList(improvements)) {
            if (!isBlank(improvement)) {
                ordered.add(improvement.trim());
            }
        }
        return List.copyOf(ordered);
    }

    private List<String> buildKnowledgeGapTags(List<CategoryInsight> lowCategories, List<String> improvements) {
        Set<String> tags = new LinkedHashSet<>();
        for (CategoryInsight category : lowCategories) {
            addTag(tags, mapTextToTag(category.category()));
        }
        for (String improvement : improvements) {
            addTag(tags, mapTextToTag(improvement));
        }
        return List.copyOf(tags);
    }

    private void addTag(Set<String> tags, String tag) {
        if (tag != null) {
            tags.add(tag);
        }
    }

    /**
     * 将短板信号映射为业务侧可读标签，无法识别时保留原始文本。
     */
    private String mapTextToTag(String text) {
        if (isBlank(text)) {
            return null;
        }
        String original = text.trim();
        String normalized = original.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "数据库", "sql", "mysql", "索引", "事务", "查询")) {
            return "数据库基础";
        }
        if (containsAny(normalized, "系统设计", "架构", "高可用", "扩展", "容量")) {
            return "系统设计";
        }
        if (containsAny(normalized, "java", "jvm", "集合", "基础")) {
            return "Java基础";
        }
        if (containsAny(normalized, "算法", "复杂度", "时间复杂度", "空间复杂度")) {
            return "算法复杂度";
        }
        if (containsAny(normalized, "并发", "线程", "锁", "同步")) {
            return "并发编程";
        }
        if (containsAny(normalized, "项目", "业务", "落地")) {
            return "项目量化表达";
        }
        if (containsAny(normalized, "表达", "沟通", "结构", "star")) {
            return "沟通表达";
        }
        return original;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildPracticePriorities(List<CategoryInsight> lowCategories, List<String> improvements) {
        Set<String> priorities = new LinkedHashSet<>();
        lowCategories.stream()
            .map(category -> "优先复盘「" + category.category() + "」相关题目，目标是把平均得分从 "
                + category.averageScore() + " 分提升到 75 分以上")
            .forEach(priorities::add);
        improvements.stream()
            .limit(2)
            .map(improvement -> "针对改进项「" + improvement + "」整理示例答案并进行口述练习")
            .forEach(priorities::add);
        return List.copyOf(priorities);
    }

    private String buildSummary(Integer overallScore, List<CategoryInsight> lowCategories, List<String> improvements) {
        if (lowCategories.isEmpty() && improvements.isEmpty()) {
            return NO_GAP_SUMMARY;
        }
        return "综合得分 " + overallScore + "，识别到 " + lowCategories.size()
            + " 个分类短板和 " + improvements.size() + " 条改进项";
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 短板分析结果，列表顺序保持规则推导顺序。
     */
    public record InterviewGapAnalysis(
        boolean available,
        String summary,
        List<CategoryInsight> lowCategories,
        List<String> repeatedImprovements,
        List<String> knowledgeGapTags,
        List<String> practicePriorities
    ) {
        public InterviewGapAnalysis {
            summary = summary == null ? "" : summary;
            lowCategories = lowCategories == null ? List.of() : List.copyOf(lowCategories);
            repeatedImprovements = repeatedImprovements == null ? List.of() : List.copyOf(repeatedImprovements);
            knowledgeGapTags = knowledgeGapTags == null ? List.of() : List.copyOf(knowledgeGapTags);
            practicePriorities = practicePriorities == null ? List.of() : List.copyOf(practicePriorities);
        }
    }

    /**
     * 单个面试分类的得分洞察。
     */
    public record CategoryInsight(
        String category,
        int averageScore,
        int answerCount,
        String reason
    ) {
        public CategoryInsight {
            category = category == null ? "" : category;
            reason = reason == null ? "" : reason;
        }
    }

    private static final class CategoryStats {
        private final String category;
        private final int firstAppearance;
        private int totalScore;
        private int answerCount;

        private CategoryStats(String category, int firstAppearance) {
            this.category = category;
            this.firstAppearance = firstAppearance;
        }

        private void add(int score) {
            totalScore += score;
            answerCount++;
        }

        private int averageScore() {
            return Math.round((float) totalScore / answerCount);
        }

        private int firstAppearance() {
            return firstAppearance;
        }

        private boolean isWeakCategory() {
            return averageScore() < WEAK_CATEGORY_THRESHOLD;
        }

        private CategoryInsight toInsight() {
            int averageScore = averageScore();
            return new CategoryInsight(
                category,
                averageScore,
                answerCount,
                "该分类平均得分 " + averageScore + "，是本次面试的相对薄弱方向"
            );
        }
    }
}
