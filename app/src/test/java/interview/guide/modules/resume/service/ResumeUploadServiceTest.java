package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.listener.AnalyzeStreamProducer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ResumeUploadServiceTest {

    @Mock private ResumeParseService parseService;
    @Mock private FileStorageService storageService;
    @Mock private ResumePersistenceService persistenceService;
    @Mock private AppConfigProperties appConfig;
    @Mock private FileValidationService fileValidationService;
    @Mock private AnalyzeStreamProducer analyzeStreamProducer;
    @Mock private ResumeRepository resumeRepository;

    @InjectMocks
    private ResumeUploadService resumeUploadService;

    // ========= 测试数据 ========
    private ResumeEntity failedResume;
    private ResumeEntity completedResume;

    @BeforeEach
    void setUp() {
        // 构造一个"分析失败"的简历实体
        failedResume = new ResumeEntity();
        failedResume.setId(1L);
        failedResume.setOriginalFilename("张三_简历.pdf");
        failedResume.setAnalyzeStatus(AsyncTaskStatus.FAILED);
        failedResume.setAnalyzeError("AI 服务超时");
        failedResume.setAnalyzeErrorCode("AI_SERVICE_TIMEOUT");
        failedResume.setAnalyzeRetryable(false);
        failedResume.setResumeText("姓名：张三\n技能：Java");
        failedResume.setStorageKey("resume/abc123.pdf");
        failedResume.setStorageUrl("http://storage/resume/abc123.pdf");

        // 构建一个"分析完成"的简历实体
        completedResume = new ResumeEntity();
        completedResume.setId(2L);
        completedResume.setOriginalFilename("李四_简历.pdf");
        completedResume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
        completedResume.setStorageKey("resumes/def456.pdf");
        completedResume.setStorageUrl("http://storage/resume/def456.pdf");
    }

    @Test
    @DisplayName("重复上传 FAILED 简历时,应重置状态为 PENDING 并重新发送分析任务")
    void shouldRetriggerAnalysis_whenDuplicateResumeIsFailed() {
        // ===== 准备 =====
        MultipartFile mockFile = new org.springframework.mock.web.MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                "mock content".getBytes()
        );

        // 返回这个 FAILED 的简历
        when(persistenceService.findExistingResume(mockFile))
                .thenReturn(Optional.of(failedResume));

        // 模拟保存成功
        when(resumeRepository.save(any(ResumeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // ===== 执行 =====
        Map<String, Object> result = resumeUploadService.uploadAndAnalyze(mockFile);

        // ===== Assert(断言) =====
        // 1. 验证返回值标记为 duplicate
        assertThat(result.get("duplicate")).isEqualTo(true);

        // 2. 验证简历状态被重置为 PENDING
        ArgumentCaptor<ResumeEntity> captor = ArgumentCaptor.forClass(ResumeEntity.class);
        verify(resumeRepository).save(captor.capture());
        verify(parseService, never()).parseResume(any(MultipartFile.class));
        verify(parseService, never()).downloadAndParseContent(anyString(), anyString());
        ResumeEntity savedResume = captor.getValue();

        assertThat(savedResume.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.PENDING);
        assertThat(savedResume.getAnalyzeError()).isNull();
        assertThat(savedResume.getAnalyzeErrorCode()).isNull();
        assertThat(savedResume.getAnalyzeRetryable()).isNull();

        // 3. 验证重新发送了分析任务
        verify(analyzeStreamProducer).sendAnalyzeTask(
                eq(1L), // resumeId
                eq("姓名：张三\n技能：Java") // resumeText
        );
    }

    @Test
    @DisplayName("重复上传 FAILED 简历时,应重置状态为 PENDING 并重新发送分析任务 -- 当简历文本为空时的处理")
    void shouldDownloadAndParse_whenDuplicateResumeTextIsNull() {
        // ===== 准备 =====
        MultipartFile mockFile = new org.springframework.mock.web.MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                "mock content".getBytes()
        );

        // 设置简历文本为空
        failedResume.setResumeText(null);

        // 返回这个 FAILED 的简历
        when(persistenceService.findExistingResume(mockFile))
                .thenReturn(Optional.of(failedResume));
        when(parseService.downloadAndParseContent(anyString(), anyString()))
                .thenReturn("姓名：张三\n技能：Java");

        // 模拟保存成功
        when(resumeRepository.save(any(ResumeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // ===== 执行 =====
        Map<String, Object> result = resumeUploadService.uploadAndAnalyze(mockFile);

        // ===== Assert(断言) =====
        // 1. 验证返回值标记为 duplicate
        assertThat(result.get("duplicate")).isEqualTo(true);

        // 2. 验证简历状态被重置为 PENDING
        ArgumentCaptor<ResumeEntity> captor = ArgumentCaptor.forClass(ResumeEntity.class);
        verify(resumeRepository).save(captor.capture());
        verify(parseService, never()).parseResume(any(MultipartFile.class));
        verify(parseService).downloadAndParseContent(anyString(), anyString());
        ResumeEntity savedResume = captor.getValue();

        assertThat(savedResume.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.PENDING);
        assertThat(savedResume.getAnalyzeError()).isNull();
        assertThat(savedResume.getAnalyzeErrorCode()).isNull();
        assertThat(savedResume.getAnalyzeRetryable()).isNull();

        // 3. 验证重新发送了分析任务
        verify(analyzeStreamProducer).sendAnalyzeTask(
                eq(1L), // resumeId
                eq("姓名：张三\n技能：Java") // resumeText
        );
    }

    @Test
    @DisplayName("重复上传 COMPLETED 简历时，应返回历史分析结果，不重新触发分析")
    void shouldReturnExistingAnalysis_whenDuplicateResumeIsCompleted() {
        MultipartFile mockFile = mock(MultipartFile.class);

        when(persistenceService.findExistingResume(mockFile))
                .thenReturn(Optional.of(completedResume));

        // 构造一个历史分析结果
        ResumeAnalysisResponse mockAnalysis = new ResumeAnalysisResponse(
                85, null, "整体不错", List.of("Java扎实"), List.of(), "简历原文"
        );
        when(persistenceService.getLatestAnalysisAsDTO(2L))
                .thenReturn(Optional.of(mockAnalysis));

        // ====== ACT ======
        Map<String, Object> result = resumeUploadService.uploadAndAnalyze(mockFile);

        // ====== Assert =====
        // 1. 应该标记为 duplicate
        assertThat(result.get("duplicate")).isEqualTo(true);

        // 2. 应该包含历史分析结果
        assertThat(result.get("analysis")).isNotNull();

        // 3. 关键：不应该重新发送任务
        verify(analyzeStreamProducer, never()).sendAnalyzeTask(anyLong(), anyString());

        // 4. 不应该修改简历状态
        verify(resumeRepository, never()).save(any(ResumeEntity.class));
    }
}
