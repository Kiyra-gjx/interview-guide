package interview.guide.modules.agent.support;

import interview.guide.modules.agent.model.AgentToolOutputDTO;
import interview.guide.modules.agent.model.AgentToolOutputNormalizationDTO;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 执行结果。
 */
public record AgentToolResult(
    String summary,
    Map<String, Object> answerPayload,
    Map<String, Object> debugPayload,
    List<String> confirmedFacts
) {

    private static final int SUMMARY_LIMIT = 200;
    private static final int ANSWER_TEXT_LIMIT = 500;
    private static final int DEBUG_TEXT_LIMIT = 320;
    private static final int FACT_TEXT_LIMIT = 180;
    private static final int ANSWER_COLLECTION_LIMIT = 8;
    private static final int DEBUG_COLLECTION_LIMIT = 5;
    private static final int FACT_COUNT_LIMIT = 6;
    private static final int MAX_DEPTH = 4;

    public AgentToolResult {
        answerPayload = answerPayload == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(answerPayload));
        debugPayload = debugPayload == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(debugPayload));
        confirmedFacts = confirmedFacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(confirmedFacts));
    }

    /**
     * 生成供回答 Prompt 消费的统一回答视图。
     */
    public Map<String, Object> promptPayload() {
        MemoryProjection memoryProjection = memoryProjection();
        AgentToolOutputDTO output = toToolOutput("tool_result", null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", memoryProjection.summary());
        payload.put("answer", output.answer());
        payload.put("facts", memoryProjection.facts());
        return Collections.unmodifiableMap(payload);
    }

    /**
     * 生成供单次 tool 输出写回 memory 的统一摘要视图。
     */
    public MemoryProjection memoryProjection() {
        return memoryProjection(FACT_COUNT_LIMIT);
    }

    /**
     * 生成供 memory 写回使用的统一摘要视图，并允许调用方指定事实数量上限。
     */
    public MemoryProjection memoryProjection(int factLimit) {
        // .1 复用与 prompt / trace 相同的 summary 裁剪和单条 fact 长度规则。
        NormalizedValue normalizedSummary = normalizeText(summary, SUMMARY_LIMIT);
        NormalizedFacts normalizedFacts = normalizeFacts(confirmedFacts, factLimit);
        // .2 只暴露跨轮 memory 真正需要的字段，避免 answer / debug 回灌污染上下文。
        return new MemoryProjection(normalizedSummary.stringValue(), normalizedFacts.facts());
    }

    /**
     * 对已有 memory 快照执行统一归一化，默认沿用单次 tool 的 fact 上限。
     */
    public static MemoryProjection memoryProjection(String summary, List<String> facts) {
        return new AgentToolResult(summary, Map.of(), Map.of(), facts).memoryProjection();
    }

    /**
     * 对已有 memory 快照执行统一归一化，并允许按调用方的总量上限保留历史 facts。
     */
    public static MemoryProjection memoryProjection(String summary, List<String> facts, int factLimit) {
        return new AgentToolResult(summary, Map.of(), Map.of(), facts).memoryProjection(factLimit);
    }

    /**
     * 把 Tool 原始结果归一化为统一结构化视图。
     */
    public AgentToolOutputDTO toToolOutput(String kind, String reply) {
        MemoryProjection memoryProjection = memoryProjection();
        // .1 先分别对回答层和调试层做裁剪与结构化归一化。
        NormalizedValue normalizedAnswer = normalizeNode(
            answerPayload,
            new OutputLimit(ANSWER_TEXT_LIMIT, ANSWER_COLLECTION_LIMIT),
            0
        );
        NormalizedValue normalizedDebug = normalizeNode(
            debugPayload,
            new OutputLimit(DEBUG_TEXT_LIMIT, DEBUG_COLLECTION_LIMIT),
            0
        );
        // .2 再把统一后的 summary / answer / debug / facts 收口到单一 DTO。
        return new AgentToolOutputDTO(
            blankToEmpty(kind),
            memoryProjection.summary(),
            blankToEmpty(reply),
            normalizedAnswer.mapValue(),
            normalizedDebug.mapValue(),
            memoryProjection.facts(),
            new AgentToolOutputNormalizationDTO(
                normalizeText(summary, SUMMARY_LIMIT).truncated(),
                normalizedAnswer.truncated(),
                normalizedDebug.truncated(),
                normalizeFacts(confirmedFacts, FACT_COUNT_LIMIT).truncated()
            )
        );
    }

    public Map<String, Object> tracePayload() {
        return tracePayload(null);
    }

    /**
     * 生成可直接写入 trace 的统一输出快照。
     */
    public Map<String, Object> tracePayload(String reply) {
        AgentToolOutputDTO output = toToolOutput("tool_result", reply);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", output.kind());
        payload.put("summary", output.summary());
        payload.put("reply", output.reply());
        payload.put("answer", output.answer());
        payload.put("debug", output.debug());
        payload.put("facts", output.facts());
        payload.put("normalization", normalizationPayload(output.normalization()));
        return Collections.unmodifiableMap(payload);
    }

    /**
     * 归一化顶层对象，统一控制层次深度、文本长度与集合大小。
     */
    private NormalizedValue normalizeNode(Object value, OutputLimit limit, int depth) {
        if (value == null) {
            return new NormalizedValue(null, false);
        }
        if (value instanceof String text) {
            return normalizeText(text, limit.textLimit());
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>) {
            return new NormalizedValue(value, false);
        }
        if (value.getClass().isArray()) {
            return normalizeCollection(arrayToList(value), limit, depth);
        }
        if (value instanceof List<?> list) {
            return normalizeCollection(list, limit, depth);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            iterable.forEach(items::add);
            return normalizeCollection(items, limit, depth);
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map, limit, depth);
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> recordFields = extractRecordFields(value);
            if (recordFields != null) {
                return normalizeMap(recordFields, limit, depth);
            }
        }
        Map<String, Object> beanProperties = extractBeanProperties(value);
        if (beanProperties != null) {
            return normalizeMap(beanProperties, limit, depth);
        }
        return normalizeText(String.valueOf(value), limit.textLimit());
    }

    /**
     * 归一化 Map 结构，保留顺序并限制字段数量。
     */
    private NormalizedValue normalizeMap(Map<?, ?> source, OutputLimit limit, int depth) {
        if (depth >= MAX_DEPTH) {
            return normalizeText(String.valueOf(source), limit.textLimit());
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        boolean truncated = false;
        int count = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (count >= limit.collectionLimit()) {
                truncated = true;
                break;
            }
            NormalizedValue child = normalizeNode(entry.getValue(), limit, depth + 1);
            normalized.put(String.valueOf(entry.getKey()), child.value());
            truncated = truncated || child.truncated();
            count++;
        }
        if (source.size() > limit.collectionLimit()) {
            truncated = true;
        }
        return new NormalizedValue(Collections.unmodifiableMap(normalized), truncated);
    }

    /**
     * 归一化列表结构，限制元素数量并递归处理内容。
     */
    private NormalizedValue normalizeCollection(List<?> source, OutputLimit limit, int depth) {
        if (depth >= MAX_DEPTH) {
            return normalizeText(String.valueOf(source), limit.textLimit());
        }
        List<Object> normalized = new ArrayList<>();
        boolean truncated = false;
        for (int i = 0; i < source.size(); i++) {
            if (i >= limit.collectionLimit()) {
                truncated = true;
                break;
            }
            NormalizedValue child = normalizeNode(source.get(i), limit, depth + 1);
            normalized.add(child.value());
            truncated = truncated || child.truncated();
        }
        if (source.size() > limit.collectionLimit()) {
            truncated = true;
        }
        return new NormalizedValue(Collections.unmodifiableList(normalized), truncated);
    }

    /**
     * 归一化事实列表，默认沿用单次 tool 输出的事实上限。
     */
    private NormalizedFacts normalizeFacts(List<String> facts) {
        return normalizeFacts(facts, FACT_COUNT_LIMIT);
    }

    /**
     * 归一化事实列表，允许调用方控制本次保留的 fact 数量。
     */
    private NormalizedFacts normalizeFacts(List<String> facts, int factLimit) {
        List<String> normalized = new ArrayList<>();
        boolean truncated = false;
        int count = 0;
        for (String fact : facts == null ? List.<String>of() : facts) {
            if (fact == null || fact.isBlank()) {
                continue;
            }
            if (count >= factLimit) {
                truncated = true;
                break;
            }
            NormalizedValue normalizedFact = normalizeText(fact, FACT_TEXT_LIMIT);
            normalized.add(normalizedFact.stringValue());
            truncated = truncated || normalizedFact.truncated();
            count++;
        }
        long nonBlankCount = (facts == null ? List.<String>of() : facts).stream()
            .filter(fact -> fact != null && !fact.isBlank())
            .count();
        if (nonBlankCount > factLimit) {
            truncated = true;
        }
        return new NormalizedFacts(Collections.unmodifiableList(normalized), truncated);
    }

    /**
     * 提取 record 组件，保留声明顺序；失败时回退到字符串路径。
     */
    private Map<String, Object> extractRecordFields(Object value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        try {
            // .1 record 组件顺序就是声明顺序，直接作为结构化输出顺序。
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                component.getAccessor().trySetAccessible();
                fields.put(component.getName(), component.getAccessor().invoke(value));
            }
            return fields;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 提取普通 POJO 的可读属性，避免真实工具输出退化成 toString。
     */
    private Map<String, Object> extractBeanProperties(Object value) {
        if (!supportsBeanIntrospection(value.getClass())) {
            return null;
        }
        try {
            // .1 先按属性名排序，保证不同运行环境下的输出顺序稳定。
            PropertyDescriptor[] descriptors = Introspector
                .getBeanInfo(value.getClass(), Object.class)
                .getPropertyDescriptors();
            Arrays.sort(descriptors, Comparator.comparing(PropertyDescriptor::getName));
            Map<String, Object> properties = new LinkedHashMap<>();
            for (PropertyDescriptor descriptor : descriptors) {
                if (descriptor.getReadMethod() == null) {
                    continue;
                }
                descriptor.getReadMethod().trySetAccessible();
                properties.put(descriptor.getName(), descriptor.getReadMethod().invoke(value));
            }
            return properties.isEmpty() ? null : properties;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 判断当前类型是否适合按 JavaBean 方式展开。
     */
    private boolean supportsBeanIntrospection(Class<?> type) {
        if (type.isRecord()) {
            return false;
        }
        Package typePackage = type.getPackage();
        if (typePackage == null) {
            return true;
        }
        String packageName = typePackage.getName();
        return !packageName.startsWith("java.")
            && !packageName.startsWith("javax.")
            && !packageName.startsWith("sun.");
    }

    /**
     * 统一裁剪文本长度，避免长文本直接污染 Prompt 与 trace。
     */
    private NormalizedValue normalizeText(String value, int limit) {
        String normalized = blankToEmpty(value);
        if (normalized.length() <= limit) {
            return new NormalizedValue(normalized, false);
        }
        return new NormalizedValue(normalized.substring(0, limit) + "...", true);
    }

    /**
     * 把归一化标记转成 trace 可持久化的 JSON 结构。
     */
    private Map<String, Object> normalizationPayload(AgentToolOutputNormalizationDTO normalization) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summaryTruncated", normalization.summaryTruncated());
        payload.put("answerTruncated", normalization.answerTruncated());
        payload.put("debugTruncated", normalization.debugTruncated());
        payload.put("factsTruncated", normalization.factsTruncated());
        return Collections.unmodifiableMap(payload);
    }

    /**
     * 把数组统一转成 List，方便递归复用同一套归一化逻辑。
     */
    private List<Object> arrayToList(Object array) {
        int length = Array.getLength(array);
        List<Object> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            values.add(Array.get(array, i));
        }
        return values;
    }

    /**
     * 把可空文本标准化为空字符串。
     */
    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record OutputLimit(int textLimit, int collectionLimit) {
    }

    /**
     * memory 写回使用的最小归一化视图。
     */
    public record MemoryProjection(String summary, List<String> facts) {

        public MemoryProjection {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    private record NormalizedFacts(List<String> facts, boolean truncated) {
    }

    private record NormalizedValue(Object value, boolean truncated) {
        private String stringValue() {
            return value == null ? "" : value.toString();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> mapValue() {
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        }
    }
}
