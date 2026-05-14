package interview.guide.common.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@EnableConfigurationProperties(StructuredOutputProperties.class)
public class StructuredOutputInvoker {

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被严格 JSON 解析器直接解析的 JSON 对象。规则：
1. 不要输出 Markdown 代码块。
2. 不要输出任何解释文字、前后缀或注释。
3. 字符串中的引号必须正确转义。
4. 字符串中不要出现字面换行，必须使用 \\n。
""";

    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";

    private final StructuredOutputProperties properties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> attemptCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> invocationCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> latencyTimers = new ConcurrentHashMap<>();

    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptSystemPrompt = buildAttemptSystemPrompt(systemPromptWithFormat, attempt, lastError);
            try {
                String rawContent = chatClient.prompt()
                    .system(attemptSystemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
                T result = parseWithRepair(outputConverter, rawContent, logContext, log);
                recordAttempt(contextTag, "success");
                recordInvocation(contextTag, "success", startNanos);
                return result;
            } catch (StructuredOutputException e) {
                recordAttempt(contextTag, "failure");
                recordInvocation(contextTag, "failure", startNanos);
                throw e;
            } catch (Exception e) {
                recordAttempt(contextTag, "failure");
                if (!shouldRetry(e, attempt, maxAttempts)) {
                    recordInvocation(contextTag, "failure", startNanos);
                    throw e;
                }
                lastError = e;
                log.warn("{}结构化解析失败，准备重试: attempt={}, error={}",
                    logContext, attempt, e.getMessage());
            }
        }

        recordInvocation(contextTag, "failure", startNanos);
        throw new StructuredOutputException(buildFailureMessage(errorPrefix, logContext), lastError);
    }

    String buildAttemptSystemPrompt(String systemPromptWithFormat, int attempt, Exception lastError) {
        if (attempt == 1) {
            if (properties.isAppendStrictJsonInstruction()) {
                return systemPromptWithFormat + "\n\n" + STRICT_JSON_INSTRUCTION;
            }
            return systemPromptWithFormat;
        }
        return buildRetrySystemPrompt(systemPromptWithFormat, lastError);
    }

    private String buildFailureMessage(String errorPrefix, String logContext) {
        if (logContext != null && !logContext.isBlank()) {
            return logContext + "结构化输出解析失败";
        }
        if (errorPrefix != null && !errorPrefix.isBlank()) {
            return errorPrefix + "结构化输出解析失败";
        }
        return "结构化输出解析失败";
    }

    private boolean shouldRetry(Exception e, int attempt, int maxAttempts) {
        return attempt < maxAttempts && isStructuredOutputError(e);
    }

    private boolean isStructuredOutputError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof StructuredOutputException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("illegal unquoted character")
                    || normalized.contains("cannot deserialize")
                    || normalized.contains("unexpected character")
                    || normalized.contains("unrecognized token")
                    || normalized.contains("json parse")
                    || normalized.contains("jsonmappingexception")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        StringBuilder prompt = new StringBuilder(systemPromptWithFormat);

        if (properties.isAppendStrictJsonInstruction()) {
            prompt.append("\n\n").append(STRICT_JSON_INSTRUCTION);
        }

        if (properties.isRetryUseRepairPrompt()) {
            prompt.append("\n上次输出解析失败，请仅返回合法 JSON。");
        }

        if (properties.isIncludeLastError() && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次解析错误：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        int maxLength = properties.getErrorMessageMaxLength();
        if (oneLine.length() > maxLength) {
            return oneLine.substring(0, maxLength) + "...";
        }
        return oneLine;
    }

    private <T> T parseWithRepair(
        BeanOutputConverter<T> outputConverter,
        String rawContent,
        String logContext,
        Logger log
    ) {
        String cleaned = repairJson(rawContent);
        try {
            return outputConverter.convert(cleaned);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(cleaned);
            if (!repaired.equals(cleaned)) {
                repaired = escapeControlCharsInJsonStrings(repaired);
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已本地修复", logContext);
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    /**
     * 修复 JSON 字符串值内的未转义双引号。
     * 判断逻辑：遇到 " 时向后扫描，如果下一个非空白字符是 , } ] : 则认为是字符串终止符，否则自动补 \。
     */
    String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        StringBuilder out = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);

            if (!inString) {
                out.append(ch);
                if (ch == '"') {
                    inString = true;
                }
                continue;
            }

            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                out.append(ch);
                escaped = true;
                continue;
            }

            if (ch == '"') {
                if (isStringTerminator(content, i)) {
                    out.append(ch);
                    inString = false;
                } else {
                    out.append("\\\"");
                }
                continue;
            }

            out.append(ch);
        }
        return out.toString();
    }

    private boolean isStringTerminator(String content, int quoteIndex) {
        for (int j = quoteIndex + 1; j < content.length(); j++) {
            char next = content.charAt(j);
            if (next == ' ' || next == '\t' || next == '\n' || next == '\r') {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    private String repairJson(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return rawContent;
        }

        String candidate = rawContent.trim();
        if (candidate.startsWith("```")) {
            candidate = stripCodeFence(candidate);
        }

        candidate = extractJsonBody(candidate);
        return escapeControlCharsInJsonStrings(candidate);
    }

    private String stripCodeFence(String text) {
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }

        String body = text.substring(firstNewline + 1);
        int fenceEnd = body.lastIndexOf("```");
        if (fenceEnd >= 0) {
            return body.substring(0, fenceEnd).trim();
        }
        return text;
    }

    private String extractJsonBody(String text) {
        int objStart = text.indexOf('{');
        int objEnd = text.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return text.substring(objStart, objEnd + 1);
        }

        int arrStart = text.indexOf('[');
        int arrEnd = text.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            return text.substring(arrStart, arrEnd + 1);
        }
        return text;
    }

    private String escapeControlCharsInJsonStrings(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!inString) {
                out.append(ch);
                if (ch == '"') {
                    inString = true;
                }
                continue;
            }

            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                out.append(ch);
                escaped = true;
                continue;
            }

            if (ch == '"') {
                out.append(ch);
                inString = false;
                continue;
            }

            if (ch == '\n') {
                out.append("\\n");
                continue;
            }
            if (ch == '\r') {
                out.append("\\r");
                continue;
            }
            if (ch == '\t') {
                out.append("\\t");
                continue;
            }

            if (ch < 0x20) {
                out.append(String.format("\\u%04x", (int) ch));
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private String normalizeContextTag(String logContext) {
        if (logContext == null || logContext.isBlank()) {
            return "unknown";
        }
        return logContext.trim().toLowerCase().replaceAll("\\s+", "_");
    }

    private void recordAttempt(String contextTag, String status) {
        if (meterRegistry == null || !properties.isMetricsEnabled()) {
            return;
        }
        String key = METRIC_ATTEMPTS + "." + contextTag + "." + status;
        attemptCounters.computeIfAbsent(key, k ->
            Counter.builder(METRIC_ATTEMPTS)
                .tag("context", contextTag)
                .tag("status", status)
                .register(meterRegistry)
        ).increment();
    }

    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (meterRegistry == null || !properties.isMetricsEnabled()) {
            return;
        }
        String key = METRIC_INVOCATIONS + "." + contextTag + "." + status;
        invocationCounters.computeIfAbsent(key, k ->
            Counter.builder(METRIC_INVOCATIONS)
                .tag("context", contextTag)
                .tag("status", status)
                .register(meterRegistry)
        ).increment();

        String timerKey = METRIC_LATENCY + "." + contextTag + "." + status;
        latencyTimers.computeIfAbsent(timerKey, k ->
            Timer.builder(METRIC_LATENCY)
                .tag("context", contextTag)
                .tag("status", status)
                .register(meterRegistry)
        ).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
