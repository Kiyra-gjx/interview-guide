package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.support.AgentContextBudget;
import interview.guide.modules.agent.support.AgentContextSection;
import interview.guide.modules.agent.support.AgentContextSectionStatus;
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
    @DisplayName("should expose full goal and latest message alongside the assembled summary in the decision prompt")
    void shouldExposeFullGoalAndLatestMessageAlongsideTheAssembledSummaryInTheDecisionPrompt() throws Exception {
        AgentPromptService promptService = new AgentPromptService(
            new ObjectMapper(),
            utf8Resource("system"),
            utf8Resource("目标={userGoal}\n消息={latestUserMessage}\n上下文={contextSummary}\n步骤={stepIndex}"),
            utf8Resource("answer")
        );

        String prompt = promptService.buildDecisionUserPrompt(context("上下文摘要", "完整目标", "完整消息"), 3);

        assertThat(prompt).contains("完整目标");
        assertThat(prompt).contains("完整消息");
        assertThat(prompt).contains("上下文摘要");
        assertThat(prompt).contains("3");
    }

    @Test
    @DisplayName("should expose full goal and latest message while only passing the normalized answer view to the final answer prompt")
    void shouldExposeFullGoalAndLatestMessageWhileOnlyPassingTheNormalizedAnswerViewToTheFinalAnswerPrompt() throws Exception {
        AgentPromptService promptService = new AgentPromptService(
            new ObjectMapper(),
            utf8Resource("system"),
            utf8Resource("user"),
            utf8Resource("目标={userGoal}\n消息={latestUserMessage}\n上下文={contextSummary}\n工具={toolName}\n视图={toolAnswerJson}")
        );
        AgentToolResult toolResult = new AgentToolResult(
            "summary",
            Map.of("answer", "业务结果"),
            Map.of("retrievalQuery", "debug query"),
            List.of("fact-1")
        );

        String prompt = promptService.buildAnswerUserPrompt(
            context("用于回答阶段的上下文", "完整目标", "完整消息"),
            "search_knowledge_base",
            toolResult
        );

        assertThat(prompt).contains("完整目标");
        assertThat(prompt).contains("完整消息");
        assertThat(prompt).contains("业务结果");
        assertThat(prompt).contains("summary");
        assertThat(prompt).contains("fact-1");
        assertThat(prompt).doesNotContain("debug query");
        assertThat(prompt).contains("用于回答阶段的上下文");
    }

    private ByteArrayResource utf8Resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private AgentAssembledContext context(String summary, String userGoal, String latestUserMessage) {
        return new AgentAssembledContext(
            "agent-session",
            userGoal,
            latestUserMessage,
            42L,
            List.of(7L, 8L),
            new AgentMemorySnapshot("goal", "phase", List.of("fact"), List.of("tool"), "next"),
            summary,
            new AgentContextBudget(320, 180, 140),
            List.of(
                new AgentContextSection(
                    "latest_user_message",
                    "最新用户消息",
                    100,
                    latestUserMessage,
                    AgentContextSectionStatus.INCLUDED,
                    "included",
                    latestUserMessage.length(),
                    latestUserMessage.length()
                )
            )
        );
    }
}
