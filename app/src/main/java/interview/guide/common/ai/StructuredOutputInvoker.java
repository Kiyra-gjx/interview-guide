package interview.guide.common.ai;

import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一封装结构化输出调用，包含重试与轻量 JSON 修复兜底。
 */
@Component
public class StructuredOutputInvoker {

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被严格 JSON 解析器直接解析的 JSON 对象。规则：
1. 不要输出 Markdown 代码块。
2. 不要输出任何解释文字、前后缀或注释。
3. 字符串中的引号必须正确转义。
4. 字符串中不要出现字面换行，必须使用 \\n。
""";

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;

    public StructuredOutputInvoker(
        @Value("${app.ai.structured-max-attempts:2}") int maxAttempts,
        @Value("${app.ai.structured-include-last-error:true}") boolean includeLastErrorInRetryPrompt
    ) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.includeLastErrorInRetryPrompt = includeLastErrorInRetryPrompt;
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
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptSystemPrompt = buildAttemptSystemPrompt(systemPromptWithFormat, attempt, lastError);
            try {
                String rawContent = chatClient.prompt()
                    .system(attemptSystemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
                return parseWithRepair(outputConverter, rawContent, logContext, log);
            } catch (StructuredOutputException e) {
                throw e;
            } catch (Exception e) {
                if (!shouldRetry(e, attempt)) {
                    throw e;
                }
                lastError = e;
                log.warn("{}结构化解析失败，准备重试: attempt={}, error={}",
                    logContext, attempt, e.getMessage());
            }
        }

        throw new StructuredOutputException(buildFailureMessage(errorPrefix, logContext), lastError);
    }

    String buildAttemptSystemPrompt(String systemPromptWithFormat, int attempt, Exception lastError) {
        if (attempt == 1) {
            return systemPromptWithFormat + "\n\n" + STRICT_JSON_INSTRUCTION;
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

    private boolean shouldRetry(Exception e, int attempt) {
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
        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n")
            .append(STRICT_JSON_INSTRUCTION)
            .append("\n上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次解析错误：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > 200) {
            return oneLine.substring(0, 200) + "...";
        }
        return oneLine;
    }

    private <T> T parseWithRepair(
        BeanOutputConverter<T> outputConverter,
        String rawContent,
        String logContext,
        Logger log
    ) {
        try {
            return outputConverter.convert(rawContent);
        } catch (Exception originalError) {
            String repaired = repairJson(rawContent);
            if (repaired == null || repaired.equals(rawContent)) {
                throw originalError;
            }

            try {
                T value = outputConverter.convert(repaired);
                log.info("{}结构化输出兜底修复后解析成功", logContext);
                return value;
            } catch (Exception repairedError) {
                originalError.addSuppressed(repairedError);
                throw originalError;
            }
        }
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
}
