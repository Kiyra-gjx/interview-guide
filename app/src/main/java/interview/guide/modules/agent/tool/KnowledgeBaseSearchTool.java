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
    public AgentToolResult execute(Map<String, Object> input, AgentToolContext context) {
        List<Long> knowledgeBaseIds = readLongList(input.get("knowledgeBaseIds"));
        if (knowledgeBaseIds.isEmpty() && context.knowledgeBaseIds() != null) {
            knowledgeBaseIds = context.knowledgeBaseIds();
        }
        String question = readString(input.get("question"));
        if (question.isBlank()) {
            question = context.latestUserMessage();
        }

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

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("knowledgeBaseIds", knowledgeBaseIds);
        output.put("knowledgeBaseName", response.knowledgeBaseName());
        output.put("answer", response.answer());
        output.put("retrievalQuery", debug == null ? null : debug.retrievalQuery());
        output.put("effectiveHit", debug != null && debug.effectiveHit());
        output.put("hitCount", debug == null ? 0 : debug.hitCount());
        output.put("hits", debug == null ? List.of() : debug.hits());

        List<String> facts = new ArrayList<>();
        facts.add("知识库检索结论: " + preview(response.answer()));
        if (debug != null) {
            facts.add("知识库命中数: " + debug.hitCount());
        }

        int hitCount = debug == null ? 0 : debug.hitCount();
        return new AgentToolResult(
            "已完成知识库检索，命中 " + hitCount + " 条候选片段。",
            output,
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
