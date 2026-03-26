package interview.guide.common.exception;

/**
 * AI 服务异常，保存可重试信息供异步任务链路使用。
 */
public class AiServiceException extends BusinessException {

    private final boolean retryable;

    public AiServiceException(ErrorCode errorCode, String message, boolean retryable, Throwable cause) {
        super(errorCode, message);
        this.retryable = retryable;
        if (cause != null) {
            initCause(cause);
        }
    }

    public boolean isRetryable() {
        return retryable;
    }
}