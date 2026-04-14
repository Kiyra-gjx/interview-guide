package interview.guide.common.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredOutputInvokerTest {

    private static final String FORMAT_PROMPT = "system prompt\nformat section";

    @Test
    @DisplayName("首轮提示词也应包含严格 JSON 约束")
    void shouldIncludeStrictJsonInstructionOnFirstAttempt() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(2, true);

        String prompt = invoker.buildAttemptSystemPrompt(FORMAT_PROMPT, 1, null);

        assertThat(prompt)
            .startsWith(FORMAT_PROMPT)
            .contains("请仅返回可被严格 JSON 解析器直接解析的 JSON 对象")
            .doesNotContain("上次输出解析失败")
            .doesNotContain("上次解析错误");
    }

    @Test
    @DisplayName("重试提示词应追加失败上下文和上次错误")
    void shouldIncludeRetryContextAndLastErrorOnRetryAttempt() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(2, true);

        String prompt = invoker.buildAttemptSystemPrompt(
            FORMAT_PROMPT,
            2,
            new RuntimeException("Unexpected close marker '}'")
        );

        assertThat(prompt)
            .startsWith(FORMAT_PROMPT)
            .contains("请仅返回可被严格 JSON 解析器直接解析的 JSON 对象")
            .contains("上次输出解析失败")
            .contains("上次解析错误")
            .contains("Unexpected close marker '}'");
    }
}
