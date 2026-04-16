package interview.guide.modules.knowledgebase.model;

/**
 * 带检索调试信息的知识库查询响应。
 */
public record QueryDebugResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    QueryDebugInfo debug
) {
}
