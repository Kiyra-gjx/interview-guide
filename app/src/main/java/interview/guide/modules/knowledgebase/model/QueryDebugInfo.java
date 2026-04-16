package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 检索调试信息。
 */
public record QueryDebugInfo(
    List<Long> knowledgeBaseIds,
    String originalQuestion,
    String rewrittenQuestion,
    List<String> candidateQueries,
    List<Candidate> candidates,
    String retrievalQuery,
    int topK,
    double minScore,
    int hitCount,
    boolean effectiveHit,
    List<Hit> hits
) {

    /**
     * 单个命中文档的调试视图。
     */
    public record Hit(
        String knowledgeBaseId,
        String preview
    ) {
    }

    /**
     * 单个候选检索语句的调试视图。
     */
    public record Candidate(
        String query,
        int rawHitCount,
        boolean effectiveHit,
        String rejectionReason,
        List<Hit> hits
    ) {
    }
}
