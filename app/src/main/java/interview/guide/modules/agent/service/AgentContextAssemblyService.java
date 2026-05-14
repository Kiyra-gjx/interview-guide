package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.support.AgentContextBudget;
import interview.guide.modules.agent.support.AgentContextSection;
import interview.guide.modules.agent.support.AgentContextSectionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Agent 上下文装配服务。
 * 负责统一收口 Prompt 与 Tool 共享的上下文来源、优先级和预算裁剪语义。
 */
@Service
@RequiredArgsConstructor
public class AgentContextAssemblyService {

    private static final int DEFAULT_TOTAL_CONTEXT_CHARS = 960;
    private static final int MIN_REQUIRED_SECTION_CHARS = 24;
    private static final int MIN_OPTIONAL_SECTION_CHARS = 40;
    private static final int UNBOUNDED_SECTION_CHARS = Integer.MAX_VALUE;

    private final AgentSessionService sessionService;
    private final PromptSanitizer promptSanitizer;

    /**
     * 按默认预算装配上下文。
     *
     * @param session 当前会话
     * @param memorySnapshot 当前记忆快照
     * @param latestUserMessage 最新用户消息
     * @return 统一装配后的上下文快照
     */
    public AgentAssembledContext assemble(
        AgentSessionEntity session,
        AgentMemorySnapshot memorySnapshot,
        String latestUserMessage
    ) {
        return assemble(session, memorySnapshot, latestUserMessage, DEFAULT_TOTAL_CONTEXT_CHARS);
    }

    /**
     * 按指定预算装配上下文。
     * 该重载主要用于测试和需要显式控制预算的场景。
     *
     * @param session 当前会话
     * @param memorySnapshot 当前记忆快照
     * @param latestUserMessage 最新用户消息
     * @param totalContextChars 本次装配总预算
     * @return 统一装配后的上下文快照
     */
    AgentAssembledContext assemble(
        AgentSessionEntity session,
        AgentMemorySnapshot memorySnapshot,
        String latestUserMessage,
        int totalContextChars
    ) {
        AgentMemorySnapshot safeMemory = safeMemory(memorySnapshot);
        List<Long> knowledgeBaseIds = normalizeKnowledgeBaseIds(sessionService.readKnowledgeBaseIds(session));
        String resolvedGoal = resolveGoal(session, safeMemory);
        int normalizedTotalContextChars = Math.max(totalContextChars, MIN_REQUIRED_SECTION_CHARS);

        // 1. 先把候选来源按优先级固定下来，避免相同输入下分段顺序漂移。
        List<SectionCandidate> candidates = List.of(
            new SectionCandidate(
                "latest_user_message",
                "最新用户消息",
                100,
                normalizeText(latestUserMessage),
                UNBOUNDED_SECTION_CHARS,
                true,
                "latest_user_message"
            ),
            new SectionCandidate(
                "goal",
                "当前目标",
                90,
                resolvedGoal,
                UNBOUNDED_SECTION_CHARS,
                true,
                resolveGoalReason(session, safeMemory)
            ),
            new SectionCandidate(
                "memory_state",
                "记忆状态",
                80,
                buildMemoryState(safeMemory),
                160,
                true,
                "memory_snapshot"
            ),
            new SectionCandidate(
                "confirmed_facts",
                "已确认事实",
                70,
                buildConfirmedFacts(safeMemory),
                240,
                false,
                "memory_confirmed_facts"
            ),
            new SectionCandidate(
                "resource_bindings",
                "绑定资源",
                60,
                buildResourceBindings(session, knowledgeBaseIds),
                120,
                true,
                "session_bindings"
            ),
            new SectionCandidate(
                "used_tools",
                "已使用工具",
                50,
                buildUsedTools(safeMemory),
                120,
                false,
                "memory_used_tools"
            )
        );

        // 2. 再按预算裁剪分段，同时为后续必留分段预留最小空间。
        List<AgentContextSection> sections = assembleSections(candidates, normalizedTotalContextChars);
        String promptContextSummary = renderPromptContextSummary(sections);
        AgentContextBudget budget = buildBudget(normalizedTotalContextChars, sections);

        // 3. 最后渲染给 Prompt 的上下文摘要，Tool 侧也共用同一份装配结果。
        return new AgentAssembledContext(
            session == null ? null : session.getSessionId(),
            resolvedGoal,
            normalizeText(latestUserMessage),
            session == null ? null : session.getResumeId(),
            knowledgeBaseIds,
            safeMemory,
            promptContextSummary,
            budget,
            sections
        );
    }

    /**
     * 组装所有上下文分段，并记录每段的保留或裁剪结果。
     *
     * @param candidates 候选分段
     * @param totalBudget 本次总预算
     * @return 装配后的分段列表
     */
    private List<AgentContextSection> assembleSections(List<SectionCandidate> candidates, int totalBudget) {
        List<AgentContextSection> sections = new ArrayList<>();
        int remainingBudget = totalBudget;
        int includedSectionCount = 0;
        for (int index = 0; index < candidates.size(); index++) {
            SectionCandidate candidate = candidates.get(index);
            String content = normalizeText(candidate.content());
            if (content == null) {
                sections.add(new AgentContextSection(
                    candidate.key(),
                    candidate.label(),
                    candidate.priority(),
                    "",
                    AgentContextSectionStatus.OMITTED,
                    "source_empty",
                    0,
                    0
                ));
                continue;
            }

            // 1. 先为后续必留分段预留最小空间，避免当前高优分段把后面的必留信息挤掉。
            int reservedBudget = reserveRequiredBudget(candidates, index + 1, includedSectionCount + 1);
            int maxContentChars = resolveMaxContentChars(candidate, remainingBudget, reservedBudget, includedSectionCount > 0);
            if (!candidate.required() && maxContentChars < MIN_OPTIONAL_SECTION_CHARS) {
                sections.add(new AgentContextSection(
                    candidate.key(),
                    candidate.label(),
                    candidate.priority(),
                    "",
                    AgentContextSectionStatus.OMITTED,
                    "budget_exhausted",
                    content.length(),
                    0
                ));
                continue;
            }

            // 2. 必留分段即使预算很紧，也至少保留最小可解释长度。
            if (candidate.required() && maxContentChars < MIN_REQUIRED_SECTION_CHARS) {
                maxContentChars = Math.min(
                    candidate.maxChars(),
                    Math.max(MIN_REQUIRED_SECTION_CHARS, resolveRemainingContentBudget(candidate, remainingBudget, includedSectionCount > 0))
                );
            }

            String includedContent = content;
            AgentContextSectionStatus status = AgentContextSectionStatus.INCLUDED;
            String reason = candidate.reason();
            if (content.length() > maxContentChars && maxContentChars > 0) {
                includedContent = trimToLength(content, maxContentChars);
                status = AgentContextSectionStatus.TRUNCATED;
                reason = "truncated_to_budget";
            }

            sections.add(new AgentContextSection(
                candidate.key(),
                candidate.label(),
                candidate.priority(),
                includedContent,
                status,
                reason,
                content.length(),
                includedContent.length()
            ));
            // 3. 真实扣减时按最终渲染成本结算，把段间换行也算进去。
            remainingBudget = Math.max(
                0,
                remainingBudget - renderSectionCost(candidate.label(), includedContent, includedSectionCount > 0)
            );
            includedSectionCount++;
        }
        return sections;
    }

    /**
     * 计算后续必留分段需要预留的最小预算。
     *
     * @param candidates 候选分段
     * @param startIndex 起始下标
     * @return 需要预留的字符数
     */
    private int reserveRequiredBudget(List<SectionCandidate> candidates, int startIndex, int includedSectionCount) {
        int reserved = 0;
        boolean hasPreviousIncluded = includedSectionCount > 0;
        for (int index = startIndex; index < candidates.size(); index++) {
            SectionCandidate candidate = candidates.get(index);
            if (!candidate.required()) {
                continue;
            }
            String content = normalizeText(candidate.content());
            if (content == null) {
                continue;
            }
            int minimalContentChars = Math.min(content.length(), Math.min(candidate.maxChars(), MIN_REQUIRED_SECTION_CHARS));
            reserved += renderSectionCost(candidate.label(), trimToLength(content, minimalContentChars), hasPreviousIncluded);
            hasPreviousIncluded = true;
        }
        return reserved;
    }

    /**
     * 根据当前剩余预算计算该分段可用的最大内容长度。
     *
     * @param candidate 候选分段
     * @param remainingBudget 剩余预算
     * @param reservedBudget 已预留的预算
     * @return 本分段允许使用的内容长度
     */
    private int resolveMaxContentChars(
        SectionCandidate candidate,
        int remainingBudget,
        int reservedBudget,
        boolean hasPreviousIncluded
    ) {
        int availableBudget = resolveRemainingContentBudget(candidate, Math.max(0, remainingBudget - reservedBudget), hasPreviousIncluded);
        if (availableBudget <= 0) {
            return 0;
        }
        return Math.min(candidate.maxChars(), availableBudget);
    }

    /**
     * 计算在当前预算下，扣除标题和段间换行后，还能分配给内容本身的字符数。
     *
     * @param candidate 候选分段
     * @param availableBudget 扣除后续预留预算后的可用空间
     * @param hasPreviousIncluded 当前分段前是否已有保留分段
     * @return 内容本身可用的字符数
     */
    private int resolveRemainingContentBudget(SectionCandidate candidate, int availableBudget, boolean hasPreviousIncluded) {
        int separatorCost = hasPreviousIncluded ? 1 : 0;
        int labelCost = candidate.label().length() + 4;
        if (availableBudget <= separatorCost + labelCost) {
            return 0;
        }
        return availableBudget - separatorCost - labelCost;
    }

    /**
     * 生成统一的 Prompt 上下文摘要。
     *
     * @param sections 已装配分段
     * @return Prompt 可直接消费的上下文摘要
     */
    private String renderPromptContextSummary(List<AgentContextSection> sections) {
        return sections.stream()
            .filter(section -> section.status() != AgentContextSectionStatus.OMITTED)
            // 1. 当前目标和最新消息已经由 prompt 模板独立透传，这里不再重复展开。
            .filter(this::shouldRenderInPromptSummary)
            .map(section -> renderSection(section.label(), section.content()))
            .reduce((first, second) -> first + "\n" + second)
            .orElse("暂无可用上下文。");
    }

    /**
     * 判断某个分段是否应该进入 Prompt 摘要。
     *
     * @param section 上下文分段
     * @return true 表示应渲染到摘要，false 表示只保留在明细中
     */
    private boolean shouldRenderInPromptSummary(AgentContextSection section) {
        if (section == null) {
            return false;
        }
        return !"latest_user_message".equals(section.key())
            && !"goal".equals(section.key());
    }

    /**
     * 计算最终预算使用情况。
     *
     * @param totalChars 总预算
     * @param sections 装配后的分段
     * @return 预算信息
     */
    private AgentContextBudget buildBudget(int totalChars, List<AgentContextSection> sections) {
        int usedChars = calculateConsumedBudget(sections);
        int normalizedTotal = Math.max(totalChars, usedChars);
        return new AgentContextBudget(normalizedTotal, usedChars, Math.max(0, normalizedTotal - usedChars));
    }

    /**
     * 计算本次装配真实消耗的预算。
     * 这里按所有已保留分段结算，即便其中某些分段不会出现在 prompt summary 中，
     * 只要它们参与了裁剪决策，就必须体现在 budget 元数据里。
     *
     * @param sections 装配后的分段
     * @return 实际消耗的预算字符数
     */
    private int calculateConsumedBudget(List<AgentContextSection> sections) {
        int usedChars = 0;
        int includedSectionCount = 0;
        for (AgentContextSection section : safeList(sections)) {
            if (section.status() == AgentContextSectionStatus.OMITTED) {
                continue;
            }

            // 1. 每个已保留分段都按真实渲染成本结算，和 assemble 阶段保持一致。
            usedChars += renderSectionCost(section.label(), section.content(), includedSectionCount > 0);
            includedSectionCount++;
        }
        return usedChars;
    }

    /**
     * 构造记忆状态分段内容。
     *
     * @param memorySnapshot 当前记忆快照
     * @return 记忆状态描述
     */
    private String buildMemoryState(AgentMemorySnapshot memorySnapshot) {
        return "phase=%s; nextFocus=%s".formatted(
            blankToDefault(normalizeText(memorySnapshot.currentPhase()), "unknown"),
            blankToDefault(normalizeText(memorySnapshot.nextFocus()), "暂无")
        );
    }

    /**
     * 构造事实分段内容，并做稳定去重。
     *
     * @param memorySnapshot 当前记忆快照
     * @return 事实摘要
     */
    private String buildConfirmedFacts(AgentMemorySnapshot memorySnapshot) {
        String raw = joinDistinct(memorySnapshot.confirmedFacts(), " | ");
        return raw == null ? null : promptSanitizer.sanitize(raw);
    }

    /**
     * 构造资源绑定分段内容。
     *
     * @param session 当前会话
     * @param knowledgeBaseIds 已规范化的知识库 ID
     * @return 绑定资源描述
     */
    private String buildResourceBindings(AgentSessionEntity session, List<Long> knowledgeBaseIds) {
        Long resumeId = session == null ? null : session.getResumeId();
        return "resumeId=%s; knowledgeBaseIds=%s".formatted(
            resumeId == null ? "未绑定" : resumeId,
            knowledgeBaseIds
        );
    }

    /**
     * 构造已使用工具分段内容，并做稳定去重。
     *
     * @param memorySnapshot 当前记忆快照
     * @return 工具摘要
     */
    private String buildUsedTools(AgentMemorySnapshot memorySnapshot) {
        return joinDistinct(memorySnapshot.usedTools(), ", ");
    }

    /**
     * 解析当前应使用的用户目标。
     *
     * @param session 当前会话
     * @param memorySnapshot 当前记忆快照
     * @return 最终目标
     */
    private String resolveGoal(AgentSessionEntity session, AgentMemorySnapshot memorySnapshot) {
        String sessionGoal = session == null ? null : normalizeText(session.getGoal());
        if (sessionGoal != null) {
            return sessionGoal;
        }
        String memoryGoal = normalizeText(memorySnapshot.userGoal());
        return blankToDefault(memoryGoal, "未提供目标");
    }

    /**
     * 解释目标分段为什么采用当前来源。
     *
     * @param session 当前会话
     * @param memorySnapshot 当前记忆快照
     * @return 目标分段原因
     */
    private String resolveGoalReason(AgentSessionEntity session, AgentMemorySnapshot memorySnapshot) {
        String sessionGoal = session == null ? null : normalizeText(session.getGoal());
        String memoryGoal = normalizeText(memorySnapshot.userGoal());
        if (sessionGoal != null && memoryGoal != null && !sessionGoal.equals(memoryGoal)) {
            return "session_goal_overrides_memory_goal";
        }
        if (sessionGoal != null) {
            return "session_goal";
        }
        if (memoryGoal != null) {
            return "memory_goal_fallback";
        }
        return "goal_unavailable";
    }

    /**
     * 对知识库绑定做稳定去重，避免相同 ID 在 Prompt 和 Tool 上下文中重复出现。
     *
     * @param knowledgeBaseIds 原始知识库 ID 列表
     * @return 去重后的知识库 ID 列表
     */
    private List<Long> normalizeKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long knowledgeBaseId : safeList(knowledgeBaseIds)) {
            if (knowledgeBaseId != null) {
                normalized.add(knowledgeBaseId);
            }
        }
        return List.copyOf(normalized);
    }

    /**
     * 用稳定顺序去重并拼接字符串列表。
     *
     * @param values 原始值列表
     * @param delimiter 拼接分隔符
     * @return 去重后的拼接结果
     */
    private String joinDistinct(List<String> values, String delimiter) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : safeList(values)) {
            String normalizedValue = normalizeText(value);
            if (normalizedValue != null) {
                normalized.add(normalizedValue);
            }
        }
        return normalized.isEmpty() ? null : String.join(delimiter, normalized);
    }

    /**
     * 规范化字符串，统一去除首尾空白。
     *
     * @param value 原始字符串
     * @return 规范化后的字符串
     */
    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 对空值 memory 做兜底，避免装配过程反复判空。
     *
     * @param memorySnapshot 原始 memory
     * @return 可安全读取的 memory
     */
    private AgentMemorySnapshot safeMemory(AgentMemorySnapshot memorySnapshot) {
        if (memorySnapshot != null) {
            return memorySnapshot;
        }
        return new AgentMemorySnapshot("", "", List.of(), List.of(), "");
    }

    /**
     * 按指定长度裁剪文本，并用省略号标记。
     *
     * @param value 原始文本
     * @param maxLength 最大长度
     * @return 裁剪后的文本
     */
    private String trimToLength(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    /**
     * 渲染单个分段到 Prompt 摘要。
     *
     * @param label 分段标题
     * @param content 分段内容
     * @return 最终渲染结果
     */
    private String renderSection(String label, String content) {
        return "- %s: %s".formatted(label, blankToDefault(content, "暂无"));
    }

    /**
     * 计算单个分段在最终 Prompt 摘要里的真实渲染成本。
     *
     * @param label 分段标题
     * @param content 分段内容
     * @param hasPreviousIncluded 当前分段前是否已有保留分段
     * @return 真实渲染成本
     */
    private int renderSectionCost(String label, String content, boolean hasPreviousIncluded) {
        return renderSection(label, content).length() + (hasPreviousIncluded ? 1 : 0);
    }

    /**
     * 规范化列表输入。
     *
     * @param values 原始列表
     * @return 非空列表
     * @param <T> 列表元素类型
     */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 为 null 或空白值提供兜底文本。
     *
     * @param value 原始值
     * @param fallback 兜底值
     * @return 可安全输出的文本
     */
    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 上下文分段候选项。
     *
     * @param key 分段键
     * @param label 分段展示名
     * @param priority 优先级
     * @param content 原始内容
     * @param maxChars 该分段最大内容长度
     * @param required 是否必须保留
     * @param reason 默认原因
     */
    private record SectionCandidate(
        String key,
        String label,
        int priority,
        String content,
        int maxChars,
        boolean required,
        String reason
    ) {
    }
}
