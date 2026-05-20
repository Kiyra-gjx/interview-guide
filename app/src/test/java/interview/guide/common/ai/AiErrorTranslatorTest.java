package interview.guide.common.ai;

import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiErrorTranslatorTest {

    private final AiErrorTranslator translator = new AiErrorTranslator();

    @Test
    @DisplayName("StructuredOutputException 应映射为 AI_RESPONSE_FORMAT_INVALID")
    void shouldTranslateStructuredOutputExceptionAsResponseFormatInvalid() {
        AiErrorDescriptor descriptor = translator.translate(
            new StructuredOutputException("测试结构化输出解析失败", new RuntimeException("cannot deserialize"))
        );

        assertThat(descriptor.errorCode()).isEqualTo(ErrorCode.AI_RESPONSE_FORMAT_INVALID);
        assertThat(descriptor.retryable()).isTrue();
    }

    @Test
    @DisplayName("cause chain 中的 StructuredOutputException 应映射为 AI_RESPONSE_FORMAT_INVALID")
    void shouldTranslateNestedStructuredOutputExceptionAsResponseFormatInvalid() {
        AiErrorDescriptor descriptor = translator.translate(
            new RuntimeException(
                "outer wrapper",
                new StructuredOutputException("测试结构化输出解析失败", new RuntimeException("bad json"))
            )
        );

        assertThat(descriptor.errorCode()).isEqualTo(ErrorCode.AI_RESPONSE_FORMAT_INVALID);
        assertThat(descriptor.retryable()).isTrue();
    }
}
