package interview.guide.common.exception;

import interview.guide.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.SocketTimeoutException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 AI 业务异常。
     */
    @ExceptionHandler(AiServiceException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleAiServiceException(AiServiceException e) {
        log.warn("AI 业务异常: code={}, retryable={}, message={}",
            e.getCode(), e.isRetryable(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(resolveBusinessStatus(e))
            .body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 处理参数校验异常。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.error(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 处理绑定异常。
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return Result.error(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 处理文件上传大小超限异常。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("文件上传大小超限: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, "文件大小超过限制");
    }

    /**
     * 处理非法参数异常。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    /**
     * 处理 AI 服务网络异常。
     */
    @ExceptionHandler(ResourceAccessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleResourceAccessException(ResourceAccessException e) {
        log.error("AI 服务连接失败: {}", e.getMessage(), e);

        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return Result.error(ErrorCode.AI_SERVICE_TIMEOUT, "AI 服务响应超时，请稍后重试");
        }

        String message = e.getMessage();
        if (message != null && message.contains("handshake")) {
            return Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 服务连接失败，请检查网络后重试");
        }

        return Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 服务暂时不可用，请稍后重试");
    }

    /**
     * 处理 AI 服务调用异常。
     */
    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleRestClientException(RestClientException e) {
        log.error("AI 服务调用失败: {}", e.getMessage(), e);

        String message = e.getMessage();
        if (message != null) {
            if (message.contains("401") || message.contains("Unauthorized") || message.contains("API key is required")) {
                return Result.error(ErrorCode.AI_API_KEY_INVALID, "AI 服务认证失败，请检查 API Key 配置");
            }
            if (message.contains("quota") || message.contains("Quota") || message.contains("billing")) {
                return Result.error(ErrorCode.AI_QUOTA_EXCEEDED, "AI 服务额度不足，请联系管理员处理");
            }
            if (message.contains("429") || message.contains("Too Many Requests")) {
                return Result.error(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "AI 服务调用过于频繁，请稍后重试");
            }
        }

        return Result.error(ErrorCode.AI_SERVICE_ERROR, "AI 服务调用失败，请稍后重试");
    }

    /**
     * 处理其他未知异常。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }

    private HttpStatus resolveBusinessStatus(BusinessException e) {
        return switch (e.getCode()) {
            case 9005 -> HttpStatus.CONFLICT;
            case 9001, 9007, 404 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.OK;
        };
    }
}
