package interview.guide.common.ai;

import interview.guide.common.exception.ErrorCode;

/**
 * AI 错误翻译结果。
 */
public record AiErrorDescriptor(
    ErrorCode errorCode,
    String userMessage,
    boolean retryable
) {
}