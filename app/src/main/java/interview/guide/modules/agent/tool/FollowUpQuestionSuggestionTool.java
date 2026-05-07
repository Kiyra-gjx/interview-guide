package interview.guide.modules.agent.tool;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.interview.FollowUpQuestionPlanner;
import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer;
import interview.guide.modules.agent.tool.interview.InterviewToolContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结合最近一次可用面试数据生成追问建议。
 * 这里做的是规则式追问规划，目标是给 Agent 一个可解释、可复盘的练习入口。
 */
@Component
@RequiredArgsConstructor
public class FollowUpQuestionSuggestionTool implements AgentTool {

    private static final int DEFAULT_MAX_COUNT = 3;
    private static final int MIN_MAX_COUNT = 1;
    private static final int MAX_MAX_COUNT = 5;
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final String AVAILABLE_SUMMARY = "已生成可继续练习的追问建议。";
    private static final String UNAVAILABLE_SUMMARY = "当前暂无可继续练习的追问建议。";

    private final InterviewToolContextService interviewToolContextService;
    private final InterviewGapAnalyzer interviewGapAnalyzer;
    private final FollowUpQuestionPlanner followUpQuestionPlanner;

    @Override
    public String name() {
        return "suggest_follow_up_questions";
    }

    @Override
    public String description() {
        return "根据最近面试表现生成 1 到 5 个具体追问建议。输入: { sessionId, resumeId, focusCategory, maxCount }";
    }

    @Override
    public List<List<String>> requiredAnyOfInputs() {
        return List.of(List.of("sessionId", "resumeId"));
    }

    @Override
    public List<String> allowedInputs() {
        return List.of("sessionId", "resumeId", "focusCategory", "maxCount");
    }

    @Override
    public AgentToolRiskLevel riskLevel() {
        return AgentToolRiskLevel.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        // 1. 先收敛可选参数，避免后续分析分支反复处理类型和范围校验。
        int maxCount = parseMaxCount(input);
        String focusCategory = parseOptionalString(input, "focusCategory");

        // 2. 再解析可用于追问的面试详情；优先已评估面试，必要时退到有题目数据的最近面试。
        InterviewToolContextService.AnalysisSource source = interviewToolContextService.loadFollowUpSource(input, context);
        if (source.detail() == null) {
            return buildUnavailableResult(source, focusCategory, maxCount);
        }

        // 3. 追问规划复用短板分析结果，让建议能解释“为什么问这个方向”。
        InterviewGapAnalyzer.InterviewGapAnalysis analysis = interviewGapAnalyzer.analyze(source.detail());
        List<FollowUpQuestionPlanner.FollowUpSuggestion> suggestions =
            followUpQuestionPlanner.plan(source.detail(), analysis, focusCategory, maxCount);
        if (suggestions.isEmpty()) {
            return buildUnavailableResult(source, focusCategory, maxCount);
        }

        return new AgentToolResult(
            AVAILABLE_SUMMARY,
            buildAvailablePayload(source, focusCategory, maxCount, suggestions),
            buildDebugPayload(source, focusCategory),
            List.of()
        );
    }

    private AgentToolResult buildUnavailableResult(
        InterviewToolContextService.AnalysisSource source,
        String focusCategory,
        int maxCount
    ) {
        return new AgentToolResult(
            UNAVAILABLE_SUMMARY,
            buildUnavailablePayload(source, focusCategory, maxCount),
            buildDebugPayload(source, focusCategory),
            List.of()
        );
    }

    private Map<String, Object> buildUnavailablePayload(
        InterviewToolContextService.AnalysisSource source,
        String focusCategory,
        int maxCount
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumeId", source.resumeId());
        payload.put("selectedSessionId", source.sessionId());
        payload.put("focusCategory", focusCategory);
        payload.put("maxCount", maxCount);
        payload.put("available", false);
        payload.put("suggestions", List.of());
        return payload;
    }

    private Map<String, Object> buildAvailablePayload(
        InterviewToolContextService.AnalysisSource source,
        String focusCategory,
        int maxCount,
        List<FollowUpQuestionPlanner.FollowUpSuggestion> suggestions
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumeId", source.resumeId());
        payload.put("selectedSessionId", source.sessionId());
        payload.put("focusCategory", focusCategory);
        payload.put("maxCount", maxCount);
        payload.put("available", true);
        payload.put("suggestions", suggestions.stream().map(this::toSuggestionPayload).toList());
        return payload;
    }

    private Map<String, Object> buildDebugPayload(
        InterviewToolContextService.AnalysisSource source,
        String focusCategory
    ) {
        Map<String, Object> debugPayload = new LinkedHashMap<>();
        debugPayload.put("selectedSessionId", source.sessionId());
        debugPayload.put("usedFallback", source.usedFallback());
        debugPayload.put("fallbackReason", source.fallbackReason());
        debugPayload.put("focusCategory", focusCategory);
        return debugPayload;
    }

    private Map<String, Object> toSuggestionPayload(FollowUpQuestionPlanner.FollowUpSuggestion suggestion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", suggestion.question());
        payload.put("focusArea", suggestion.focusArea());
        payload.put("reason", suggestion.reason());
        payload.put("coachingTip", suggestion.coachingTip());
        return payload;
    }

    private int parseMaxCount(Map<String, Object> input) {
        Long parsed = readOptionalLong(input, "maxCount");
        if (parsed == null) {
            return DEFAULT_MAX_COUNT;
        }
        return (int) Math.max(MIN_MAX_COUNT, Math.min(MAX_MAX_COUNT, parsed));
    }

    private String parseOptionalString(Map<String, Object> input, String fieldName) {
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
                throw invalidInput(fieldName, fieldName + " 不是合法整数");
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

    private Map<String, Object> safeInput(Map<String, Object> input) {
        return input == null ? Map.of() : input;
    }

    private BusinessException invalidInput(String fieldName, String message) {
        return new BusinessException(ErrorCode.AGENT_INVALID_INPUT, fieldName + ": " + message);
    }
}
