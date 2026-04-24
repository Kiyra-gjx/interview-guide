package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.support.AgentContextSection;
import interview.guide.modules.agent.support.AgentContextSectionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentContextAssemblyServiceTest {

    @Mock
    private AgentSessionService sessionService;

    private AgentContextAssemblyService service;

    @BeforeEach
    void setUp() {
        service = new AgentContextAssemblyService(sessionService);
    }

    @Test
    @DisplayName("should assemble multi-source context in a stable priority order")
    void shouldAssembleMultiSourceContextInAStablePriorityOrder() {
        AgentSessionEntity session = session("agent-session-1", "冲刺 Java 面试", 42L);
        AgentMemorySnapshot memory = new AgentMemorySnapshot(
            "过期目标",
            "interview_gap_ready",
            List.of("低分维度: 数据库", "低分维度: 数据库", "候选人优势: 并发"),
            List.of("get_resume_profile", "analyze_interview_gaps", "get_resume_profile"),
            "优先补数据库表达"
        );

        when(sessionService.readKnowledgeBaseIds(session)).thenReturn(List.of(9L, 9L, 11L));

        AgentAssembledContext context = service.assemble(
            session,
            memory,
            "结合我的上下文给我建议下一步",
            520
        );

        assertThat(context.userGoal()).isEqualTo("冲刺 Java 面试");
        assertThat(context.resumeId()).isEqualTo(42L);
        assertThat(context.knowledgeBaseIds()).containsExactly(9L, 11L);

        String summary = context.promptContextSummary();
        assertThat(summary).doesNotContain("最新用户消息");
        assertThat(summary).doesNotContain("当前目标");
        assertThat(summary).contains("记忆状态");
        assertThat(summary).contains("已确认事实");
        assertThat(summary).contains("绑定资源");
        assertThat(summary).contains("已使用工具");
        assertThat(summary.indexOf("记忆状态")).isLessThan(summary.indexOf("已确认事实"));
        assertThat(summary.indexOf("已确认事实")).isLessThan(summary.indexOf("绑定资源"));
        assertThat(summary.indexOf("绑定资源")).isLessThan(summary.indexOf("已使用工具"));

        AgentContextSection goalSection = findSection(context, "goal");
        AgentContextSection factsSection = findSection(context, "confirmed_facts");
        AgentContextSection toolsSection = findSection(context, "used_tools");

        assertThat(goalSection.reason()).isEqualTo("session_goal_overrides_memory_goal");
        assertThat(factsSection.content()).contains("低分维度: 数据库");
        assertThat(factsSection.content()).contains("候选人优势: 并发");
        assertThat(factsSection.content()).doesNotContain("低分维度: 数据库 | 低分维度: 数据库");
        assertThat(toolsSection.content()).contains("get_resume_profile");
        assertThat(toolsSection.content()).contains("analyze_interview_gaps");
        assertThat(toolsSection.content()).doesNotContain("get_resume_profile, get_resume_profile");
    }

    @Test
    @DisplayName("should trim low-priority sections when the context budget is exhausted")
    void shouldTrimLowPrioritySectionsWhenTheContextBudgetIsExhausted() {
        AgentSessionEntity session = session("agent-session-2", "准备系统设计面试", 99L);
        AgentMemorySnapshot memory = new AgentMemorySnapshot(
            "准备系统设计面试",
            "knowledge_context_ready",
            List.of(
                "事实一 ".repeat(20),
                "事实二 ".repeat(20),
                "事实三 ".repeat(20)
            ),
            List.of("get_resume_profile", "search_knowledge_base", "analyze_interview_gaps"),
            "请优先整理高并发设计要点，再补案例表达"
        );

        when(sessionService.readKnowledgeBaseIds(session)).thenReturn(List.of(1L, 2L, 3L, 4L));

        AgentAssembledContext context = service.assemble(
            session,
            memory,
            "帮我收敛下一步准备重点",
            220
        );

        assertThat(context.budget().totalChars()).isEqualTo(220);
        assertThat(context.budget().usedChars()).isLessThanOrEqualTo(220);
        assertThat(findSection(context, "latest_user_message").status()).isNotEqualTo(AgentContextSectionStatus.OMITTED);
        assertThat(findSection(context, "goal").status()).isNotEqualTo(AgentContextSectionStatus.OMITTED);
        assertThat(findSection(context, "memory_state").status()).isNotEqualTo(AgentContextSectionStatus.OMITTED);
        assertThat(findSection(context, "confirmed_facts").status()).isEqualTo(AgentContextSectionStatus.TRUNCATED);
        assertThat(findSection(context, "used_tools").status()).isEqualTo(AgentContextSectionStatus.OMITTED);
    }

    @Test
    @DisplayName("should keep the latest request and goal complete when the total budget allows it")
    void shouldKeepTheLatestRequestAndGoalCompleteWhenTheTotalBudgetAllowsIt() {
        String goal = ("我想准备一场会重点追问项目取舍、性能优化与线上排障案例的 Java 面试，"
            + "并且希望把项目深度和表达结构一起补齐。").repeat(4);
        String latestUserMessage = ("我先补充一段背景：之前主要做后端开发，也带过一点性能压测和线上排障。"
            + "现在真正的问题是，请你结合这些背景，帮我判断下一轮最该优先补哪一块。").repeat(5);
        AgentSessionEntity session = session("agent-session-3", goal, 77L);
        AgentMemorySnapshot memory = new AgentMemorySnapshot(
            "旧目标",
            "goal_received",
            List.of("事实: 做过压测"),
            List.of("get_resume_profile"),
            "先把问题收敛"
        );

        when(sessionService.readKnowledgeBaseIds(session)).thenReturn(List.of(3L, 5L));

        AgentAssembledContext context = service.assemble(
            session,
            memory,
            latestUserMessage,
            960
        );

        AgentContextSection latestSection = findSection(context, "latest_user_message");
        AgentContextSection goalSection = findSection(context, "goal");

        assertThat(latestSection.status()).isEqualTo(AgentContextSectionStatus.INCLUDED);
        assertThat(latestSection.content()).isEqualTo(latestUserMessage);
        assertThat(goalSection.status()).isEqualTo(AgentContextSectionStatus.INCLUDED);
        assertThat(goalSection.content()).isEqualTo(goal);
        assertThat(context.promptContextSummary()).doesNotContain(latestUserMessage);
        assertThat(context.promptContextSummary()).doesNotContain(goal);
    }

    @Test
    @DisplayName("should keep missing bindings explainable and fall back to the memory goal")
    void shouldKeepMissingBindingsExplainableAndFallBackToTheMemoryGoal() {
        AgentSessionEntity session = session("agent-session-3", "   ", null);
        AgentMemorySnapshot memory = new AgentMemorySnapshot(
            "记忆里的求职目标",
            "goal_received",
            List.of(),
            List.of(),
            "先补简历上下文"
        );

        when(sessionService.readKnowledgeBaseIds(session)).thenReturn(List.of());

        AgentAssembledContext context = service.assemble(
            session,
            memory,
            "我该先做什么",
            320
        );

        AgentContextSection goalSection = findSection(context, "goal");
        AgentContextSection resourceSection = findSection(context, "resource_bindings");

        assertThat(context.userGoal()).isEqualTo("记忆里的求职目标");
        assertThat(goalSection.reason()).isEqualTo("memory_goal_fallback");
        assertThat(resourceSection.content()).contains("resumeId=未绑定");
        assertThat(resourceSection.content()).contains("knowledgeBaseIds=[]");
        assertThat(resourceSection.status()).isEqualTo(AgentContextSectionStatus.INCLUDED);
    }

    @Test
    @DisplayName("should align budget usage with the actual assembled section cost even when some sections are hidden from the prompt summary")
    void shouldAlignBudgetUsageWithTheActualAssembledSectionCostEvenWhenSomeSectionsAreHiddenFromThePromptSummary() {
        AgentSessionEntity session = session("agent-session-4", "准备 Java 面试", 42L);
        AgentMemorySnapshot memory = new AgentMemorySnapshot(
            "准备 Java 面试",
            "resume_context_ready",
            List.of("fact-1", "fact-2"),
            List.of("get_resume_profile", "search_knowledge_base"),
            "补齐下一轮练习重点"
        );

        when(sessionService.readKnowledgeBaseIds(session)).thenReturn(List.of(7L, 8L));

        AgentAssembledContext context = service.assemble(
            session,
            memory,
            "请基于当前上下文帮我总结下一步准备重点",
            420
        );

        assertThat(context.promptContextSummary()).isNotEqualTo("暂无可用上下文。");
        assertThat(context.budget().usedChars()).isEqualTo(calculateExpectedBudgetUsage(context));
        assertThat(context.budget().usedChars()).isGreaterThan(context.promptContextSummary().length());
        assertThat(context.budget().remainingChars())
            .isEqualTo(context.budget().totalChars() - context.budget().usedChars());
    }

    private AgentContextSection findSection(AgentAssembledContext context, String key) {
        return context.sections().stream()
            .filter(section -> key.equals(section.key()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing section: " + key));
    }

    private int calculateExpectedBudgetUsage(AgentAssembledContext context) {
        int usedChars = 0;
        int includedSectionCount = 0;
        for (AgentContextSection section : context.sections()) {
            if (section.status() == AgentContextSectionStatus.OMITTED) {
                continue;
            }
            String renderedSection = "- %s: %s".formatted(section.label(), section.content());
            usedChars += renderedSection.length();
            if (includedSectionCount > 0) {
                usedChars += 1;
            }
            includedSectionCount++;
        }
        return usedChars;
    }

    private AgentSessionEntity session(String sessionId, String goal, Long resumeId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setGoal(goal);
        session.setResumeId(resumeId);
        return session;
    }
}
