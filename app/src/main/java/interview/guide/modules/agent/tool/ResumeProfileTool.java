package interview.guide.modules.agent.tool;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.service.ResumeHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取简历画像 Tool。
 * 只读取简历和最近一次分析结果，不触发新的简历解析或打分任务。
 */
@Component
@RequiredArgsConstructor
public class ResumeProfileTool implements AgentTool {

    private static final int PREVIEW_LIMIT = 500;

    private final ResumeHistoryService resumeHistoryService;

    @Override
    public String name() {
        return "get_resume_profile";
    }

    @Override
    public String description() {
        return "根据 resumeId 读取简历摘要、最近分析结果、优势项和历史面试数量。输入: { resumeId }";
    }

    @Override
    public List<String> requiredInputs() {
        return List.of("resumeId");
    }

    @Override
    public AgentToolRiskLevel riskLevel() {
        return AgentToolRiskLevel.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        // 1. 模型没有显式传 resumeId 时，回退到当前 Agent 会话绑定的简历。
        Long resumeId = readLong(input.get("resumeId"));
        if (resumeId == null) {
            resumeId = context.resumeId();
        }
        if (resumeId == null) {
            throw new BusinessException(ErrorCode.AGENT_INVALID_INPUT, "get_resume_profile 缺少 resumeId");
        }

        // 2. 简历详情中可能没有分析历史，后续字段都按“无最新分析”做空值收敛。
        ResumeDetailDTO detail = resumeHistoryService.getResumeDetail(resumeId);
        ResumeDetailDTO.AnalysisHistoryDTO latest = detail.analyses() == null || detail.analyses().isEmpty()
            ? null
            : detail.analyses().getFirst();

        List<String> strengths = latest == null || latest.strengths() == null
            ? List.of()
            : latest.strengths();
        List<String> suggestions = latest == null || latest.suggestions() == null
            ? List.of()
            : latest.suggestions().stream()
                .map(this::formatSuggestion)
                .limit(3)
                .toList();
        String summary = latest == null ? "" : nullToEmpty(latest.summary());

        // 3. answerPayload 给最终回答使用，保留简历预览、摘要、优势和建议。
        Map<String, Object> answerPayload = new LinkedHashMap<>();
        answerPayload.put("resumeId", resumeId);
        answerPayload.put("filename", detail.filename());
        answerPayload.put("resumeTextPreview", preview(detail.resumeText()));
        answerPayload.put("latestSummary", summary);
        answerPayload.put("strengths", strengths);
        answerPayload.put("suggestions", suggestions);
        answerPayload.put("interviewCount", detail.interviews() == null ? 0 : detail.interviews().size());

        // 4. memory 只写入稳定事实，避免把完整简历文本跨轮回灌进上下文。
        List<String> facts = new ArrayList<>();
        if (!summary.isBlank()) {
            facts.add("简历摘要: " + summary);
        }
        if (!strengths.isEmpty()) {
            facts.add("候选人优势: " + String.join("、", strengths.stream().limit(3).toList()));
        }
        facts.add("已绑定简历ID: " + resumeId);

        return new AgentToolResult(
            "已读取简历画像，包含摘要、优势和历史面试数量。",
            answerPayload,
            Map.of(),
            facts
        );
    }

    private String formatSuggestion(ResumeAnalysisResponse.Suggestion suggestion) {
        return nullToEmpty(suggestion.category()) + ": " + nullToEmpty(suggestion.recommendation());
    }

    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String preview(String resumeText) {
        String normalized = nullToEmpty(resumeText).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LIMIT) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
