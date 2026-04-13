package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * 简历历史服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeHistoryService {

    private final ResumePersistenceService resumePersistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final InterviewMapper interviewMapper;

    /**
     * 获取所有简历列表。
     */
    public List<ResumeListItemDTO> getAllResumes() {
        List<ResumeEntity> resumes = resumePersistenceService.findAllResumes();

        return resumes.stream().map(resume -> {
            Integer latestScore = null;
            java.time.LocalDateTime lastAnalyzedAt = null;
            Optional<ResumeAnalysisEntity> analysisOpt = resumePersistenceService.getLatestAnalysis(resume.getId());
            if (analysisOpt.isPresent()) {
                ResumeAnalysisEntity analysis = analysisOpt.get();
                latestScore = analysis.getOverallScore();
                lastAnalyzedAt = analysis.getAnalyzedAt();
            }

            int interviewCount = interviewPersistenceService.findByResumeId(resume.getId()).size();
            return resumeMapper.toListItemDTO(resume, latestScore, lastAnalyzedAt, interviewCount);
        }).toList();
    }

    /**
     * 获取简历详情。
     */
    public ResumeDetailDTO getResumeDetail(Long id) {
        Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(id);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();
        List<ResumeAnalysisEntity> analyses = resumePersistenceService.findAnalysesByResumeId(id);
        List<ResumeDetailDTO.AnalysisHistoryDTO> analysisHistory = resumeMapper.toAnalysisHistoryDTOList(
            analyses,
            this::extractStrengths,
            this::extractSuggestions
        );

        List<Object> interviewHistory = interviewMapper.toInterviewHistoryList(
            interviewPersistenceService.findByResumeId(id)
        );

        return new ResumeDetailDTO(
            resume.getId(),
            resume.getOriginalFilename(),
            resume.getFileSize(),
            resume.getContentType(),
            resume.getStorageUrl(),
            resume.getUploadedAt(),
            resume.getAccessCount(),
            resume.getResumeText(),
            resume.getAnalyzeStatus(),
            resume.getAnalyzeError(),
            resume.getAnalyzeErrorCode(),
            resume.getAnalyzeRetryable(),
            analysisHistory,
            interviewHistory
        );
    }

    private List<String> extractStrengths(ResumeAnalysisEntity entity) {
        try {
            if (entity.getStrengthsJson() != null) {
                return objectMapper.readValue(entity.getStrengthsJson(), new TypeReference<>() {
                });
            }
        } catch (JacksonException e) {
            log.error("解析 strengths JSON 失败", e);
        }
        return List.of();
    }

    private List<ResumeAnalysisResponse.Suggestion> extractSuggestions(ResumeAnalysisEntity entity) {
        try {
            if (entity.getSuggestionsJson() != null) {
                return objectMapper.readValue(entity.getSuggestionsJson(), new TypeReference<>() {
                });
            }
        } catch (JacksonException e) {
            log.error("解析 suggestions JSON 失败", e);
        }
        return List.of();
    }

    /**
     * 导出简历分析报告为 PDF。
     */
    public ExportResult exportAnalysisPdf(Long resumeId) {
        Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(resumeId);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();
        Optional<ResumeAnalysisResponse> analysisOpt = resumePersistenceService.getLatestAnalysisAsDTO(resumeId);
        if (analysisOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND);
        }

        try {
            byte[] pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysisOpt.get());
            String filename = "简历分析报告_" + resume.getOriginalFilename() + ".pdf";
            return new ExportResult(pdfBytes, filename);
        } catch (Exception e) {
            log.error("导出 PDF 失败: resumeId={}", resumeId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出 PDF 失败: " + e.getMessage());
        }
    }

    /**
     * PDF 导出结果。
     */
    public record ExportResult(byte[] pdfBytes, String filename) {
    }
}