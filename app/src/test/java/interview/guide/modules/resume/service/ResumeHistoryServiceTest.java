package interview.guide.modules.resume.service;

import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.infrastructure.mapper.ResumeMapperImpl;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ResumeHistoryServiceTest {
    @Mock
    private ResumePersistenceService resumePersistenceService;
    @Mock
    private InterviewPersistenceService interviewPersistenceService;
    @Mock
    private PdfExportService pdfExportService;
    @Mock
    private InterviewMapper interviewMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResumeMapper resumeMapper = new ResumeMapperImpl();

    private ResumeHistoryService resumeHistoryService;

    @BeforeEach
    void setUp() {
        resumeHistoryService = new ResumeHistoryService(
            resumePersistenceService,
            interviewPersistenceService,
            pdfExportService,
            objectMapper,
            resumeMapper,
            interviewMapper
        );
    }

    @Test
    @DisplayName("获取简历详情时应将 suggestionsJson 反序列化为强建议类型")
    void shouldDeserializeSuggestionsJsonToTypedSuggestions_whenGetResumeDetail() {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(1L);
        resume.setOriginalFilename("test.pdf");
        resume.setFileSize(1024L);
        resume.setContentType("application/pdf");
        resume.setStorageUrl("http://test");
        resume.setResumeText("Java resume");

        ResumeAnalysisEntity analysis = new ResumeAnalysisEntity();
        analysis.setId(10L);
        analysis.setOverallScore(85);
        analysis.setContentScore(20);
        analysis.setStructureScore(15);
        analysis.setSkillMatchScore(22);
        analysis.setExpressionScore(13);
        analysis.setProjectScore(15);
        analysis.setSummary("总结");
        analysis.setAnalyzedAt(LocalDateTime.of(2026, 4, 13, 10, 0));
        analysis.setStrengthsJson("[\"基础扎实\"]");
        analysis.setSuggestionsJson("""
            [
              {
                "category": "项目",
                "priority": "高",
                "issue": "项目描述过于简单",
                "recommendation": "补充项目背景、职责和结果"
              }
            ]
            """);

        when(resumePersistenceService.findById(1L)).thenReturn(Optional.of(resume));
        when(resumePersistenceService.findAnalysesByResumeId(1L)).thenReturn(List.of(analysis));
        when(interviewPersistenceService.findByResumeId(1L)).thenReturn(List.of());
        when(interviewMapper.toInterviewHistoryList(List.of())).thenReturn(List.of());

        ResumeDetailDTO detailDTO = resumeHistoryService.getResumeDetail(1L);

        assertEquals(1, detailDTO.analyses().size());

        ResumeDetailDTO.AnalysisHistoryDTO historyDTO = detailDTO.analyses().get(0);
        assertEquals(1, historyDTO.suggestions().size());

        ResumeAnalysisResponse.Suggestion suggestion = historyDTO.suggestions().get(0);
        assertEquals("项目", suggestion.category());
        assertEquals("高", suggestion.priority());
        assertEquals("项目描述过于简单", suggestion.issue());
        assertEquals("补充项目背景、职责和结果", suggestion.recommendation());
    }
}
