package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.listener.AnalyzeStreamProducer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

/**
 * 简历上传服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    private final ResumeParseService parseService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    private final FileValidationService fileValidationService;
    private final AnalyzeStreamProducer analyzeStreamProducer;
    private final ResumeRepository resumeRepository;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * 上传并分析简历。
     */
    public Map<String, Object> uploadAndAnalyze(MultipartFile file) {
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        String fileName = file.getOriginalFilename();
        log.info("收到简历上传请求: {}, 大小: {} bytes", fileName, file.getSize());

        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);

        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            return handleDuplicateResume(existingResume.get());
        }

        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确认文件不是扫描版 PDF");
        }

        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到 RustFS: {}", fileKey);

        ResumeEntity savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl);
        analyzeStreamProducer.sendAnalyzeTask(savedResume.getId(), resumeText);

        log.info("简历上传完成，分析任务已入队: {}, resumeId={}", fileName, savedResume.getId());

        return Map.of(
            "resume", Map.of(
                "id", savedResume.getId(),
                "filename", savedResume.getOriginalFilename(),
                "analyzeStatus", AsyncTaskStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl,
                "resumeId", savedResume.getId()
            ),
            "duplicate", false
        );
    }

    private void validateContentType(String contentType) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.getAllowedTypes(),
            "不支持的文件类型: " + contentType
        );
    }

    private Map<String, Object> handleDuplicateResume(ResumeEntity resume) {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.getId());

        Optional<ResumeAnalysisResponse> analysisOpt = persistenceService.getLatestAnalysisAsDTO(resume.getId());
        return analysisOpt.map(resumeAnalysisResponse -> Map.of(
            "analysis", resumeAnalysisResponse,
            "storage", Map.of(
                "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                "resumeId", resume.getId()
            ),
            "duplicate", true
        )).orElseGet(() -> Map.of(
            "resume", Map.of(
                "id", resume.getId(),
                "filename", resume.getOriginalFilename(),
                "analyzeStatus", resume.getAnalyzeStatus() != null ? resume.getAnalyzeStatus().name() : AsyncTaskStatus.PENDING.name(),
                "analyzeError", resume.getAnalyzeError(),
                "analyzeErrorCode", resume.getAnalyzeErrorCode(),
                "analyzeRetryable", resume.getAnalyzeRetryable()
            ),
            "storage", Map.of(
                "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                "resumeId", resume.getId()
            ),
            "duplicate", true
        ));
    }

    /**
     * 重新分析简历。
     */
    @Transactional
    public void reanalyze(Long resumeId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        log.info("开始重新分析简历: resumeId={}, filename={}", resumeId, resume.getOriginalFilename());

        String resumeText = resume.getResumeText();
        if (resumeText == null || resumeText.trim().isEmpty()) {
            resumeText = parseService.downloadAndParseContent(resume.getStorageKey(), resume.getOriginalFilename());
            if (resumeText == null || resumeText.trim().isEmpty()) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
            resume.setResumeText(resumeText);
        }

        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        resume.setAnalyzeErrorCode(null);
        resume.setAnalyzeRetryable(null);
        resumeRepository.save(resume);

        analyzeStreamProducer.sendAnalyzeTask(resumeId, resumeText);
        log.info("重新分析任务已发送: resumeId={}", resumeId);
    }
}