package interview.guide.modules.resume.listener;

import interview.guide.common.exception.AiServiceException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeStreamConsumerTest {

    @Mock
    private RedisService redisService;
    @Mock
    private ResumeGradingService gradingService;
    @Mock
    private ResumePersistenceService persistenceService;
    @Mock
    private ResumeRepository resumeRepository;

    private AnalyzeStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AnalyzeStreamConsumer(
            redisService,
            gradingService,
            persistenceService,
            resumeRepository
        );
    }

    @Test
    @DisplayName("结构化输出失败应停止自动重试但保留手动重试能力")
    void shouldDisableAutomaticRetryButKeepManualRetryForStructuredOutputFailure() {
        Long resumeId = 1L;
        String content = "resume text";
        AnalyzeStreamConsumer.AnalyzePayload payload = new AnalyzeStreamConsumer.AnalyzePayload(resumeId, content);

        ResumeEntity resume = new ResumeEntity();
        resume.setId(resumeId);

        AiServiceException formatError = new AiServiceException(
            ErrorCode.AI_RESPONSE_FORMAT_INVALID,
            "AI 返回结果格式异常，请稍后重试",
            true,
            null
        );

        when(resumeRepository.existsById(resumeId)).thenReturn(true);
        when(resumeRepository.findById(resumeId)).thenReturn(Optional.of(resume));
        when(gradingService.analyzeResume(content)).thenThrow(formatError);

        assertThatThrownBy(() -> consumer.processBusiness(payload))
            .isInstanceOf(AiServiceException.class)
            .satisfies(ex -> {
                AiServiceException aiEx = (AiServiceException) ex;
                assertThat(aiEx.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_FORMAT_INVALID);
                assertThat(aiEx.isRetryable()).isFalse();
            });

        consumer.markFailed(payload, "ignored");

        verify(persistenceService, never()).saveAnalysis(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(resumeRepository).save(argThat(saved ->
            saved.getAnalyzeStatus() == AsyncTaskStatus.FAILED
                && ErrorCode.AI_RESPONSE_FORMAT_INVALID.name().equals(saved.getAnalyzeErrorCode())
                && Boolean.TRUE.equals(saved.getAnalyzeRetryable())
        ));
    }
}
