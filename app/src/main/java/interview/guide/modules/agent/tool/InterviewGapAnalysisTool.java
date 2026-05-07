package interview.guide.modules.agent.tool;

import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.interview.InterviewGapAnalyzer;
import interview.guide.modules.agent.tool.interview.InterviewToolContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于已评估面试结果输出短板分析。
 * 该 Tool 只基于已落库的评估结论做本地分析，不再次调用模型。
 */
@Component
@RequiredArgsConstructor
public class InterviewGapAnalysisTool implements AgentTool {

    private static final String UNAVAILABLE_SUMMARY = "当前暂无可用评估结果，暂不输出短板分析。";

    private final InterviewToolContextService interviewToolContextService;
    private final InterviewGapAnalyzer interviewGapAnalyzer;

    @Override
    public String name() {
        return "analyze_interview_gaps";
    }

    @Override
    public String description() {
        return "分析指定或最近一次已评估面试的低分维度、重复改进项和练习优先级。输入: { sessionId, resumeId }";
    }

    @Override
    public List<List<String>> requiredAnyOfInputs() {
        return List.of(List.of("sessionId", "resumeId"));
    }

    @Override
    public List<String> allowedInputs() {
        return List.of("sessionId", "resumeId");
    }

    @Override
    public AgentToolRiskLevel riskLevel() {
        return AgentToolRiskLevel.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        // 1. 先解析目标面试：显式 sessionId 优先，否则从 resumeId 下寻找最近一次已评估面试。
        InterviewToolContextService.AnalysisSource source = interviewToolContextService.loadGapAnalysisSource(input, context);
        if (source.detail() == null) {
            // 2. 没有可分析详情时返回稳定的 unavailable payload，而不是抛出业务异常中断整轮。
            return new AgentToolResult(
                UNAVAILABLE_SUMMARY,
                buildUnavailablePayload(source),
                buildDebugPayload(source, false),
                List.of()
            );
        }

        // 3. 有详情时执行本地规则分析，并把低分维度写成 memory 可复用事实。
        InterviewGapAnalyzer.InterviewGapAnalysis analysis = interviewGapAnalyzer.analyze(source.detail());
        return new AgentToolResult(
            analysis.summary(),
            buildAvailablePayload(source, analysis),
            buildDebugPayload(source, true),
            buildConfirmedFacts(analysis)
        );
    }

    private Map<String, Object> buildUnavailablePayload(InterviewToolContextService.AnalysisSource source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumeId", source.resumeId());
        payload.put("selectedSessionId", source.sessionId());
        payload.put("available", false);
        payload.put("overallScore", null);
        payload.put("summary", UNAVAILABLE_SUMMARY);
        payload.put("lowCategories", List.of());
        payload.put("repeatedImprovements", List.of());
        payload.put("knowledgeGapTags", List.of());
        payload.put("practicePriorities", List.of());
        return payload;
    }

    private Map<String, Object> buildAvailablePayload(
        InterviewToolContextService.AnalysisSource source,
        InterviewGapAnalyzer.InterviewGapAnalysis analysis
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumeId", source.resumeId());
        payload.put("selectedSessionId", source.sessionId());
        payload.put("available", analysis.available());
        payload.put("overallScore", source.detail().overallScore());
        payload.put("summary", analysis.summary());
        payload.put("lowCategories", analysis.lowCategories().stream().map(this::toCategoryPayload).toList());
        payload.put("repeatedImprovements", analysis.repeatedImprovements());
        payload.put("knowledgeGapTags", analysis.knowledgeGapTags());
        payload.put("practicePriorities", analysis.practicePriorities());
        return payload;
    }

    private Map<String, Object> buildDebugPayload(
        InterviewToolContextService.AnalysisSource source,
        boolean detailAvailable
    ) {
        Map<String, Object> debugPayload = new LinkedHashMap<>();
        debugPayload.put("selectedSessionId", source.sessionId());
        debugPayload.put("usedFallback", source.usedFallback());
        debugPayload.put("fallbackReason", source.fallbackReason());
        debugPayload.put("detailAvailable", detailAvailable);
        return debugPayload;
    }

    private List<String> buildConfirmedFacts(InterviewGapAnalyzer.InterviewGapAnalysis analysis) {
        String lowCategories = analysis.lowCategories().stream()
            .map(InterviewGapAnalyzer.CategoryInsight::category)
            .filter(category -> category != null && !category.isBlank())
            .collect(Collectors.joining("、"));
        if (lowCategories.isBlank()) {
            return List.of();
        }
        return List.of("低分维度: " + lowCategories);
    }

    private Map<String, Object> toCategoryPayload(InterviewGapAnalyzer.CategoryInsight insight) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", insight.category());
        payload.put("averageScore", insight.averageScore());
        payload.put("answerCount", insight.answerCount());
        payload.put("reason", insight.reason());
        return payload;
    }
}
