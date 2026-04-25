package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.support.AgentToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryServiceTest {

    private final AgentMemoryService memoryService = new AgentMemoryService(new ObjectMapper());

    @Test
    @DisplayName("should map interview tools to dedicated memory phases")
    void shouldMapInterviewToolsToDedicatedMemoryPhases() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of("fact-1"),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = new AgentToolResult("summary", Map.of(), Map.of(), List.of("fact-2"));

        assertThat(memoryService.updateAfterTool(current, "get_interview_history_summary", result).currentPhase())
            .isEqualTo("interview_history_ready");
        assertThat(memoryService.updateAfterTool(current, "analyze_interview_gaps", result).currentPhase())
            .isEqualTo("interview_gap_ready");
        assertThat(memoryService.updateAfterTool(current, "suggest_follow_up_questions", result).currentPhase())
            .isEqualTo("follow_up_ready");
    }

    @Test
    @DisplayName("should deduplicate confirmed facts in order and cap them to memory limit")
    void shouldDeduplicateConfirmedFactsInOrderAndCapThemToMemoryLimit() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of("fact-1", "fact-2", "fact-3"),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = new AgentToolResult(
            "summary",
            Map.of(),
            Map.of(),
            List.of("fact-2", " ", "fact-4", "fact-5", "fact-6", "fact-7", "fact-8", "fact-9", "fact-10")
        );

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "analyze_interview_gaps", result);

        assertThat(updated.confirmedFacts()).containsExactly(
            "fact-1",
            "fact-2",
            "fact-3",
            "fact-4",
            "fact-5",
            "fact-6",
            "fact-7",
            "fact-8"
        );
        assertThat(updated.usedTools()).containsExactly("get_resume_profile", "analyze_interview_gaps");
        assertThat(updated.nextFocus()).isEqualTo("summary");
    }

    @Test
    @DisplayName("should normalize tool summary and facts before writing them into memory")
    void shouldNormalizeToolSummaryAndFactsBeforeWritingThemIntoMemory() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of(),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = new AgentToolResult(
            "s".repeat(220),
            Map.of(),
            Map.of(),
            IntStream.range(0, 8)
                .mapToObj(index -> "fact-" + index + "-" + "x".repeat(200))
                .toList()
        );

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "search_knowledge_base", result);

        assertThat(updated.nextFocus()).hasSize(203).endsWith("...");
        assertThat(updated.confirmedFacts()).hasSize(6);
        assertThat(updated.confirmedFacts()).allSatisfy(fact -> assertThat(fact).hasSizeLessThanOrEqualTo(183));
    }

    @Test
    @DisplayName("should normalize legacy memory facts when merging new tool results")
    void shouldNormalizeLegacyMemoryFactsWhenMergingNewToolResults() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            List.of("legacy-" + "x".repeat(200)),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = new AgentToolResult(
            "summary",
            Map.of(),
            Map.of(),
            List.of("fact-2")
        );

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "analyze_interview_gaps", result);

        assertThat(updated.confirmedFacts()).containsExactly("legacy-" + "x".repeat(173) + "...", "fact-2");
    }

    @Test
    @DisplayName("should keep existing eight short memory facts when merging a new tool result")
    void shouldKeepExistingEightShortMemoryFactsWhenMergingANewToolResult() {
        AgentMemorySnapshot current = new AgentMemorySnapshot(
            "准备面试",
            "goal_received",
            IntStream.rangeClosed(1, 8)
                .mapToObj(index -> "fact-" + index)
                .toList(),
            List.of("get_resume_profile"),
            "next"
        );
        AgentToolResult result = new AgentToolResult(
            "summary",
            Map.of(),
            Map.of(),
            List.of("fact-9")
        );

        AgentMemorySnapshot updated = memoryService.updateAfterTool(current, "search_knowledge_base", result);

        assertThat(updated.confirmedFacts()).containsExactly(
            "fact-1",
            "fact-2",
            "fact-3",
            "fact-4",
            "fact-5",
            "fact-6",
            "fact-7",
            "fact-8"
        );
    }
}
