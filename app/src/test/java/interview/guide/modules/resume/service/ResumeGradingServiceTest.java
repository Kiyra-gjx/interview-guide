package interview.guide.modules.resume.service;

import interview.guide.common.ai.AiErrorTranslator;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.AiServiceException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ResumeGradingServiceTest {
    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private AiErrorTranslator aiErrorTranslator;
    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    @Mock
    private ResumeDomainClassificationService resumeDomainClassificationService;

    private ResumeGradingService resumeGradingService;

    @BeforeEach
    void setUp() throws IOException {
        when(chatClientBuilder.build()).thenReturn(chatClient);

        resumeGradingService = new ResumeGradingService(
            chatClientBuilder,
            aiErrorTranslator,
            structuredOutputInvoker,
            resumeDomainClassificationService,
            new ByteArrayResource("system".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("user".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    @DisplayName("领域外简历应直接返回兜底结果且不调用评分AI")
    void shouldReturnFallback_whenDomainIsOutOfScope() {
        String resumeText = "任意简历文本";

        when(resumeDomainClassificationService.classify(resumeText))
            .thenReturn(ResumeDomain.OUT_OF_SCOPE);

        ResumeAnalysisResponse response = resumeGradingService.analyzeResume(resumeText);

        assertThat(response.overallScore()).isEqualTo(29);
        assertThat(response.summary()).contains("不属于计算机/技术岗位目标方向");

        verify(resumeDomainClassificationService).classify(resumeText);
        verify(structuredOutputInvoker, never()).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        );
    }

    @Test
    @DisplayName("领域内简历应进入正式评分AI链路")
    void shouldInvokeScoringAi_whenDomainIsInScope() {
        String resumeText = """
            张三
            软件工程专业
            技术栈：Java、Spring Boot、MySQL、Redis、Docker
            后端开发实习，参与接口开发与数据库设计
            项目经历：基于 Spring Boot 的后台管理系统
            """;

        when(structuredOutputInvoker.invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        )).thenThrow(new AiServiceException(
            ErrorCode.RESUME_ANALYSIS_FAILED,
            "mock scoring failure",
            false,
            null
        ));

        assertThatThrownBy(() -> resumeGradingService.analyzeResume(resumeText))
            .isInstanceOf(AiServiceException.class);

        verify(resumeDomainClassificationService).classify(resumeText);
        verify(structuredOutputInvoker, times(1)).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        );
    }

    @Test
    @DisplayName("领域不确定简历应继续进入正式评分AI链路")
    void shouldInvokeScoringAi_whenDomainIsUncertain() {
        String resumeText = "边界简历文本";

        when(resumeDomainClassificationService.classify(resumeText))
            .thenReturn(ResumeDomain.UNCERTAIN);

        when(structuredOutputInvoker.invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        )).thenThrow(new AiServiceException(
            ErrorCode.RESUME_ANALYSIS_FAILED,
            "mock scoring failure",
            false,
            null
        ));

        assertThatThrownBy(() -> resumeGradingService.analyzeResume(resumeText))
            .isInstanceOf(AiServiceException.class);

        verify(resumeDomainClassificationService).classify(resumeText);
        verify(structuredOutputInvoker, times(1)).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        );
    }
}
