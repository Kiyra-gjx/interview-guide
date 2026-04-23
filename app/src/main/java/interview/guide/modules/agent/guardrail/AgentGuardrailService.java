package interview.guide.modules.agent.guardrail;

import interview.guide.modules.agent.tool.AgentTool;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agent Guardrail 服务。
 * 这里先提供最小可用的输入拦截入口，后续工具和输出规则继续在同一抽象下扩展。
 */
@Service
public class AgentGuardrailService {

    private static final int INPUT_MESSAGE_MAX_LENGTH = 4000;
    private static final Pattern EXTRACTION_INTENT_PATTERN = Pattern.compile(
        "(输出|打印|展示|显示|贴出|透露|泄露|给我看|reveal|show|dump|print|expose)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INTERNAL_TARGET_PATTERN = Pattern.compile(
        "(system\\s*prompt|系统提示词|内部规则|内部推理|chain\\s*of\\s*thought|memorybefore|memoryafter|debugpayload|toolinputjson)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAW_JSON_REPLY_PATTERN = Pattern.compile("^\\s*[\\[{].*[\\]}]\\s*$", Pattern.DOTALL);
    private static final Pattern INTERNAL_OUTPUT_FIELD_PATTERN = Pattern.compile(
        "\\b(debugpayload|toolinputjson|memorybefore|memoryafter|answerpayload)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 评估输入 Guardrail。
     * 当前只实现最小可用规则：控制字符、超长消息、内部数据抽取请求。
     */
    public InputGuardrailDecision evaluateInput(String message) {
        String normalizedMessage = normalize(message);
        if (containsUnsupportedControlCharacter(normalizedMessage)) {
            return InputGuardrailDecision.blocked(
                normalizedMessage,
                new AgentGuardrailResult(
                    AgentGuardrailStage.INPUT,
                    AgentGuardrailCode.INPUT_CONTROL_CHARACTERS,
                    AgentGuardrailAction.REJECT,
                    AgentGuardrailResolution.RETURN_SAFE_REPLY,
                    "输入包含不可接受的控制字符"
                )
            );
        }
        if (normalizedMessage.length() > INPUT_MESSAGE_MAX_LENGTH) {
            return InputGuardrailDecision.blocked(
                normalizedMessage,
                new AgentGuardrailResult(
                    AgentGuardrailStage.INPUT,
                    AgentGuardrailCode.INPUT_MESSAGE_TOO_LONG,
                    AgentGuardrailAction.REJECT,
                    AgentGuardrailResolution.RETURN_SAFE_REPLY,
                    "输入长度超过安全处理阈值"
                )
            );
        }
        if (isInternalDataExtractionRequest(normalizedMessage)) {
            return InputGuardrailDecision.blocked(
                normalizedMessage,
                new AgentGuardrailResult(
                    AgentGuardrailStage.INPUT,
                    AgentGuardrailCode.INPUT_INTERNAL_DATA_REQUEST,
                    AgentGuardrailAction.REJECT,
                    AgentGuardrailResolution.RETURN_SAFE_REPLY,
                    "请求暴露系统提示词或内部调试信息"
                )
            );
        }
        return InputGuardrailDecision.allowed(normalizedMessage);
    }

    /**
     * 评估工具 Guardrail。
     * 当前只负责工具输入面的安全校验，例如未声明参数拦截；
     * “高风险但可审批”的策略已经移动到 orchestrator 层统一处理。
     */
    public ToolGuardrailDecision evaluateTool(AgentTool tool, Map<String, Object> toolInput) {
        Map<String, Object> normalizedInput = toolInput == null
            ? Map.of()
            : immutableToolInput(toolInput);
        List<String> allowedInputs = tool.allowedInputs();
        if (allowedInputs == null || allowedInputs.isEmpty()) {
            allowedInputs = tool.requiredInputs() == null ? List.of() : List.copyOf(tool.requiredInputs());
        }
        List<String> effectiveAllowedInputs = allowedInputs;
        List<String> unexpectedInputs = normalizedInput.keySet().stream()
            .filter(key -> !effectiveAllowedInputs.contains(key))
            .sorted()
            .toList();
        if (!unexpectedInputs.isEmpty()) {
            return ToolGuardrailDecision.blocked(
                normalizedInput,
                new AgentGuardrailResult(
                    AgentGuardrailStage.TOOL,
                    AgentGuardrailCode.TOOL_UNEXPECTED_INPUT,
                    AgentGuardrailAction.REJECT,
                    AgentGuardrailResolution.BLOCK_TOOL_CALL,
                    "工具收到未声明参数: " + String.join(", ", unexpectedInputs)
                )
            );
        }
        return ToolGuardrailDecision.allowed(normalizedInput);
    }

    /**
     * 评估输出 Guardrail。
     * 当前先拦截空回答、原始 JSON 以及明显的内部字段泄漏。
     */
    public OutputGuardrailDecision evaluateOutput(String reply, String fallbackReply) {
        String normalizedReply = normalize(reply);
        String normalizedFallback = normalize(fallbackReply);
        if (normalizedReply.isBlank()) {
            return OutputGuardrailDecision.degraded(
                safeFallbackReply(normalizedFallback),
                new AgentGuardrailResult(
                    AgentGuardrailStage.OUTPUT,
                    AgentGuardrailCode.OUTPUT_EMPTY_REPLY,
                    AgentGuardrailAction.DEGRADE,
                    AgentGuardrailResolution.REPLACE_WITH_FALLBACK_REPLY,
                    "最终回复为空，已降级为安全回复"
                )
            );
        }
        if (RAW_JSON_REPLY_PATTERN.matcher(normalizedReply).matches()) {
            return OutputGuardrailDecision.degraded(
                safeFallbackReply(normalizedFallback),
                new AgentGuardrailResult(
                    AgentGuardrailStage.OUTPUT,
                    AgentGuardrailCode.OUTPUT_RAW_JSON_REPLY,
                    AgentGuardrailAction.DEGRADE,
                    AgentGuardrailResolution.REPLACE_WITH_FALLBACK_REPLY,
                    "最终回复呈现为原始 JSON 结构"
                )
            );
        }
        if (INTERNAL_OUTPUT_FIELD_PATTERN.matcher(normalizedReply).find()) {
            return OutputGuardrailDecision.degraded(
                safeFallbackReply(normalizedFallback),
                new AgentGuardrailResult(
                    AgentGuardrailStage.OUTPUT,
                    AgentGuardrailCode.OUTPUT_SENSITIVE_FIELD_LEAK,
                    AgentGuardrailAction.DEGRADE,
                    AgentGuardrailResolution.REPLACE_WITH_FALLBACK_REPLY,
                    "最终回复包含内部字段或敏感信息"
                )
            );
        }
        return OutputGuardrailDecision.allowed(normalizedReply);
    }

    private boolean containsUnsupportedControlCharacter(String value) {
        return value.chars().anyMatch(ch -> Character.isISOControl(ch) && !Character.isWhitespace(ch));
    }

    private boolean isInternalDataExtractionRequest(String value) {
        return EXTRACTION_INTENT_PATTERN.matcher(value).find()
            && INTERNAL_TARGET_PATTERN.matcher(value).find();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeFallbackReply(String fallbackReply) {
        return fallbackReply.isBlank()
            ? "本轮回复触发了输出安全保护，我先返回保守结果。请换一种更直接的提问方式后重试。"
            : fallbackReply;
    }

    /**
     * 复制工具输入并保留 null 值，避免 Map.copyOf 在 guardrail 判定前抛异常。
     */
    private Map<String, Object> immutableToolInput(Map<String, Object> toolInput) {
        if (toolInput == null || toolInput.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(toolInput));
    }

    /**
     * 输入 Guardrail 的评估结果。
     */
    public record InputGuardrailDecision(
        String normalizedMessage,
        AgentGuardrailResult result
    ) {
        public static InputGuardrailDecision allowed(String normalizedMessage) {
            return new InputGuardrailDecision(normalizedMessage, null);
        }

        public static InputGuardrailDecision blocked(String normalizedMessage, AgentGuardrailResult result) {
            return new InputGuardrailDecision(normalizedMessage, result);
        }

        public boolean blocked() {
            return result != null;
        }

        public List<AgentGuardrailResult> guardrailResults() {
            return result == null ? List.of() : List.of(result);
        }
    }

    /**
     * 工具 Guardrail 的评估结果。
     */
    public record ToolGuardrailDecision(
        Map<String, Object> toolInput,
        AgentGuardrailResult result
    ) {
        public ToolGuardrailDecision {
            toolInput = toolInput == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(toolInput));
        }

        public static ToolGuardrailDecision allowed(Map<String, Object> toolInput) {
            return new ToolGuardrailDecision(toolInput, null);
        }

        public static ToolGuardrailDecision blocked(Map<String, Object> toolInput, AgentGuardrailResult result) {
            return new ToolGuardrailDecision(toolInput, result);
        }

        public boolean blocked() {
            return result != null;
        }

        public List<AgentGuardrailResult> guardrailResults() {
            return result == null ? List.of() : List.of(result);
        }
    }

    /**
     * 输出 Guardrail 的评估结果。
     */
    public record OutputGuardrailDecision(
        String reply,
        AgentGuardrailResult result
    ) {
        public static OutputGuardrailDecision allowed(String reply) {
            return new OutputGuardrailDecision(reply, null);
        }

        public static OutputGuardrailDecision degraded(String reply, AgentGuardrailResult result) {
            return new OutputGuardrailDecision(reply, result);
        }

        public boolean degraded() {
            return result != null;
        }

        public List<AgentGuardrailResult> guardrailResults() {
            return result == null ? List.of() : List.of(result);
        }
    }
}
