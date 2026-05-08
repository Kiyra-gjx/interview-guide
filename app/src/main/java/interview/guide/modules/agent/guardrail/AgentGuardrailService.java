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
 * 这里先提供最小可用的输入、工具和输出拦截规则。
 * 每一层只处理自己的边界问题，业务决策仍交给 orchestrator 统一收口。
 */
@Service
public class AgentGuardrailService {

    private static final int INPUT_MESSAGE_MAX_LENGTH = 4000;
    private static final Pattern EXTRACTION_INTENT_PATTERN = Pattern.compile(
        "(输出|打印|展示|显示|贴出|透露|泄露|给我看|reveal|show|dump|print|expose)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INTERNAL_TARGET_PATTERN = Pattern.compile(
        "(system\\s*prompt|系统提示词|内部规则|内部推理|chain\\s*of\\s*thought|memorybefore|memoryafter|debugpayload|answerpayload|toolinputjson|tool\\s*output|summarytruncated|answertruncated|debugtruncated|factstruncated|tool\\s*output\\s*\\.\\s*debug|tool\\s*output\\s*\\.\\s*normalization|normalization\\s+(?:json|payload|object|fields?|flags?|structure|结果|结构|对象|字段|标记))",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAW_JSON_REPLY_PATTERN = Pattern.compile("^\\s*[\\[{].*[\\]}]\\s*$", Pattern.DOTALL);
    private static final Pattern INTERNAL_OUTPUT_FIELD_PATTERN = Pattern.compile(
        "(\\bsystem\\s*prompt\\b\\s*(?:=|:|：))|(\\bchain\\s*of\\s*thought\\b\\s*(?:=|:|：))|(\\b(debugpayload|toolinputjson|memorybefore|memoryafter|answerpayload|summarytruncated|answertruncated|debugtruncated|factstruncated)\\b)|(\\btool\\s*output\\b\\s*(?:=|[:：]\\s*[\\[{]|\\.))|(\\bnormalization\\b\\s*(?:=|[:：]\\s*[\\[{]|\\.))",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 评估输入 Guardrail。
     * 当前覆盖控制字符、超长消息和内部数据抽取请求。
     */
    public InputGuardrailDecision evaluateInput(String message) {
        String normalizedMessage = normalize(message);
        // 1. 先拦截控制字符和超长输入，避免异常内容继续进入后续链路。
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
        // 2. 再识别内部数据抽取意图，只拦截结构化内部字段，不误伤普通概念讨论。
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
     * 当前只负责工具输入面的安全校验，审批策略仍由 orchestrator 统一处理。
     */
    public ToolGuardrailDecision evaluateTool(AgentTool tool, Map<String, Object> toolInput) {
        // 1. 先复制输入，避免后续校验过程中被调用方继续修改。
        Map<String, Object> normalizedInput = toolInput == null
            ? Map.of()
            : immutableToolInput(toolInput);

        // 2. 再解析工具声明的输入白名单；没有显式 allowedInputs 时退回 requiredInputs。
        List<String> allowedInputs = tool.allowedInputs();
        if (allowedInputs == null || allowedInputs.isEmpty()) {
            allowedInputs = tool.requiredInputs() == null ? List.of() : List.copyOf(tool.requiredInputs());
        }

        // 3. 只允许工具声明过的输入字段进入执行阶段。
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
     * 当前覆盖空回复、原始 JSON 和明显的内部字段泄漏。
     */
    public OutputGuardrailDecision evaluateOutput(String reply, String fallbackReply) {
        String normalizedReply = normalize(reply);
        String normalizedFallback = normalize(fallbackReply);
        // 1. 先处理空回复和原始 JSON 这类直接不可接受的输出形态。
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
        // 2. 最后拦截结构化内部字段泄漏，但保留对普通 normalization 概念解释的正常回答。
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

    /**
     * 判断输入中是否含有不可接受的控制字符。
     */
    private boolean containsUnsupportedControlCharacter(String value) {
        return value.chars().anyMatch(ch -> Character.isISOControl(ch) && !Character.isWhitespace(ch));
    }

    /**
     * 判断当前输入是否在尝试抽取内部数据。
     */
    private boolean isInternalDataExtractionRequest(String value) {
        return EXTRACTION_INTENT_PATTERN.matcher(value).find()
            && INTERNAL_TARGET_PATTERN.matcher(value).find();
    }

    /**
     * 统一规整可空文本。
     */
    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 生成输出降级时的保守回复。
     */
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
