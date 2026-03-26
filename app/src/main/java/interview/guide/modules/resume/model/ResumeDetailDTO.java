package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历详情 DTO。
 */
public record ResumeDetailDTO(
    Long id,
    String filename,
    Long fileSize,
    String contentType,
    String storageUrl,
    LocalDateTime uploadedAt,
    Integer accessCount,
    String resumeText,
    AsyncTaskStatus analyzeStatus,
    String analyzeError,
    String analyzeErrorCode,
    Boolean analyzeRetryable,
    List<AnalysisHistoryDTO> analyses,
    List<Object> interviews
) {
    /**
     * 分析历史 DTO。
     */
    public record AnalysisHistoryDTO(
        Long id,
        Integer overallScore,
        Integer contentScore,
        Integer structureScore,
        Integer skillMatchScore,
        Integer expressionScore,
        Integer projectScore,
        String summary,
        LocalDateTime analyzedAt,
        List<String> strengths,
        List<Object> suggestions
    ) {
    }
}