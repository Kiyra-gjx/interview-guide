package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.support.AgentContextSection;
import interview.guide.modules.agent.support.AgentContextSectionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStage3ContextEvalTest {

    private static final String SUITE_ID = "stage-3-context-set";
    private static final String JSON_REPORT_NAME = "stage-3-context-set-report.json";
    private static final String MARKDOWN_REPORT_NAME = "stage-3-context-set-report.md";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("should run the fixed stage 3 context quantification suite and persist reports")
    void shouldRunTheFixedStage3ContextQuantificationSuiteAndPersistReports() throws Exception {
        Path reportDirectory = Path.of("build", "reports", "agent-eval");

        AgentStage3ContextEvalReport report = runFixedSuite(reportDirectory);

        assertThat(report.summary().totalCases()).isEqualTo(10);
        assertThat(report.summary().passedCases()).isEqualTo(10);
        assertThat(report.summary().averageRawContextChars()).isGreaterThan(report.summary().averageAssembledChars());
        assertThat(report.summary().averageCompressionRate()).isGreaterThan(0);
        assertThat(report.summary().maxCompressionRate()).isGreaterThan(0);
        assertThat(report.summary().keySectionRetentionRate()).isEqualTo(100.0);
        assertThat(report.summary().brokenRequestCount()).isZero();
        assertThat(report.caseResults()).allMatch(AgentStage3ContextEvalCaseResult::passed);

        Path jsonReport = reportDirectory.resolve(JSON_REPORT_NAME);
        Path markdownReport = reportDirectory.resolve(MARKDOWN_REPORT_NAME);

        assertThat(Files.exists(jsonReport)).isTrue();
        assertThat(Files.exists(markdownReport)).isTrue();
        assertThat(Files.readString(markdownReport))
            .contains("平均压缩率")
            .contains("最高压缩率")
            .contains("关键 section 保留率")
            .contains("requestBroken");
    }

    private AgentStage3ContextEvalReport runFixedSuite(Path reportDirectory) throws Exception {
        List<ContextEvalScenario> scenarios = buildScenarios();
        List<AgentStage3ContextEvalCaseResult> caseResults = new ArrayList<>();
        for (ContextEvalScenario scenario : scenarios) {
            AgentContextAssemblyService service = contextServiceFor(scenario.knowledgeBaseIds());
            AgentAssembledContext context = service.assemble(
                scenario.session(),
                scenario.memory(),
                scenario.latestUserMessage(),
                scenario.budget()
            );
            ContextVerificationResult verification = scenario.verifier().verify(context);
            int rawContextChars = calculateRawContextChars(context);
            int assembledChars = context.budget().usedChars();
            double compressionRate = toPercent(rawContextChars - assembledChars, rawContextChars);
            List<String> omittedSections = collectSectionKeys(context, AgentContextSectionStatus.OMITTED);
            List<String> truncatedSections = collectSectionKeys(context, AgentContextSectionStatus.TRUNCATED);
            int retainedKeySections = countRetainedKeySections(context, scenario.keySections());

            caseResults.add(new AgentStage3ContextEvalCaseResult(
                scenario.caseId(),
                scenario.description(),
                scenario.budget(),
                rawContextChars,
                assembledChars,
                compressionRate,
                omittedSections,
                truncatedSections,
                scenario.keySections(),
                retainedKeySections,
                verification.requestBroken(),
                verification.passed(),
                verification.note()
            ));
        }

        AgentStage3ContextEvalReport report = new AgentStage3ContextEvalReport(
            SUITE_ID,
            LocalDateTime.now().toString(),
            buildSummary(caseResults),
            caseResults
        );
        writeReport(reportDirectory, report);
        return report;
    }

    private List<ContextEvalScenario> buildScenarios() {
        List<ContextEvalScenario> scenarios = new ArrayList<>();
        List<String> defaultKeySections = List.of("latest_user_message", "goal", "memory_state", "resource_bindings");

        scenarios.add(new ContextEvalScenario(
            "CTX-01",
            "多源上下文按稳定优先级装配",
            520,
            session("agent-session-1", "冲刺 Java 面试", 42L),
            new AgentMemorySnapshot(
                "过期目标",
                "interview_gap_ready",
                List.of("低分维度: 数据库", "低分维度: 数据库", "候选人优势: 并发"),
                List.of("get_resume_profile", "analyze_interview_gaps", "get_resume_profile"),
                "优先补数据库表达"
            ),
            "结合我的上下文给我建议下一步",
            List.of(9L, 9L, 11L),
            defaultKeySections,
            context -> {
                boolean inOrder = indexOfSummary(context, "记忆状态") < indexOfSummary(context, "已确认事实")
                    && indexOfSummary(context, "已确认事实") < indexOfSummary(context, "绑定资源")
                    && indexOfSummary(context, "绑定资源") < indexOfSummary(context, "已使用工具");
                boolean passed = Objects.equals(context.userGoal(), "冲刺 Java 面试")
                    && Objects.equals(context.resumeId(), 42L)
                    && context.knowledgeBaseIds().equals(List.of(9L, 11L))
                    && !context.promptContextSummary().contains("最新用户消息")
                    && !context.promptContextSummary().contains("当前目标")
                    && inOrder
                    && "session_goal_overrides_memory_goal".equals(findSection(context, "goal").reason())
                    && !findSection(context, "confirmed_facts").content().contains("低分维度: 数据库 | 低分维度: 数据库")
                    && !findSection(context, "used_tools").content().contains("get_resume_profile, get_resume_profile");
                return new ContextVerificationResult(passed, false, "验证稳定优先级、去重和 goal 覆盖");
            }
        ));

        scenarios.add(new ContextEvalScenario(
            "CTX-02",
            "预算耗尽时优先裁低优先级 section",
            220,
            session("agent-session-2", "准备系统设计面试", 99L),
            new AgentMemorySnapshot(
                "准备系统设计面试",
                "knowledge_context_ready",
                List.of("事实一 ".repeat(20), "事实二 ".repeat(20), "事实三 ".repeat(20)),
                List.of("get_resume_profile", "search_knowledge_base", "analyze_interview_gaps"),
                "请优先整理高并发设计要点，再补案例表达"
            ),
            "帮我收敛下一步准备重点",
            List.of(1L, 2L, 3L, 4L),
            defaultKeySections,
            context -> {
                boolean passed = context.budget().totalChars() == 220
                    && context.budget().usedChars() <= 220
                    && findSection(context, "latest_user_message").status() != AgentContextSectionStatus.OMITTED
                    && findSection(context, "goal").status() != AgentContextSectionStatus.OMITTED
                    && findSection(context, "memory_state").status() != AgentContextSectionStatus.OMITTED
                    && findSection(context, "confirmed_facts").status() == AgentContextSectionStatus.TRUNCATED
                    && findSection(context, "used_tools").status() == AgentContextSectionStatus.OMITTED;
                return new ContextVerificationResult(passed, false, "验证 budget 下必留分段保留、低优先级裁剪");
            }
        ));

        String goal = ("我想准备一场会重点追问项目取舍、性能优化与线上排障案例的 Java 面试，"
            + "并且希望把项目深度和表达结构一起补齐。").repeat(4);
        String latestUserMessage = ("我先补充一段背景：之前主要做后端开发，也带过一点性能压测和线上排障。"
            + "现在真正的问题是，请你结合这些背景，帮我判断下一轮最该优先补哪一块。").repeat(5);
        scenarios.add(new ContextEvalScenario(
            "CTX-03",
            "预算允许时保留完整 latest request 和 goal",
            960,
            session("agent-session-3", goal, 77L),
            new AgentMemorySnapshot("旧目标", "goal_received", List.of("事实: 做过压测"), List.of("get_resume_profile"), "先把问题收敛"),
            latestUserMessage,
            List.of(3L, 5L),
            defaultKeySections,
            context -> {
                AgentContextSection latestSection = findSection(context, "latest_user_message");
                AgentContextSection goalSection = findSection(context, "goal");
                boolean passed = latestSection.status() == AgentContextSectionStatus.INCLUDED
                    && latestSection.content().equals(latestUserMessage)
                    && goalSection.status() == AgentContextSectionStatus.INCLUDED
                    && goalSection.content().equals(goal)
                    && !context.promptContextSummary().contains(latestUserMessage)
                    && !context.promptContextSummary().contains(goal);
                return new ContextVerificationResult(passed, false, "验证当前请求和目标完整保留");
            }
        ));

        scenarios.add(new ContextEvalScenario(
            "CTX-04",
            "缺少绑定信息时回退到 memory goal",
            320,
            session("agent-session-4", "   ", null),
            new AgentMemorySnapshot("记忆里的求职目标", "goal_received", List.of(), List.of(), "先补简历上下文"),
            "我该先做什么",
            List.of(),
            defaultKeySections,
            context -> {
                AgentContextSection goalSection = findSection(context, "goal");
                AgentContextSection resourceSection = findSection(context, "resource_bindings");
                boolean passed = Objects.equals(context.userGoal(), "记忆里的求职目标")
                    && "memory_goal_fallback".equals(goalSection.reason())
                    && resourceSection.content().contains("resumeId=未绑定")
                    && resourceSection.content().contains("knowledgeBaseIds=[]")
                    && resourceSection.status() == AgentContextSectionStatus.INCLUDED;
                return new ContextVerificationResult(passed, false, "验证 fallback goal 与 explainable bindings");
            }
        ));

        scenarios.add(new ContextEvalScenario(
            "CTX-05",
            "隐藏 section 时 budget 统计与真实装配成本保持一致",
            420,
            session("agent-session-5", "准备 Java 面试", 42L),
            new AgentMemorySnapshot(
                "准备 Java 面试",
                "resume_context_ready",
                List.of("fact-1", "fact-2"),
                List.of("get_resume_profile", "search_knowledge_base"),
                "补齐下一轮练习重点"
            ),
            "请基于当前上下文帮我总结下一步准备重点",
            List.of(7L, 8L),
            defaultKeySections,
            context -> {
                boolean passed = !context.promptContextSummary().equals("暂无可用上下文。")
                    && context.budget().usedChars() == calculateExpectedBudgetUsage(context)
                    && context.budget().usedChars() > context.promptContextSummary().length()
                    && context.budget().remainingChars() == context.budget().totalChars() - context.budget().usedChars();
                return new ContextVerificationResult(passed, false, "验证 summary 隐藏字段不影响 budget 结算");
            }
        ));

        scenarios.add(new ContextEvalScenario(
            "CTX-06",
            "只绑定 resume 的请求保留正确资源绑定",
            360,
            session("agent-session-6", "围绕简历准备 Java 面试", 55L),
            new AgentMemorySnapshot("围绕简历准备 Java 面试", "resume_context_ready", List.of("事实: 有 Java 项目"), List.of("get_resume_profile"), "根据简历补亮点"),
            "先根据我的简历说最该补哪一块",
            List.of(),
            defaultKeySections,
            context -> {
                AgentContextSection resourceSection = findSection(context, "resource_bindings");
                boolean passed = Objects.equals(context.resumeId(), 55L)
                    && context.knowledgeBaseIds().isEmpty()
                    && resourceSection.content().contains("resumeId=55")
                    && resourceSection.content().contains("knowledgeBaseIds=[]")
                    && resourceSection.status() == AgentContextSectionStatus.INCLUDED;
                return new ContextVerificationResult(passed, false, "验证 resume-only 资源绑定");
            }
        ));

        scenarios.add(new ContextEvalScenario(
            "CTX-07",
            "只绑定 knowledge base 的请求保留正确资源绑定",
            360,
            session("agent-session-7", "结合知识库整理面试要点", null),
            new AgentMemorySnapshot("结合知识库整理面试要点", "knowledge_context_ready", List.of("事实: 需要补系统设计"), List.of("search_knowledge_base"), "从知识库归纳要点"),
            "先根据知识库帮我整理重点",
            List.of(7L, 7L, 8L),
            defaultKeySections,
            context -> {
                AgentContextSection resourceSection = findSection(context, "resource_bindings");
                boolean passed = context.resumeId() == null
                    && context.knowledgeBaseIds().equals(List.of(7L, 8L))
                    && resourceSection.content().contains("resumeId=未绑定")
                    && resourceSection.content().contains("knowledgeBaseIds=[7, 8]");
                return new ContextVerificationResult(passed, false, "验证 knowledge-base-only 资源绑定");
            }
        ));

        scenarios.add(new ContextEvalScenario(
            "CTX-08",
            "follow-up 场景下长 facts 可截断但当前问题不被裁坏",
            260,
            session("agent-session-8", "继续跟进数据库面试表现", 66L),
            new AgentMemorySnapshot(
                "继续跟进数据库面试表现",
                "follow_up_ready",
                List.of("数据库追问点 ".repeat(18), "索引优化案例 ".repeat(16)),
                List.of("analyze_interview_gaps", "suggest_follow_up_questions"),
                "继续追问数据库定位与优化表达"
            ),
            "继续追问我上一次的数据库问题",
            List.of(12L),
            defaultKeySections,
            context -> {
                boolean passed = findSection(context, "confirmed_facts").status() == AgentContextSectionStatus.TRUNCATED
                    && allSectionsRetained(context, defaultKeySections);
                return new ContextVerificationResult(passed, false, "验证 follow-up 长 facts 截断");
            }
        ));

        scenarios.add(new ContextEvalScenario(
            "CTX-09",
            "明显超预算请求仍输出可解释的 section status",
            260,
            session("agent-session-9", "准备系统设计与性能排障的综合面试".repeat(4), 77L),
            new AgentMemorySnapshot(
                "准备系统设计与性能排障的综合面试".repeat(3),
                "heavy_follow_up_ready",
                List.of("高并发案例 ".repeat(20), "数据库瓶颈 ".repeat(18), "线上排障 ".repeat(18)),
                List.of("get_resume_profile", "search_knowledge_base", "analyze_interview_gaps", "suggest_follow_up_questions"),
                "先收敛最关键的追问方向"
            ),
            "请结合我之前的项目经历、数据库问题、并发排障和系统设计背景，告诉我下一轮到底应该先补哪块".repeat(2),
            List.of(3L, 5L, 8L),
            defaultKeySections,
            context -> {
                boolean hasOmittedOrTruncated = context.sections().stream().anyMatch(section ->
                    section.status() == AgentContextSectionStatus.OMITTED || section.status() == AgentContextSectionStatus.TRUNCATED
                );
                boolean passed = hasOmittedOrTruncated && allSectionsRetained(context, defaultKeySections);
                return new ContextVerificationResult(passed, false, "验证超预算场景仍保留关键 section");
            }
        ));

        scenarios.add(new ContextEvalScenario(
            "CTX-10",
            "完全未绑定资源时资源绑定仍保持可解释",
            320,
            session("agent-session-10", "准备后端面试", null),
            new AgentMemorySnapshot("准备后端面试", "goal_received", List.of(), List.of(), "先判断缺什么材料"),
            "如果我现在没有绑定简历和知识库，应该先做什么",
            List.of(),
            defaultKeySections,
            context -> {
                AgentContextSection resourceSection = findSection(context, "resource_bindings");
                boolean passed = resourceSection.status() == AgentContextSectionStatus.INCLUDED
                    && resourceSection.content().contains("resumeId=未绑定")
                    && resourceSection.content().contains("knowledgeBaseIds=[]");
                return new ContextVerificationResult(passed, false, "验证缺少绑定资源时仍可解释");
            }
        ));

        return scenarios;
    }

    private AgentStage3ContextEvalSummary buildSummary(List<AgentStage3ContextEvalCaseResult> caseResults) {
        int totalCases = caseResults.size();
        int passedCases = (int) caseResults.stream().filter(AgentStage3ContextEvalCaseResult::passed).count();
        double averageRawContextChars = average(caseResults.stream().mapToInt(AgentStage3ContextEvalCaseResult::rawContextChars).toArray());
        double averageAssembledChars = average(caseResults.stream().mapToInt(AgentStage3ContextEvalCaseResult::assembledChars).toArray());
        double averageCompressionRate = average(caseResults.stream().mapToDouble(AgentStage3ContextEvalCaseResult::compressionRate).toArray());
        double maxCompressionRate = caseResults.stream().mapToDouble(AgentStage3ContextEvalCaseResult::compressionRate).max().orElse(0);
        int totalKeySections = caseResults.stream().mapToInt(result -> result.keySections().size()).sum();
        int retainedKeySections = caseResults.stream().mapToInt(AgentStage3ContextEvalCaseResult::retainedKeySectionCount).sum();
        double keySectionRetentionRate = totalKeySections == 0 ? 0 : Math.round((retainedKeySections * 10000.0) / totalKeySections) / 100.0;
        int brokenRequestCount = (int) caseResults.stream().filter(AgentStage3ContextEvalCaseResult::requestBroken).count();
        return new AgentStage3ContextEvalSummary(
            totalCases,
            passedCases,
            averageRawContextChars,
            averageAssembledChars,
            averageCompressionRate,
            Math.round(maxCompressionRate * 100.0) / 100.0,
            keySectionRetentionRate,
            brokenRequestCount
        );
    }

    private void writeReport(Path reportDirectory, AgentStage3ContextEvalReport report) throws Exception {
        Files.createDirectories(reportDirectory);
        Files.writeString(
            reportDirectory.resolve(JSON_REPORT_NAME),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        );
        Files.writeString(reportDirectory.resolve(MARKDOWN_REPORT_NAME), toMarkdown(report));
    }

    private String toMarkdown(AgentStage3ContextEvalReport report) {
        AgentStage3ContextEvalSummary summary = report.summary();
        StringBuilder builder = new StringBuilder();
        builder.append("# Stage 3 Context Set Report\n\n");
        builder.append("- suite: ").append(report.suiteId()).append('\n');
        builder.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        builder.append("- totalCases: ").append(summary.totalCases()).append('\n');
        builder.append("- passedCases: ").append(summary.passedCases()).append('\n');
        builder.append("- 平均原始上下文长度: ").append(summary.averageRawContextChars()).append('\n');
        builder.append("- 平均装配后长度: ").append(summary.averageAssembledChars()).append('\n');
        builder.append("- 平均压缩率: ").append(summary.averageCompressionRate()).append("%\n");
        builder.append("- 最高压缩率: ").append(summary.maxCompressionRate()).append("%\n");
        builder.append("- 关键 section 保留率: ").append(summary.keySectionRetentionRate()).append("%\n");
        builder.append("- requestBroken 数量: ").append(summary.brokenRequestCount()).append("\n\n");
        builder.append("| Case | Budget | RawChars | AssembledChars | CompressionRate | Omitted | Truncated | requestBroken | Passed | Note |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (AgentStage3ContextEvalCaseResult result : report.caseResults()) {
            builder.append("| ")
                .append(result.caseId())
                .append(" | ")
                .append(result.budget())
                .append(" | ")
                .append(result.rawContextChars())
                .append(" | ")
                .append(result.assembledChars())
                .append(" | ")
                .append(result.compressionRate())
                .append("% | ")
                .append(joinList(result.omittedSections()))
                .append(" | ")
                .append(joinList(result.truncatedSections()))
                .append(" | ")
                .append(result.requestBroken())
                .append(" | ")
                .append(result.passed())
                .append(" | ")
                .append(result.note())
                .append(" |\n");
        }
        return builder.toString();
    }

    private AgentContextAssemblyService contextServiceFor(List<Long> knowledgeBaseIds) {
        AgentSessionService sessionService = mock(AgentSessionService.class);
        AgentContextAssemblyService service = new AgentContextAssemblyService(sessionService, testSanitizer());
        when(sessionService.readKnowledgeBaseIds(org.mockito.ArgumentMatchers.any())).thenReturn(knowledgeBaseIds);
        return service;
    }

    private static AgentSessionEntity session(String sessionId, String goal, Long resumeId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setGoal(goal);
        session.setResumeId(resumeId);
        return session;
    }

    private static AgentContextSection findSection(AgentAssembledContext context, String key) {
        return context.sections().stream()
            .filter(section -> key.equals(section.key()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("missing section: " + key));
    }

    private static boolean allSectionsRetained(AgentAssembledContext context, List<String> keys) {
        return keys.stream().allMatch(key -> findSection(context, key).status() != AgentContextSectionStatus.OMITTED);
    }

    private static int countRetainedKeySections(AgentAssembledContext context, List<String> keys) {
        return (int) keys.stream()
            .filter(key -> findSection(context, key).status() != AgentContextSectionStatus.OMITTED)
            .count();
    }

    private static int indexOfSummary(AgentAssembledContext context, String token) {
        return context.promptContextSummary().indexOf(token);
    }

    private static int calculateExpectedBudgetUsage(AgentAssembledContext context) {
        int usedChars = 0;
        int includedSectionCount = 0;
        for (AgentContextSection section : context.sections()) {
            if (section.status() == AgentContextSectionStatus.OMITTED) {
                continue;
            }
            usedChars += renderCost(section.label(), section.includedLength(), includedSectionCount > 0);
            includedSectionCount++;
        }
        return usedChars;
    }

    private static int calculateRawContextChars(AgentAssembledContext context) {
        int rawChars = 0;
        int nonEmptySectionCount = 0;
        for (AgentContextSection section : context.sections()) {
            if (section.originalLength() <= 0) {
                continue;
            }
            rawChars += renderCost(section.label(), section.originalLength(), nonEmptySectionCount > 0);
            nonEmptySectionCount++;
        }
        return rawChars;
    }

    private static int renderCost(String label, int contentLength, boolean hasPreviousIncluded) {
        return (hasPreviousIncluded ? 1 : 0) + label.length() + 4 + Math.max(0, contentLength);
    }

    private static List<String> collectSectionKeys(AgentAssembledContext context, AgentContextSectionStatus status) {
        return context.sections().stream()
            .filter(section -> section.status() == status)
            .map(AgentContextSection::key)
            .toList();
    }

    private static double toPercent(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return Math.round((numerator * 10000.0) / denominator) / 100.0;
    }

    private static double average(int[] values) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (int value : values) {
            sum += value;
        }
        return Math.round((sum / values.length) * 100.0) / 100.0;
    }

    private static double average(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return Math.round((sum / values.length) * 100.0) / 100.0;
    }

    private static String joinList(List<String> values) {
        return values == null || values.isEmpty() ? "-" : String.join(", ", values);
    }

    private record ContextEvalScenario(
        String caseId,
        String description,
        int budget,
        AgentSessionEntity session,
        AgentMemorySnapshot memory,
        String latestUserMessage,
        List<Long> knowledgeBaseIds,
        List<String> keySections,
        ScenarioVerifier verifier
    ) {
    }

    private record ContextVerificationResult(
        boolean passed,
        boolean requestBroken,
        String note
    ) {
    }

    private record AgentStage3ContextEvalSummary(
        int totalCases,
        int passedCases,
        double averageRawContextChars,
        double averageAssembledChars,
        double averageCompressionRate,
        double maxCompressionRate,
        double keySectionRetentionRate,
        int brokenRequestCount
    ) {
    }

    private record AgentStage3ContextEvalCaseResult(
        String caseId,
        String description,
        int budget,
        int rawContextChars,
        int assembledChars,
        double compressionRate,
        List<String> omittedSections,
        List<String> truncatedSections,
        List<String> keySections,
        int retainedKeySectionCount,
        boolean requestBroken,
        boolean passed,
        String note
    ) {
    }

    private record AgentStage3ContextEvalReport(
        String suiteId,
        String generatedAt,
        AgentStage3ContextEvalSummary summary,
        List<AgentStage3ContextEvalCaseResult> caseResults
    ) {
    }

    @FunctionalInterface
    private interface ScenarioVerifier {
        ContextVerificationResult verify(AgentAssembledContext context);
    }

    private static PromptSanitizer testSanitizer() {
        PromptSanitizer s = new PromptSanitizer();
        ReflectionTestUtils.setField(s, "enabled", true);
        return s;
    }
}
