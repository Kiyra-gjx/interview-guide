package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.support.AgentToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptServiceTest {

    @Test
    @DisplayName("should only expose answer payload to the final answer prompt")
    void shouldOnlyExposeAnswerPayloadToFinalAnswerPrompt() throws Exception {
        AgentPromptService promptService = new AgentPromptService(
            new ObjectMapper(),
            utf8Resource("system"),
            utf8Resource("user"),
            utf8Resource("工具={toolName}\n载荷={answerPayloadJson}")
        );
        AgentToolResult toolResult = new AgentToolResult(
            "summary",
            Map.of("answer", "业务结果"),
            Map.of("retrievalQuery", "debug query"),
            List.of("fact-1")
        );

        String prompt = promptService.buildAnswerUserPrompt(
            "准备 Java 面试",
            "帮我总结重点",
            new AgentMemorySnapshot("goal", "phase", List.of("fact"), List.of("tool"), "next"),
            "search_knowledge_base",
            toolResult
        );

        assertThat(prompt).contains("业务结果");
        assertThat(prompt).doesNotContain("debug query");
    }

    private ByteArrayResource utf8Resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
