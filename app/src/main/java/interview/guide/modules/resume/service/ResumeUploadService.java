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
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

/**
 * 简历上传与重新分析服务。
 * 负责文件校验、去重、文本解析、对象存储以及分析任务投递。
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
        // 1. 先做统一的文件大小和基础格式校验，尽量把非法请求挡在最外层。
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        String fileName = file.getOriginalFilename();
        log.info("收到简历上传请求: {}, 大小: {} bytes", fileName, file.getSize());

        // 2. 识别并校验文件类型，避免把不支持的格式送入解析链路。
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);

        // 3. 优先做去重判断，命中历史简历时直接复用已有结果或重投分析任务。
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            ResumeEntity resume = existingResume.get();
            if (resume.getAnalyzeStatus() == AsyncTaskStatus.FAILED) {
                // 3.1 历史任务失败时，重新补齐 resumeText 并再次投递分析。
                String resumeText = getResumeText(resume);

                // 3.2 重新入队后，前端仍然按 PENDING 状态轮询即可。
                analyzeStreamProducer.sendAnalyzeTask(resume.getId(), resumeText);

                // 3.3 返回重复上传但已重试分析的响应。
                return Map.of(
                    "resume", Map.of(
                            "id", resume.getId(),
                            "filename", resume.getOriginalFilename(),
                            "analyzeStatus", AsyncTaskStatus.PENDING.name()
                    ),
                    "storage", Map.of(
                            "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                            "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                            "resumeId", resume.getId()
                    ),
                    "duplicate", true
                );
            }

            return handleDuplicateResume(resume);
        }

        // 4. 新文件走解析、存储、持久化和异步分析的完整链路。
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

    /**
     * 获取可用于分析的简历文本，并在必要时重置分析状态。
     */
    private @NonNull String getResumeText(ResumeEntity resume) {
        String resumeText = resume.getResumeText();

        // 1. 如果库里没有现成文本，就回源对象存储重新解析。
        if (resumeText == null || resumeText.trim().isEmpty()) {
            resumeText = parseService.downloadAndParseContent(
                    resume.getStorageKey(),
                    resume.getOriginalFilename()
            );
            // 1.1 仍然拿不到文本时，说明文件内容本身不可解析。
            if (resumeText == null || resumeText.trim().isEmpty()) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
            resume.setResumeText(resumeText);
        }

        // 2. 重新分析前清空上一轮错误状态，避免前端看到陈旧失败信息。
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        resume.setAnalyzeErrorCode(null);
        resume.setAnalyzeRetryable(null);

        // 3. 保存状态重置结果，保证后续异步消费读取到的是最新状态。
        resumeRepository.save(resume);
        return resumeText;
    }

    /**
     * 校验检测到的内容类型是否在白名单中。
     */
    private void validateContentType(String contentType) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.getAllowedTypes(),
            "不支持的文件类型: " + contentType
        );
    }

    /**
     * 处理重复简历场景。
     * 有历史分析结果时直接返回分析结果，否则返回当前分析状态供前端继续轮询。
     */
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
        // 1. 校验简历存在，并读取当前持久化记录。
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        log.info("开始重新分析简历: resumeId={}, filename={}", resumeId, resume.getOriginalFilename());

        // 2. 重新准备文本并重置状态，然后再次投递异步分析任务。
        String resumeText = getResumeText(resume);

        analyzeStreamProducer.sendAnalyzeTask(resumeId, resumeText);
        log.info("重新分析任务已发送: resumeId={}", resumeId);
    }
}
