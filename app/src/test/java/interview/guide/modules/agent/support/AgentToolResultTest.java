package interview.guide.modules.agent.support;

import interview.guide.modules.agent.model.AgentToolOutputDTO;
import interview.guide.modules.knowledgebase.model.QueryDebugInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolResultTest {

    private static final class DebugPojo {

        private final String source;
        private final int count;

        private DebugPojo(String source, int count) {
            this.source = source;
            this.count = count;
        }

        public String getSource() {
            return source;
        }

        public int getCount() {
            return count;
        }
    }

    @Test
    @DisplayName("should build a normalized answer prompt payload without leaking debug fields")
    void shouldBuildANormalizedAnswerPromptPayloadWithoutLeakingDebugFields() {
        AgentToolResult result = new AgentToolResult(
            "已读取简历画像",
            Map.of(
                "resumeId", 42L,
                "strengths", List.of("并发", "JVM")
            ),
            Map.of("token", "debug-secret"),
            List.of("已绑定简历ID: 42")
        );

        Map<String, Object> payload = result.promptPayload();

        assertThat(payload).containsEntry("summary", "已读取简历画像");
        assertThat(payload).containsEntry("facts", List.of("已绑定简历ID: 42"));
        assertThat((Map<String, Object>) payload.get("answer")).containsEntry("resumeId", 42L);
        assertThat(payload).doesNotContainKey("debug");
    }

    @Test
    @DisplayName("should normalize heterogeneous payloads and mark truncation when tool output is oversized")
    void shouldNormalizeHeterogeneousPayloadsAndMarkTruncationWhenToolOutputIsOversized() {
        AgentToolResult result = new AgentToolResult(
            "s".repeat(220),
            Map.of(
                "answer", "a".repeat(520),
                "recentSessions", IntStream.range(0, 10).mapToObj(index -> "session-" + index).toList()
            ),
            Map.of(
                "hits", IntStream.range(0, 7)
                    .mapToObj(index -> Map.of("snippet", "d".repeat(340), "index", index))
                    .toList()
            ),
            IntStream.range(0, 8).mapToObj(index -> "fact-" + index + "-" + "x".repeat(200)).toList()
        );

        AgentToolOutputDTO output = result.toToolOutput("tool_result", "最终回复");
        Map<String, Object> tracePayload = result.tracePayload("最终回复");

        assertThat(output.summary()).endsWith("...");
        assertThat(output.reply()).isEqualTo("最终回复");
        assertThat(output.normalization().summaryTruncated()).isTrue();
        assertThat(output.normalization().answerTruncated()).isTrue();
        assertThat(output.normalization().debugTruncated()).isTrue();
        assertThat(output.normalization().factsTruncated()).isTrue();
        assertThat(((String) output.answer().get("answer"))).endsWith("...");
        assertThat(((List<?>) output.answer().get("recentSessions"))).hasSize(8);
        assertThat(((List<?>) output.debug().get("hits"))).hasSize(5);
        assertThat(output.facts()).hasSize(6);

        assertThat(tracePayload).containsKeys("answer", "debug", "facts", "normalization");
        assertThat(tracePayload).doesNotContainKeys("answerPayload", "debugPayload", "confirmedFacts");
    }

    @Test
    @DisplayName("should preserve record and pojo debug payloads as structured objects")
    void shouldPreserveRecordAndPojoDebugPayloadsAsStructuredObjects() {
        AgentToolResult result = new AgentToolResult(
            "summary",
            Map.of(),
            Map.of(
                "hits", List.of(new QueryDebugInfo.Hit("kb-1", "source", "section", 3, "preview")),
                "owner", new DebugPojo("resume", 2)
            ),
            List.of()
        );

        AgentToolOutputDTO output = result.toToolOutput("tool_result", null);

        assertThat((List<?>) output.debug().get("hits"))
            .singleElement()
            .isEqualTo(Map.of(
                "knowledgeBaseId", "kb-1",
                "sourceTitle", "source",
                "sectionTitle", "section",
                "chunkIndex", 3,
                "preview", "preview"
            ));
        assertThat(output.debug().get("owner"))
            .isEqualTo(Map.of("count", 2, "source", "resume"));
    }
}
