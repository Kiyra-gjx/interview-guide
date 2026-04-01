package interview.guide.modules.resume.service;

import interview.guide.common.ai.AiErrorDescriptor;
import interview.guide.common.ai.AiErrorTranslator;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.AiServiceException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历评分服务。
 * 使用 Spring AI 调用大模型对简历进行评分和建议生成。
 */
@Service
public class ResumeGradingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);

    private final ChatClient chatClient;
    private final ResumeDomainClassificationService resumeDomainClassificationService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final AiErrorTranslator aiErrorTranslator;

    /**
     * 中间 DTO，用于接收 AI 响应。
     */
    private record ResumeAnalysisResponseDTO(
        int overallScore,
        ScoreDetailDTO scoreDetail,
        String summary,
        List<String> strengths,
        List<SuggestionDTO> suggestions
    ) {
    }

    private record ScoreDetailDTO(
        int contentScore,
        int structureScore,
        int skillMatchScore,
        int expressionScore,
        int projectScore
    ) {
    }

    private record SuggestionDTO(
        String category,
        String priority,
        String issue,
        String recommendation
    ) {
    }

    public ResumeGradingService(
        ChatClient.Builder chatClientBuilder,
        AiErrorTranslator aiErrorTranslator,
        StructuredOutputInvoker structuredOutputInvoker,
        ResumeDomainClassificationService resumeDomainClassificationService,
        @Value("classpath:prompts/resume-analysis-system.st") Resource systemPromptResource,
        @Value("classpath:prompts/resume-analysis-user.st") Resource userPromptResource
    ) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.aiErrorTranslator = aiErrorTranslator;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
        this.resumeDomainClassificationService = resumeDomainClassificationService;
    }

    /**
     * 分析简历并返回评分和建议。
     */
    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        log.info("开始分析简历，文本长度: {} 字符", resumeText.length());

        try {
            // 兜底判断简历领域
            ResumeDomain domain = resumeDomainClassificationService.classify(resumeText);

            if (domain == ResumeDomain.OUT_OF_SCOPE) {
                return buildOutOfScopeResponse(resumeText);
            }

            // 返回技术类领域分析结果
            return analyzeInScopeResume(resumeText);
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            AiErrorDescriptor errorDescriptor = aiErrorTranslator.translate(e);
            log.error("简历分析失败: code={}, retryable={}, message={}",
                errorDescriptor.errorCode().getCode(),
                errorDescriptor.retryable(),
                e.getMessage(),
                e);
            throw new AiServiceException(
                errorDescriptor.errorCode(),
                errorDescriptor.userMessage(),
                errorDescriptor.retryable(),
                e
            );
        }
    }

    /**
     * AI 正式评分
     */
    private ResumeAnalysisResponse analyzeInScopeResume(String resumeText) {
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeText);
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        ResumeAnalysisResponseDTO dto = structuredOutputInvoker.invoke(
            chatClient,
            systemPromptWithFormat,
            userPrompt,
            outputConverter,
            ErrorCode.RESUME_ANALYSIS_FAILED,
            "简历分析失败：",
            "简历分析",
            log
        );
        log.debug("AI 响应解析成功: overallScore={}", dto.overallScore());

        ResumeAnalysisResponse result = convertToResponse(dto, resumeText);
        log.info("简历分析完成，总分: {}", result.overallScore());
        return result;
    }

    /**
     * 将 DTO 转换为业务对象。
     */
    private ResumeAnalysisResponse convertToResponse(ResumeAnalysisResponseDTO dto, String originalText) {
        ScoreDetailDTO dtoScoreDetail = dto.scoreDetail();
        ScoreDetail scoreDetail = new ScoreDetail(
            dtoScoreDetail.contentScore(),
            dtoScoreDetail.structureScore(),
            dtoScoreDetail.skillMatchScore(),
            dtoScoreDetail.expressionScore(),
            dtoScoreDetail.projectScore()
        );

        List<Suggestion> suggestions = dto.suggestions().stream()
            .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
            .toList();

        return new ResumeAnalysisResponse(
            dto.overallScore(),
            scoreDetail,
            dto.summary(),
            dto.strengths(),
            suggestions,
            originalText
        );
    }

    /**
     * 构建非计算机领域简历分析结果
     */
    private ResumeAnalysisResponse buildOutOfScopeResponse(String resumeText) {
        ScoreDetail scoreDetail = new ScoreDetail(8, 10, 2, 5, 4);

        return new ResumeAnalysisResponse(
            calculateOverallScore(scoreDetail),
            scoreDetail,
            "该简历明显不属于计算机/技术岗位目标方向，已按领域外规则降级评分，结果仅供参考。",
            List.of("简历具备可读性"),
            List.of(
                new Suggestion(
                    "内容",
                    "高",
                    "当前简历与产品目标岗位方向不匹配",
                    "如果目标不是技术岗位，建议适用对应岗位的专用简历模板和评估标准。"
                )
            ),
            resumeText
        );
    }

    /**
     * 计算简历总评分
     */
    private int calculateOverallScore(ScoreDetail scoreDetail) {
        return scoreDetail.contentScore()
            + scoreDetail.structureScore()
            + scoreDetail.skillMatchScore()
            + scoreDetail.expressionScore()
            + scoreDetail.projectScore();
    }
}
