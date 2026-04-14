package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.AiServiceException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 简历分析 Stream 消费者。
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

    private final ResumeGradingService gradingService;
    private final ResumePersistenceService persistenceService;
    private final ResumeRepository resumeRepository;
    private final ConcurrentMap<Long, AnalyzeFailureState> failureStates = new ConcurrentHashMap<>();

    public AnalyzeStreamConsumer(
        RedisService redisService,
        ResumeGradingService gradingService,
        ResumePersistenceService persistenceService,
        ResumeRepository resumeRepository
    ) {
        super(redisService);
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
    }

    record AnalyzePayload(Long resumeId, String content) {
    }

    private record AnalyzeFailureState(
        String errorMessage,
        String errorCode,
        Boolean retryable
    ) {
    }

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "analyze-consumer";
    }

    @Override
    protected AnalyzePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (resumeIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new AnalyzePayload(Long.parseLong(resumeIdStr), content);
    }

    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void markProcessing(AnalyzePayload payload) {
        failureStates.remove(payload.resumeId());
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();
        if (!resumeRepository.existsById(resumeId)) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId);
            return;
        }

        try {
            ResumeAnalysisResponse analysis = gradingService.analyzeResume(payload.content());
            ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
            if (resume == null) {
                log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId);
                return;
            }
            persistenceService.saveAnalysis(resume, analysis);
        } catch (AiServiceException e) {
            failureStates.put(resumeId, buildFailureState(e));
            throw disableAutomaticRetryForStructuredOutputError(e);
        } catch (Exception e) {
            failureStates.put(resumeId, buildFailureState(e));
            throw e;
        }
    }

    @Override
    protected void markCompleted(AnalyzePayload payload) {
        failureStates.remove(payload.resumeId());
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        AnalyzeFailureState failureState = failureStates.remove(payload.resumeId());
        if (failureState == null) {
            failureState = new AnalyzeFailureState(
                "简历分析失败，请稍后重试",
                ErrorCode.RESUME_ANALYSIS_FAILED.name(),
                true
            );
        }
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, failureState);
    }

    @Override
    protected void retryMessage(AnalyzePayload payload, int retryCount) {
        Long resumeId = payload.resumeId();
        String content = payload.content();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, content,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount);
        } catch (Exception e) {
            log.error("重试入队失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
            updateAnalyzeStatus(
                resumeId,
                AsyncTaskStatus.FAILED,
                new AnalyzeFailureState(
                    "分析任务重试失败，请稍后重新发起分析",
                    ErrorCode.RESUME_ANALYSIS_FAILED.name(),
                    true
                )
            );
        }
    }

    private AnalyzeFailureState buildFailureState(Exception e) {
        if (e instanceof AiServiceException aiServiceException) {
            return new AnalyzeFailureState(
                aiServiceException.getMessage(),
                aiServiceException.getErrorCode().name(),
                aiServiceException.isRetryable()
            );
        }
        return new AnalyzeFailureState(
            "简历分析失败，请稍后重试",
            ErrorCode.RESUME_ANALYSIS_FAILED.name(),
            true
        );
    }

    private AiServiceException disableAutomaticRetryForStructuredOutputError(AiServiceException e) {
        if (e.getErrorCode() != ErrorCode.AI_RESPONSE_FORMAT_INVALID || !e.isRetryable()) {
            return e;
        }

        // 结构化输出失败已在单次调用内做过重试，这里停止 Stream 自动重入队，
        // 但保留 failureState 中的 retryable=true，允许用户手动重新发起分析。
        return new AiServiceException(
            e.getErrorCode(),
            e.getMessage(),
            false,
            e
        );
    }

    /**
     * 更新分析状态。
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, AnalyzeFailureState failureState) {
        try {
            resumeRepository.findById(resumeId).ifPresent(resume -> {
                resume.setAnalyzeStatus(status);
                if (failureState == null) {
                    resume.setAnalyzeError(null);
                    resume.setAnalyzeErrorCode(null);
                    resume.setAnalyzeRetryable(null);
                } else {
                    resume.setAnalyzeError(failureState.errorMessage());
                    resume.setAnalyzeErrorCode(failureState.errorCode());
                    resume.setAnalyzeRetryable(failureState.retryable());
                }
                resumeRepository.save(resume);
                log.debug("分析状态已更新: resumeId={}, status={}", resumeId, status);
            });
        } catch (Exception e) {
            log.error("更新分析状态失败: resumeId={}, status={}, error={}", resumeId, status, e.getMessage(), e);
        }
    }
}
