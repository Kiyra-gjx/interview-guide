package interview.guide.modules.resume.model;

/**
 * 简历统计信息 DTO
 */
public record ResumeStatsDTO (
    long totalCount,
    long totalInterviewCount,
    long totalAccessCount
) {
}
