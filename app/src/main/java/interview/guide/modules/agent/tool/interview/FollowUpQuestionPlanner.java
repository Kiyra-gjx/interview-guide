package interview.guide.modules.agent.tool.interview;

import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer.CategoryInsight;
import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer.InterviewGapAnalysis;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 面试追问规划器，按优先级生成轻量追问建议。
 */
@Component
public class FollowUpQuestionPlanner {

    /**
     * 按关注分类、短板分类、答题分类的顺序生成追问建议。
     */
    public List<FollowUpSuggestion> plan(
        InterviewDetailDTO detail,
        InterviewGapAnalysis analysis,
        String focusCategory,
        int maxCount
    ) {
        if (maxCount <= 0) {
            return List.of();
        }

        Set<String> categories = new LinkedHashSet<>();
        addCategory(categories, focusCategory);
        addLowCategories(categories, analysis);
        addAnswerCategories(categories, detail);

        return categories.stream()
            .limit(maxCount)
            .map(category -> buildSuggestion(category, analysis))
            .toList();
    }

    private void addLowCategories(Set<String> categories, InterviewGapAnalysis analysis) {
        if (analysis == null) {
            return;
        }
        for (CategoryInsight insight : analysis.lowCategories()) {
            addCategory(categories, insight.category());
        }
    }

    private void addAnswerCategories(Set<String> categories, InterviewDetailDTO detail) {
        if (detail == null || detail.answers() == null) {
            return;
        }
        for (InterviewDetailDTO.AnswerDetailDTO answer : detail.answers()) {
            if (answer != null) {
                addCategory(categories, answer.category());
            }
        }
    }

    private void addCategory(Set<String> categories, String category) {
        if (category != null && !category.isBlank()) {
            categories.add(category.trim());
        }
    }

    private FollowUpSuggestion buildSuggestion(String category, InterviewGapAnalysis analysis) {
        String normalizedCategory = normalizeCategory(category);
        return new FollowUpSuggestion(
            questionFor(category, normalizedCategory),
            category,
            reasonFor(category, analysis),
            coachingTipFor(normalizedCategory)
        );
    }

    /**
     * 统一归一化一次分类文本，避免 SQL/MySQL 等大小写变体漏匹配。
     */
    private String normalizeCategory(String category) {
        return category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
    }

    private String questionFor(String category, String normalizedCategory) {
        if (containsAny(normalizedCategory, "数据库", "sql", "mysql")) {
            return "针对数据库题，请解释一次慢查询从定位、索引设计到验证执行计划的完整处理步骤，并说明取舍。";
        }
        if (containsAny(normalizedCategory, "系统设计", "架构")) {
            return "针对系统设计题，请选择一个熟悉业务场景，给出核心模块、数据流、瓶颈和扩展策略。";
        }
        if (containsAny(normalizedCategory, "java", "jvm", "集合", "基础")) {
            return "针对 Java 基础题，请用一个集合或并发示例说明底层机制、适用场景和常见陷阱。";
        }
        if (containsAny(normalizedCategory, "算法", "复杂度")) {
            return "针对算法题，请给出解法思路、复杂度推导、边界条件和可替代方案。";
        }
        if (containsAny(normalizedCategory, "行为", "沟通")) {
            return "针对行为面试题，请用 STAR 结构复盘一次冲突或失败经历，并说明决策依据和结果指标。";
        }
        return "针对 " + category + " 题，请选一道已回答题目，按背景、关键约束、解决步骤、风险权衡和验证方式重组答案。";
    }

    private String reasonFor(String category, InterviewGapAnalysis analysis) {
        if (analysis == null) {
            return "该方向来自本次面试答题分类，可用于补齐追问覆盖。";
        }
        return analysis.lowCategories().stream()
            .filter(insight -> category.equals(insight.category()))
            .findFirst()
            .map(CategoryInsight::reason)
            .filter(reason -> !reason.isBlank())
            .orElse("该方向来自本次面试答题分类，可用于补齐追问覆盖。");
    }

    private String coachingTipFor(String normalizedCategory) {
        if (containsAny(normalizedCategory, "数据库", "sql", "mysql")) {
            return "回答时先说明观测指标，再讲索引与执行计划，最后补充验证方式。";
        }
        if (containsAny(normalizedCategory, "系统设计", "架构")) {
            return "先限定规模和约束，再按模块、数据流、可靠性和扩展性组织答案。";
        }
        if (containsAny(normalizedCategory, "java", "jvm", "集合", "基础")) {
            return "用一个小例子串联概念、底层机制、适用场景和常见坑点。";
        }
        if (containsAny(normalizedCategory, "算法", "复杂度")) {
            return "先讲朴素解法，再说明优化点和复杂度变化。";
        }
        return "用结构化顺序回答：结论、依据、步骤、权衡和验证。";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单条追问建议。
     */
    public record FollowUpSuggestion(
        String question,
        String focusArea,
        String reason,
        String coachingTip
    ) {
        public FollowUpSuggestion {
            question = question == null ? "" : question;
            focusArea = focusArea == null ? "" : focusArea;
            reason = reason == null ? "" : reason;
            coachingTip = coachingTip == null ? "" : coachingTip;
        }
    }
}
