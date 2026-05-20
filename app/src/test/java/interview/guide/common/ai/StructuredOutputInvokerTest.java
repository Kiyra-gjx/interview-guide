package interview.guide.common.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.net.ConnectException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredOutputInvokerTest {

    private static final String FORMAT_PROMPT = "system prompt\nformat section";

    private StructuredOutputProperties defaultProps() {
        StructuredOutputProperties props = new StructuredOutputProperties();
        props.setMaxAttempts(2);
        props.setIncludeLastError(true);
        props.setRetryUseRepairPrompt(true);
        props.setRetryAppendStrictJsonInstruction(true);
        props.setErrorMessageMaxLength(200);
        props.setMetricsEnabled(false);
        return props;
    }

    private StructuredOutputInvoker createInvoker() {
        return new StructuredOutputInvoker(defaultProps(), null);
    }

    private ChatClient mockChatClient(String rawResponse) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(rawResponse);
        return chatClient;
    }

    private ChatClient mockChatClient(String firstRawResponse, String secondRawResponse) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(firstRawResponse, secondRawResponse);
        return chatClient;
    }

    private StructuredOutputProperties bindProperties(Map<String, Object> source) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", source));
        return new Binder(ConfigurationPropertySources.from(environment.getPropertySources()))
            .bind("app.ai.structured-output", StructuredOutputProperties.class)
            .orElseGet(StructuredOutputProperties::new);
    }

    @Test
    @DisplayName("首轮提示词也应包含严格 JSON 约束")
    void shouldIncludeStrictJsonInstructionOnFirstAttempt() {
        StructuredOutputInvoker invoker = createInvoker();

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
        StructuredOutputInvoker invoker = createInvoker();

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

    @Test
    @DisplayName("禁用 strict JSON 指令时首轮不追加")
    void shouldNotAppendStrictJsonWhenDisabled() {
        StructuredOutputProperties props = defaultProps();
        props.setRetryAppendStrictJsonInstruction(false);
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, null);

        String prompt = invoker.buildAttemptSystemPrompt(FORMAT_PROMPT, 1, null);

        assertThat(prompt).isEqualTo(FORMAT_PROMPT);
    }

    @Test
    @DisplayName("新 strict JSON 配置键优先于兼容旧键")
    @SuppressWarnings("deprecation")
    void shouldPreferRetryAppendStrictJsonInstructionOverLegacyKey() {
        StructuredOutputProperties props = defaultProps();
        props.setAppendStrictJsonInstruction(true);
        props.setRetryAppendStrictJsonInstruction(false);
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, null);

        assertThat(invoker.buildAttemptSystemPrompt(FORMAT_PROMPT, 1, null))
            .isEqualTo(FORMAT_PROMPT);
    }

    @Test
    @DisplayName("未配置新 strict JSON 键时应兼容旧键")
    @SuppressWarnings("deprecation")
    void shouldFallbackToLegacyAppendStrictJsonInstruction() {
        StructuredOutputProperties props = defaultProps();
        props.setRetryAppendStrictJsonInstruction(null);
        props.setAppendStrictJsonInstruction(false);
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, null);

        assertThat(invoker.buildAttemptSystemPrompt(FORMAT_PROMPT, 1, null))
            .isEqualTo(FORMAT_PROMPT);
    }

    @Test
    @DisplayName("真实配置绑定下仅旧 strict JSON 键应仍可关闭指令")
    void shouldBindLegacyAppendStrictJsonInstructionWhenNewKeyIsAbsent() {
        StructuredOutputProperties props = bindProperties(Map.of(
            "app.ai.structured-output.append-strict-json-instruction", "false"
        ));

        assertThat(props.shouldAppendStrictJsonInstruction()).isFalse();
    }

    @Test
    @DisplayName("真实配置绑定下新 strict JSON 键应优先于旧键")
    void shouldBindRetryAppendStrictJsonInstructionWithPriorityOverLegacyKey() {
        StructuredOutputProperties props = bindProperties(Map.of(
            "app.ai.structured-output.append-strict-json-instruction", "false",
            "app.ai.structured-output.retry-append-strict-json-instruction", "true"
        ));

        assertThat(props.shouldAppendStrictJsonInstruction()).isTrue();
    }

    @Nested
    @DisplayName("JSON 引号修复")
    class QuoteRepairTest {

        private final StructuredOutputInvoker invoker = createInvoker();

        @Test
        @DisplayName("正常 JSON 不做修改")
        void shouldNotModifyValidJson() {
            String valid = "{\"name\": \"hello world\", \"age\": 30}";
            assertThat(invoker.repairUnescapedQuotesInJsonStrings(valid)).isEqualTo(valid);
        }

        @Test
        @DisplayName("修复字符串内未转义的引号")
        void shouldEscapeUnquotedQuotesInsideStrings() {
            String broken = "{\"desc\": \"He said \"hello\" to me\"}";
            String repaired = invoker.repairUnescapedQuotesInJsonStrings(broken);
            assertThat(repaired).isEqualTo("{\"desc\": \"He said \\\"hello\\\" to me\"}");
        }

        @Test
        @DisplayName("已转义的引号不重复转义")
        void shouldNotDoubleEscapeAlreadyEscapedQuotes() {
            String valid = "{\"desc\": \"He said \\\"hello\\\" to me\"}";
            assertThat(invoker.repairUnescapedQuotesInJsonStrings(valid)).isEqualTo(valid);
        }

        @Test
        @DisplayName("多字段含未转义引号 — 结果应为合法 JSON 结构")
        void shouldRepairMultipleFields() {
            // "val"ue" 中间的引号后面跟的是 u 不是结构字符，应被转义
            String broken = "{\"a\": \"val\"ue\", \"b\": \"ok\"}";
            String repaired = invoker.repairUnescapedQuotesInJsonStrings(broken);
            assertThat(repaired).isEqualTo("{\"a\": \"val\\\"ue\", \"b\": \"ok\"}");
        }

        @Test
        @DisplayName("null 和空字符串直接返回")
        void shouldHandleNullAndEmpty() {
            assertThat(invoker.repairUnescapedQuotesInJsonStrings(null)).isNull();
            assertThat(invoker.repairUnescapedQuotesInJsonStrings("")).isEmpty();
        }

        @Test
        @DisplayName("嵌套对象中的未转义引号")
        void shouldRepairNestedObjectQuotes() {
            String broken = "{\"outer\": {\"inner\": \"say \"hi\"\"}}";
            String repaired = invoker.repairUnescapedQuotesInJsonStrings(broken);
            assertThat(repaired).contains("\\\"hi\\\"");
            assertThat(repaired).endsWith("}}");
        }

        @Test
        @DisplayName("Markdown fence 包裹 JSON 时应通过 invoke 路径清理后解析")
        void shouldParseMarkdownFenceJsonThroughInvoke() {
            ChatClient chatClient = mockChatClient("```json\n{\"value\": \"ok\"}\n```");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            when(converter.convert("{\"value\": \"ok\"}")).thenReturn("ok");

            String result = invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"));

            assertThat(result).isEqualTo("ok");
            verify(converter).convert("{\"value\": \"ok\"}");
        }

        @Test
        @DisplayName("未转义引号应通过 invoke 路径本地修复后解析")
        void shouldRepairUnescapedQuotesThroughInvoke() {
            ChatClient chatClient = mockChatClient("{\"value\": \"He said \"hello\" to me\"}");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            when(converter.convert(anyString())).thenAnswer(invocation -> {
                String content = invocation.getArgument(0);
                if ("{\"value\": \"He said \\\"hello\\\" to me\"}".equals(content)) {
                    return "ok";
                }
                throw new RuntimeException("cannot deserialize");
            });

            String result = invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"));

            assertThat(result).isEqualTo("ok");
            verify(converter).convert("{\"value\": \"He said \\\"hello\\\" to me\"}");
        }

        @Test
        @DisplayName("字符串内控制字符应通过 invoke 路径转义后解析")
        void shouldEscapeControlCharsThroughInvoke() {
            ChatClient chatClient = mockChatClient("{\"value\": \"line1\nline2\"}");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            when(converter.convert("{\"value\": \"line1\\nline2\"}")).thenReturn("ok");

            String result = invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"));

            assertThat(result).isEqualTo("ok");
            verify(converter).convert("{\"value\": \"line1\\nline2\"}");
        }
    }

    @Nested
    @DisplayName("Metrics 埋点")
    class MetricsTest {

        @Test
        @DisplayName("metrics 启用时应记录 attempt counter")
        void shouldRecordAttemptMetrics() {
            StructuredOutputProperties props = defaultProps();
            props.setMetricsEnabled(true);
            props.setMaxAttempts(1);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, registry);

            ChatClient chatClient = mockChatClient("not valid json");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            when(converter.convert(anyString())).thenThrow(new RuntimeException("cannot deserialize"));

            assertThatThrownBy(() ->
                invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                    null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"))
            ).isInstanceOf(Exception.class);

            assertThat(registry.find("app.ai.structured_output.attempts")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));

            assertThat(registry.find("app.ai.structured_output.invocations")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));

            assertThat(registry.find("app.ai.structured_output.latency")
                .tag("status", "failure").timer())
                .isNotNull();
        }

        @Test
        @DisplayName("metrics 禁用时不注册任何 metric")
        void shouldNotRegisterMetricsWhenDisabled() {
            StructuredOutputProperties props = defaultProps();
            props.setMetricsEnabled(false);
            props.setMaxAttempts(1);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, registry);

            ChatClient chatClient = mockChatClient("not valid json");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            when(converter.convert(anyString())).thenThrow(new RuntimeException("cannot deserialize"));

            try {
                invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                    null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"));
            } catch (Exception ignored) {
            }

            assertThat(registry.getMeters()).isEmpty();
        }

        @Test
        @DisplayName("解析成功时记录 success metrics")
        void shouldRecordSuccessMetrics() {
            StructuredOutputProperties props = defaultProps();
            props.setMetricsEnabled(true);
            props.setMaxAttempts(1);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, registry);

            ChatClient chatClient = mockChatClient("{\"value\": \"ok\"}");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            when(converter.convert(anyString())).thenReturn("ok");

            String result = invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"));

            assertThat(result).isEqualTo("ok");
            assertThat(registry.find("app.ai.structured_output.attempts")
                .tag("status", "success").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
            assertThat(registry.find("app.ai.structured_output.invocations")
                .tag("status", "success").counter())
                .isNotNull();
        }

        @Test
        @DisplayName("结构化解析失败后重试成功应记录一次失败 attempt 和一次成功 invocation")
        void shouldRecordRetryThenSuccessMetrics() {
            StructuredOutputProperties props = defaultProps();
            props.setMetricsEnabled(true);
            props.setMaxAttempts(2);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, registry);

            ChatClient chatClient = mockChatClient("broken", "{\"value\": \"ok\"}");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            when(converter.convert(anyString())).thenAnswer(invocation -> {
                String content = invocation.getArgument(0);
                if ("{\"value\": \"ok\"}".equals(content)) {
                    return "ok";
                }
                throw new RuntimeException("cannot deserialize");
            });

            String result = invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"));

            assertThat(result).isEqualTo("ok");
            assertThat(registry.find("app.ai.structured_output.attempts")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
            assertThat(registry.find("app.ai.structured_output.attempts")
                .tag("status", "success").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
            assertThat(registry.find("app.ai.structured_output.invocations")
                .tag("status", "success").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
            assertThat(registry.find("app.ai.structured_output.invocations")
                .tag("status", "failure").counter())
                .isNull();
        }

        @Test
        @DisplayName("重试耗尽后应记录多次失败 attempt 但只记录一次失败 invocation")
        void shouldRecordOneFailureInvocationAfterRetryExhaustion() {
            StructuredOutputProperties props = defaultProps();
            props.setMetricsEnabled(true);
            props.setMaxAttempts(2);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, registry);

            ChatClient chatClient = mockChatClient("broken", "still broken");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            RuntimeException parseError = new RuntimeException("cannot deserialize");
            when(converter.convert(anyString())).thenThrow(parseError);

            assertThatThrownBy(() ->
                invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                    null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"))
            )
                .isInstanceOf(StructuredOutputException.class)
                .hasCause(parseError);

            assertThat(registry.find("app.ai.structured_output.attempts")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(2.0));
            assertThat(registry.find("app.ai.structured_output.invocations")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
        }

        @Test
        @DisplayName("非结构化异常应原样抛出且不重试")
        void shouldRethrowNonStructuredExceptionWithoutRetry() {
            StructuredOutputProperties props = defaultProps();
            props.setMetricsEnabled(true);
            props.setMaxAttempts(2);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, registry);

            ChatClient chatClient = mockChatClient("unused");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            RuntimeException networkError = new RuntimeException(new ConnectException("connection refused"));
            when(converter.convert(anyString())).thenThrow(networkError);

            assertThatThrownBy(() ->
                invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                    null, null, "测试", org.slf4j.LoggerFactory.getLogger("test"))
            )
                .isSameAs(networkError);

            verify(converter, times(1)).convert(anyString());
            assertThat(registry.find("app.ai.structured_output.attempts")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
            assertThat(registry.find("app.ai.structured_output.invocations")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
        }

        @Test
        @DisplayName("maxAttempts=1 时结构化解析失败不重试并统一包装异常")
        void shouldNotRetryAndShouldWrapStructuredOutputFailureWhenMaxAttemptsIsOne() {
            StructuredOutputProperties props = defaultProps();
            props.setMetricsEnabled(true);
            props.setMaxAttempts(1);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(props, registry);

            ChatClient chatClient = mockChatClient("not valid json");

            @SuppressWarnings("unchecked")
            BeanOutputConverter<String> converter = mock(BeanOutputConverter.class);
            RuntimeException parseError = new RuntimeException("cannot deserialize");
            when(converter.convert(anyString())).thenThrow(parseError);

            assertThatThrownBy(() ->
                invoker.invoke(chatClient, FORMAT_PROMPT, "test", converter,
                    null, "前缀：", "测试", org.slf4j.LoggerFactory.getLogger("test"))
            )
                .isInstanceOf(StructuredOutputException.class)
                .hasMessage("测试结构化输出解析失败")
                .hasCause(parseError);

            verify(converter, times(1)).convert(anyString());
            assertThat(registry.find("app.ai.structured_output.attempts")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
            assertThat(registry.find("app.ai.structured_output.invocations")
                .tag("status", "failure").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
        }
    }
}
