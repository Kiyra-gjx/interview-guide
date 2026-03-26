package interview.guide.common.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Set;

/**
 * 将 AI 调用异常翻译为用户可理解的错误信息。
 */
@Component
public class AiErrorTranslator {

    private static final Set<String> API_KEY_KEYWORDS = Set.of(
        "api key is required",
        "invalid api key",
        "incorrect api key",
        "unauthorized",
        "authentication failed",
        "invalid authentication",
        "token is invalid"
    );

    private static final Set<String> QUOTA_KEYWORDS = Set.of(
        "insufficient_quota",
        "quota exceeded",
        "billing",
        "余额不足",
        "额度不足"
    );

    private static final Set<String> RATE_LIMIT_KEYWORDS = Set.of(
        "too many requests",
        "rate limit",
        "rate_limit",
        "429"
    );

    private static final Set<String> NETWORK_KEYWORDS = Set.of(
        "connection refused",
        "connection reset",
        "connect timed out",
        "i/o error",
        "handshake",
        "no route to host",
        "network is unreachable",
        "read timed out"
    );

    private static final Set<String> STRUCTURED_OUTPUT_KEYWORDS = Set.of(
        "illegal unquoted character",
        "cannot deserialize",
        "unexpected character",
        "unrecognized token",
        "json parse",
        "jsonmappingexception"
    );

    /**
     * 翻译异常。
     */
    public AiErrorDescriptor translate(Throwable throwable) {
        Throwable rootCause = getRootCause(throwable);
        String normalizedMessage = buildNormalizedMessage(throwable, rootCause);

        if (containsAny(normalizedMessage, API_KEY_KEYWORDS)) {
            return new AiErrorDescriptor(
                ErrorCode.AI_API_KEY_INVALID,
                "AI 服务认证失败，请检查 API Key 配置",
                false
            );
        }

        if (containsAny(normalizedMessage, QUOTA_KEYWORDS)) {
            return new AiErrorDescriptor(
                ErrorCode.AI_QUOTA_EXCEEDED,
                "AI 服务额度不足，请联系管理员处理",
                false
            );
        }

        if (containsAny(normalizedMessage, RATE_LIMIT_KEYWORDS)) {
            return new AiErrorDescriptor(
                ErrorCode.AI_RATE_LIMIT_EXCEEDED,
                "AI 服务调用过于频繁，请稍后重试",
                true
            );
        }

        if (rootCause instanceof SocketTimeoutException || normalizedMessage.contains("timeout")) {
            return new AiErrorDescriptor(
                ErrorCode.AI_SERVICE_TIMEOUT,
                "AI 服务响应超时，请稍后重试",
                true
            );
        }

        if (isStructuredOutputError(throwable, rootCause, normalizedMessage)) {
            return new AiErrorDescriptor(
                ErrorCode.AI_RESPONSE_FORMAT_INVALID,
                "AI 返回结果格式异常，请稍后重试",
                true
            );
        }

        if (containsAny(normalizedMessage, NETWORK_KEYWORDS)) {
            return new AiErrorDescriptor(
                ErrorCode.AI_SERVICE_UNAVAILABLE,
                "AI 服务暂时不可用，请稍后重试",
                true
            );
        }

        return new AiErrorDescriptor(
            ErrorCode.AI_SERVICE_ERROR,
            "AI 服务调用失败，请稍后重试",
            true
        );
    }

    private boolean isStructuredOutputError(Throwable throwable, Throwable rootCause, String normalizedMessage) {
        return throwable instanceof StructuredOutputException
            || rootCause instanceof JsonProcessingException
            || containsAny(normalizedMessage, STRUCTURED_OUTPUT_KEYWORDS);
    }

    private String buildNormalizedMessage(Throwable throwable, Throwable rootCause) {
        StringBuilder builder = new StringBuilder();
        appendMessage(builder, throwable);
        if (rootCause != throwable) {
            appendMessage(builder, rootCause);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private void appendMessage(StringBuilder builder, Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(throwable.getMessage());
    }

    private boolean containsAny(String content, Set<String> keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}