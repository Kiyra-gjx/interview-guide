package interview.guide.modules.resume.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.AiServiceException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ResumeDomainClassificationServiceTest {
    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    private ResumeDomainClassificationService service;

    @BeforeEach
    public void setUp() throws IOException {
        when(chatClientBuilder.build()).thenReturn(chatClient);

        service = new ResumeDomainClassificationService(
            chatClientBuilder,
            structuredOutputInvoker,
            new ClassPathResource("prompts/resume-domain-classification-system.st"),
            new ClassPathResource("prompts/resume-domain-classification-user.st")
        );
    }

    @Test
    @DisplayName("明显法学简历应直接判定为领域外且不调用AI")
    void shouldReturnOutOfScopeByRules() {
        String resumeText = """
            张三
            法学专业
            曾在某律所实习，参与合同审查、仲裁材料整理
            熟悉法院诉讼与法务合规工作
            """;

        ResumeDomain domain = service.classify(resumeText);

        assertThat(domain).isEqualTo(ResumeDomain.OUT_OF_SCOPE);
        verify(structuredOutputInvoker, never()).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        );
    }

    @Test
    @DisplayName("明显技术简历应直接判定为领域内且不调用AI")
    void shouldReturnInScopeByRules() {
        String resumeText = """
            张三
            软件工程专业
            技术栈：Java、Spring Boot、MySQL、Redis、Docker
            后端开发实习，参与接口开发与数据库设计
            项目经历：基于 Spring Boot 的后台管理系统
            """;

        ResumeDomain domain = service.classify(resumeText);

        assertThat(domain).isEqualTo(ResumeDomain.IN_SCOPE);
        verify(structuredOutputInvoker, never()).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        );
    }

    @Test
    @DisplayName("边界简历应进入领域识别AI链路")
    void shouldInvokeClassifierAi_whenRulesDoNotMatch() {
        String resumeText = """
            李四
            信息管理相关背景
            做过数据报表、SQL 查询和简单 Python 脚本处理
            有业务分析和流程优化经验
            """;

        when(structuredOutputInvoker.invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        )).thenThrow(new AiServiceException(
            ErrorCode.RESUME_ANALYSIS_FAILED,
            "mock classifier failure",
            false,
            null
        ));

        assertThatThrownBy(() -> service.classify(resumeText))
            .isInstanceOf(AiServiceException.class);

        verify(structuredOutputInvoker, times(1)).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()
        );
    }
}
