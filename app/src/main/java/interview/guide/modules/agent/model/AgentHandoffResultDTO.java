package interview.guide.modules.agent.model;

import interview.guide.modules.agent.support.AgentToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控只读委派返回的结构化结果。
 * 这里限制子执行体只能基于冻结上下文做分析，不能调用工具，也不能写外部状态。
 */
public record AgentHandoffResultDTO(
    String summary,
    List<String> confirmedFacts,
    String nextFocus,
    String suggestedReply
) {

    public AgentHandoffResultDTO {
        summary = normalize(summary);
        nextFocus = normalize(nextFocus);
        suggestedReply = normalize(suggestedReply);
        confirmedFacts = confirmedFacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(confirmedFacts));
    }

    /**
     * 转成统一的 ToolResult 视图，复用现有 memory、trace 与 workbench 收口链路。
     */
    public AgentToolResult toToolResult(
        String delegateTask,
        String delegateReason,
        String delegateExpectedOutput
    ) {
        Map<String, Object> answerPayload = new LinkedHashMap<>();
        answerPayload.put("summary", defaultText(summary, nextFocus));
        answerPayload.put("suggestedReply", defaultText(suggestedReply, ""));
        answerPayload.put("nextFocus", defaultText(nextFocus, ""));
        answerPayload.put("confirmedFacts", confirmedFacts);

        Map<String, Object> debugPayload = new LinkedHashMap<>();
        debugPayload.put("delegateTask", defaultText(delegateTask, ""));
        debugPayload.put("delegateReason", defaultText(delegateReason, ""));
        debugPayload.put("delegateExpectedOutput", defaultText(delegateExpectedOutput, ""));
        debugPayload.put("readOnly", true);
        debugPayload.put("toolCallsAllowed", false);
        debugPayload.put("writesAllowed", false);

        return new AgentToolResult(
            defaultText(summary, defaultText(nextFocus, "delegated_summary_available")),
            Collections.unmodifiableMap(answerPayload),
            Collections.unmodifiableMap(debugPayload),
            confirmedFacts
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
