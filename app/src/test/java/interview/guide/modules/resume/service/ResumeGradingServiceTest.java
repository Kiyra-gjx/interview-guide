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


    private ResumeGradingService resumeGradingService;

    @BeforeEach
    void setUp() throws IOException {
        when(chatClientBuilder.build()).thenReturn(chatClient);

        resumeGradingService = new ResumeGradingService(
            chatClientBuilder,
            aiErrorTranslator,
            structuredOutputInvoker,
            new ByteArrayResource("system".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("user".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("domain-system".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("domain-user".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    @DisplayName("明显法学简历应命中领域外兜底且不调用AI")
    void shouldReturnFallback_whenResumeIsObviouslyOutOfScope() {
        String resumeText = """
            张三
            法学专业
            曾在某律所实习，参与合同审查、仲裁材料整理
            熟悉法院诉讼流程与法务合规工作
            """;

        ResumeAnalysisResponse response = resumeGradingService.analyzeResume(resumeText);

        assertThat(response.overallScore()).isEqualTo(29);
        assertThat(response.summary()).contains("不属于计算机/技术岗位目标方向");

        verify(structuredOutputInvoker, never()).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        );
    }

    @Test
    @DisplayName("技术简历应进入AI处理链路")
    void shouldEnterAiPipeline_whenResumeLooksInScope() {
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
            "mock is failure",
            false,
            null
        ));

        assertThatThrownBy(() -> resumeGradingService.analyzeResume(resumeText))
            .isInstanceOf(AiServiceException.class);

        verify(structuredOutputInvoker, times(1)).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        );
    }
}
