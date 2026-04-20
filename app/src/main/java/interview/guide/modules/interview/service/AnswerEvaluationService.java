package interview.guide.modules.interview.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 答案评估服务
 * 评估用户回答并生成面试报告
 */
@Service
public class AnswerEvaluationService {
    
    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);
    
    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<EvaluationReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<FinalSummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;
    
    // 中间DTO用于接收AI响应
    private record EvaluationReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvaluationDTO> questionEvaluations
    ) {}
    
    private record QuestionEvaluationDTO(
        int questionIndex,
        int score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) {}

    private record BatchEvaluationResult(
        int startIndex,
        int endIndex,
        EvaluationReportDTO report
    ) {}

    private record FinalSummaryDTO(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {}
    
    /**
     * 初始化面试评估链路所需的提示词模板、结构化转换器和分批配置。
     */
    public AnswerEvaluationService(
            ChatClient.Builder chatClientBuilder,
            StructuredOutputInvoker structuredOutputInvoker,
            @Value("classpath:prompts/interview-evaluation-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/interview-evaluation-user.st") Resource userPromptResource,
            @Value("classpath:prompts/interview-evaluation-summary-system.st") Resource summarySystemPromptResource,
            @Value("classpath:prompts/interview-evaluation-summary-user.st") Resource summaryUserPromptResource,
            @Value("${app.interview.evaluation.batch-size:8}") int evaluationBatchSize) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(EvaluationReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(summarySystemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.summaryUserPromptTemplate = new PromptTemplate(summaryUserPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.summaryOutputConverter = new BeanOutputConverter<>(FinalSummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationBatchSize);
    }
    
    /**
     * 评估完整面试并生成报告
     */
    public InterviewReportDTO evaluateInterview(String sessionId, String resumeText,
                                                 List<InterviewQuestionDTO> questions) {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());
        
        try {
            // 简历摘要（限制长度）
            // 1. 先裁剪简历内容，避免单次提示词过长影响评估稳定性。
            String resumeSummary = resumeText.length() > 500 
                ? resumeText.substring(0, 500) + "..." 
                : resumeText;

            // 分批评估，避免单次上下文过大导致 token 超限
            // 2. 分批评估所有问答，先得到每一批的原始评分结果。
            List<BatchEvaluationResult> batchResults = evaluateInBatches(sessionId, resumeSummary, questions);

            // 3. 合并批次明细，并准备批次级的兜底反馈、优点和改进项。
            List<QuestionEvaluationDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
            String fallbackOverallFeedback = mergeOverallFeedback(batchResults);
            List<String> fallbackStrengths = mergeListItems(batchResults, true);
            List<String> fallbackImprovements = mergeListItems(batchResults, false);
            // 4. 再做一次总结聚合，让最终报告比简单拼接更连贯。
            FinalSummaryDTO finalSummary = summarizeBatchResults(
                sessionId,
                resumeSummary,
                questions,
                mergedEvaluations,
                fallbackOverallFeedback,
                fallbackStrengths,
                fallbackImprovements
            );

            // 转换为业务对象
            // 5. 最后转换成接口层稳定使用的报告结构。
            return convertToReport(
                sessionId,
                mergedEvaluations,
                questions,
                finalSummary.overallFeedback(),
                finalSummary.strengths(),
                finalSummary.improvements()
            );
            
        } catch (BusinessException e) {
            // 重新抛出业务异常
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, 
                "面试评估失败：" + e.getMessage());
        }
    }
    
    /**
     * 构建问答记录字符串
     */
    private String buildQARecords(List<InterviewQuestionDTO> questions) {
        StringBuilder sb = new StringBuilder();
        for (InterviewQuestionDTO q : questions) {
            sb.append(String.format("问题%d [%s]: %s\n", 
                q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n", 
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }

    /**
     * 按配置批量切分面试题，并逐批调用模型评估，避免上下文窗口过大。
     */
    private List<BatchEvaluationResult> evaluateInBatches(
        String sessionId,
        String resumeSummary,
        List<InterviewQuestionDTO> questions
    ) {
        List<BatchEvaluationResult> results = new ArrayList<>();
        for (int start = 0; start < questions.size(); start += evaluationBatchSize) {
            // 1. 根据批大小截取当前批次的题目范围。
            int end = Math.min(start + evaluationBatchSize, questions.size());
            List<InterviewQuestionDTO> batchQuestions = questions.subList(start, end);
            // 2. 每批都独立生成一份评估结果，便于失败定位和后续聚合。
            EvaluationReportDTO report = evaluateBatch(sessionId, resumeSummary, batchQuestions, start, end);
            results.add(new BatchEvaluationResult(start, end, report));
        }
        return results;
    }

    /**
     * 评估单个批次的问答内容，生成批次级反馈、评分和参考答案。
     */
    private EvaluationReportDTO evaluateBatch(
        String sessionId,
        String resumeSummary,
        List<InterviewQuestionDTO> batchQuestions,
        int start,
        int end
    ) {
        // 1. 把问题和回答展开成稳定文本，便于模型理解当前批次上下文。
        String qaRecords = buildQARecords(batchQuestions);
        String systemPrompt = systemPromptTemplate.render();

        // 2. 组装用户提示词变量，并附加结构化输出格式约束。
        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeSummary);
        variables.put("qaRecords", qaRecords);
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            // 3. 模型调用异常时直接转业务异常，交给上层统一停止评估流程。
            EvaluationReportDTO dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败：",
                "批次评估",
                log
            );
            log.debug("批次评估完成: sessionId={}, range=[{}, {}), batchSize={}",
                sessionId, start, end, batchQuestions.size());
            return dto;
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, range=[{}, {}), error={}",
                sessionId, start, end, e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "面试评估失败：" + e.getMessage());
        }
    }

    /**
     * 按原题目顺序合并批次评估结果，缺失项补 0 分兜底对象。
     */
    private List<QuestionEvaluationDTO> mergeQuestionEvaluations(List<BatchEvaluationResult> batchResults) {
        List<QuestionEvaluationDTO> merged = new ArrayList<>();
        for (BatchEvaluationResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            List<QuestionEvaluationDTO> current =
                result.report() != null && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();
            // 按批次原顺序展开，保证后续和问题列表一一对应。
            for (int i = 0; i < expectedSize; i++) {
                if (i < current.size() && current.get(i) != null) {
                    merged.add(current.get(i));
                } else {
                    // 单题评估缺失时补兜底对象，避免报告转换阶段出现空洞。
                    merged.add(new QuestionEvaluationDTO(
                        result.startIndex() + i,
                        0,
                        "该题未成功生成评估结果，系统按 0 分处理。",
                        "",
                        List.of()
                    ));
                }
            }
        }
        return merged;
    }

    /**
     * 聚合各批次的综合反馈文本；如果批次反馈全部缺失，则返回固定兜底文案。
     */
    private String mergeOverallFeedback(List<BatchEvaluationResult> batchResults) {
        String feedback = batchResults.stream()
            .map(BatchEvaluationResult::report)
            .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
            .map(EvaluationReportDTO::overallFeedback)
            .collect(Collectors.joining("\n\n"));
        if (!feedback.isBlank()) {
            return feedback;
        }
        return "本次面试已完成分批评估，但未生成有效综合评语。";
    }

    /**
     * 聚合同类列表字段，去重后保留有限条目，供最终总结兜底使用。
     */
    private List<String> mergeListItems(List<BatchEvaluationResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchEvaluationResult result : batchResults) {
            EvaluationReportDTO report = result.report();
            if (report == null) {
                continue;
            }
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) {
                continue;
            }
            items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }

    /**
     * 基于批次评估结果再做一次全局总结，失败时退回批次聚合结果。
     */
    private FinalSummaryDTO summarizeBatchResults(
        String sessionId,
        String resumeSummary,
        List<InterviewQuestionDTO> questions,
        List<QuestionEvaluationDTO> evaluations,
        String fallbackOverallFeedback,
        List<String> fallbackStrengths,
        List<String> fallbackImprovements
    ) {
        try {
            // 1. 先把批次结果压缩成分类摘要和题目亮点，作为二次总结输入。
            String summarySystemPrompt = summarySystemPromptTemplate.render();
            Map<String, Object> variables = new HashMap<>();
            variables.put("resumeText", resumeSummary);
            variables.put("categorySummary", buildCategorySummary(questions, evaluations));
            variables.put("questionHighlights", buildQuestionHighlights(questions, evaluations));
            variables.put("fallbackOverallFeedback", fallbackOverallFeedback);
            variables.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            variables.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUserPrompt = summaryUserPromptTemplate.render(variables);

            String systemPromptWithFormat = summarySystemPrompt + "\n\n" + summaryOutputConverter.getFormat();
            // 2. 让模型生成全局总结，再用本地规则清洗列表字段。
            FinalSummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                summaryUserPrompt,
                summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试总结失败：",
                "总结评估",
                log
            );

            String overallFeedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                ? dto.overallFeedback()
                : fallbackOverallFeedback;
            List<String> strengths = sanitizeSummaryItems(
                dto != null ? dto.strengths() : null,
                fallbackStrengths
            );
            List<String> improvements = sanitizeSummaryItems(
                dto != null ? dto.improvements() : null,
                fallbackImprovements
            );

            log.debug("二次汇总评估完成: sessionId={}", sessionId);
            // 3. 文本为空时回退到批次聚合结果，保证最终报告字段完整。
            return new FinalSummaryDTO(overallFeedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
            return new FinalSummaryDTO(
                fallbackOverallFeedback,
                fallbackStrengths,
                fallbackImprovements
            );
        }
    }

    /**
     * 清洗总结类列表字段，优先使用主结果，缺失时回退到兜底结果。
     */
    private List<String> sanitizeSummaryItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .distinct()
            .limit(8)
            .toList();
    }

    /**
     * 生成分类维度的分数摘要，供最终总结提示词使用。
     */
    private String buildCategorySummary(List<InterviewQuestionDTO> questions, List<QuestionEvaluationDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            InterviewQuestionDTO q = questions.get(i);
            QuestionEvaluationDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = 0;
            if (eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                score = eval.score();
            }
            categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }

        return categoryScores.entrySet().stream()
            .map(entry -> {
                int count = entry.getValue().size();
                int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, count);
            })
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    /**
     * 生成题目维度的亮点摘要，压缩每题信息，便于总结合成时快速理解全局表现。
     */
    private String buildQuestionHighlights(List<InterviewQuestionDTO> questions, List<QuestionEvaluationDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            InterviewQuestionDTO q = questions.get(i);
            QuestionEvaluationDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String questionText = q.question() != null ? q.question() : "";
            String shortQuestion = questionText.length() > 50 ? questionText.substring(0, 50) + "..." : questionText;
            String shortFeedback = feedback.length() > 80 ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s",
                q.questionIndex() + 1, shortQuestion, score, shortFeedback));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }
    
    /**
     * 转换DTO为业务对象
     */
    private InterviewReportDTO convertToReport(
        String sessionId,
        List<QuestionEvaluationDTO> evaluations,
        List<InterviewQuestionDTO> questions,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        // 统计实际回答的问题数量
        // 1. 先统计真实作答数量，用于区分“全未作答”的总分场景。
        long answeredCount = questions.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();

        // 处理问题评估（防御性编程：AI 响应解析后可能为 null）
        // 2. 逐题合并评估结果和原始问题，补齐反馈、参考答案与分类得分。
        int evaluationsSize = evaluations != null ? evaluations.size() : 0;
        if (evaluations == null || evaluations.isEmpty()) {
            log.warn("面试评估结果解析异常：问题评估列表为空，sessionId={}", sessionId);
        }
        for (int i = 0; i < questions.size(); i++) {
            QuestionEvaluationDTO eval = i < evaluationsSize ? evaluations.get(i) : null;
            InterviewQuestionDTO q = questions.get(i);
            int qIndex = q.questionIndex();
            String feedback = eval != null && eval.feedback() != null
                ? eval.feedback()
                : "该题未成功生成评估反馈。";
            String referenceAnswer = eval != null && eval.referenceAnswer() != null
                ? eval.referenceAnswer()
                : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                ? eval.keyPoints()
                : List.of();

            // 如果用户未回答该题，分数强制为 0
            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;

            questionDetails.add(new QuestionEvaluation(
                qIndex, q.question(), q.category(),
                q.userAnswer(), score, feedback
            ));

            referenceAnswers.add(new ReferenceAnswer(
                qIndex, q.question(),
                referenceAnswer,
                keyPoints
            ));

            // 收集类别分数
            categoryScoresMap
                .computeIfAbsent(q.category(), k -> new ArrayList<>())
                .add(score);
        }

        // 计算各类别平均分
        // 3. 汇总每个分类的平均分和题目数量，供报告展示。
        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().size()
            ))
            .collect(Collectors.toList());

        // 计算总分：基于实际得分，而非 AI 返回值
        // 如果所有问题都未回答，总分为 0
        // 4. 总分只信任最终落地的题目分数，不直接使用模型返回的 overallScore。
        int overallScore;
        if (answeredCount == 0) {
            overallScore = 0;
        } else {
            // 使用问题详情中的分数计算平均值
            overallScore = (int) questionDetails.stream()
                .mapToInt(QuestionEvaluation::score)
                .average()
                .orElse(0);
        }

        return new InterviewReportDTO(
            sessionId,
            questions.size(),
            overallScore,
            categoryScores,
            questionDetails,
            overallFeedback,
            strengths != null ? strengths : List.of(),
            improvements != null ? improvements : List.of(),
            referenceAnswers
        );
    }
}
