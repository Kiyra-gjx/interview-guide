package interview.guide.modules.agent.tool;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.support.AgentToolContext;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.knowledgebase.model.QueryDebugInfo;
import interview.guide.modules.knowledgebase.model.QueryDebugResponse;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索 Tool。
 * 负责把模型给出的检索请求转成知识库查询，并拆分为回答载荷、调试载荷和 memory 事实。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSearchTool implements AgentTool {

    private static final int ANSWER_LIMIT = 240;

    private final KnowledgeBaseQueryService knowledgeBaseQueryService;

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public String description() {
        return "根据 knowledgeBaseIds 和 question 检索知识库，返回答案与检索调试信息。输入: { knowledgeBaseIds, question }";
    }

    @Override
    public List<String> requiredInputs() {
        return List.of("knowledgeBaseIds", "question");
    }

    @Override
    public AgentToolRiskLevel riskLevel() {
        return AgentToolRiskLevel.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        // 1. 优先使用模型显式给出的知识库 ID；缺失时回退到会话绑定的知识库。
        List<Long> knowledgeBaseIds = readLongList(input.get("knowledgeBaseIds"));
        if (knowledgeBaseIds.isEmpty() && context.knowledgeBaseIds() != null) {
            knowledgeBaseIds = context.knowledgeBaseIds();
        }

        // 2. question 同样允许从最新用户消息兜底，减少模型只选工具但漏传问题时的失败率。
        String question = readString(input.get("question"));
        if (question.isBlank()) {
            question = context.latestUserMessage();
        }

        // 3. 真正执行前再做必填校验，保证错误可以被 orchestrator 统一记录到 trace。
        if (knowledgeBaseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_INVALID_INPUT, "search_knowledge_base 缺少 knowledgeBaseIds");
        }
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.AGENT_INVALID_INPUT, "search_knowledge_base 缺少 question");
        }

        QueryDebugResponse response = knowledgeBaseQueryService.queryKnowledgeBaseWithDebug(
            new QueryRequest(knowledgeBaseIds, question)
        );
        QueryDebugInfo debug = response.debug();

        // 4. answerPayload 面向最终回答，只放用户可见、可引用的检索结论。
        Map<String, Object> answerPayload = new LinkedHashMap<>();
        answerPayload.put("knowledgeBaseIds", knowledgeBaseIds);
        answerPayload.put("knowledgeBaseName", response.knowledgeBaseName());
        answerPayload.put("question", question);
        answerPayload.put("answer", response.answer());

        // 5. debugPayload 面向工作台排障，保留检索 query、命中数和候选片段。
        Map<String, Object> debugPayload = new LinkedHashMap<>();
        debugPayload.put("retrievalQuery", debug == null ? null : debug.retrievalQuery());
        debugPayload.put("effectiveHit", debug != null && debug.effectiveHit());
        debugPayload.put("hitCount", debug == null ? 0 : debug.hitCount());
        debugPayload.put("hits", debug == null ? List.of() : debug.hits());

        // 6. facts 只提炼跨轮有价值的轻量事实，避免把完整知识库答案塞回 memory。
        List<String> facts = new ArrayList<>();
        facts.add("知识库检索结论: " + preview(response.answer()));
        if (debug != null) {
            facts.add("知识库命中数: " + debug.hitCount());
        }

        int hitCount = debug == null ? 0 : debug.hitCount();
        return new AgentToolResult(
            "已完成知识库检索，命中 " + hitCount + " 条候选片段。",
            answerPayload,
            debugPayload,
            facts
        );
    }

    private List<Long> readLongList(Object value) {
        if (value instanceof List<?> list) {
            List<Long> result = new ArrayList<>();
            for (Object item : list) {
                Long parsed = readLong(item);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
            return result;
        }
        if (value instanceof String str && !str.isBlank()) {
            String[] parts = str.split(",");
            List<Long> result = new ArrayList<>();
            for (String part : parts) {
                Long parsed = readLong(part);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
            return result;
        }
        return List.of();
    }

    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String readString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String preview(String answer) {
        String normalized = answer == null ? "" : answer.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= ANSWER_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, ANSWER_LIMIT) + "...";
    }
}
