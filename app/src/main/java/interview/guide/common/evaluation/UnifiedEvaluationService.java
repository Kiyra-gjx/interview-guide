package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified interview evaluation pipeline: batch scoring, local merge, and final summary synthesis.
 */
@Service
@EnableConfigurationProperties(InterviewEvaluationProperties.class)
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    private static final String BATCH_CONTEXT = "批次评估";
    private static final String SUMMARY_CONTEXT = "总结评估";
    private static final String DEFAULT_BATCH_FAILURE_FEEDBACK = "该题未成功生成评估结果，系统按 0 分处理。";
    private static final String DEFAULT_OVERALL_FEEDBACK = "本次面试已完成分批评估，但未生成有效综合评语。";
    private static final String EMPTY_REFERENCE_CONTEXT = "无额外参考基线。";

    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewEvaluationProperties properties;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;

    public UnifiedEvaluationService(
        StructuredOutputInvoker structuredOutputInvoker,
        InterviewEvaluationProperties properties
    ) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.properties = properties;
        this.systemPromptTemplate = readPrompt(properties.getSystemPromptPath());
        this.userPromptTemplate = readPrompt(properties.getUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = readPrompt(properties.getSummarySystemPromptPath());
        this.summaryUserPromptTemplate = readPrompt(properties.getSummaryUserPromptPath());
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
    }

    public EvaluationReport evaluate(
        ChatClient chatClient,
        String sessionId,
        List<QaRecord> qaRecords,
        String resumeText
    ) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    public EvaluationReport evaluate(
        ChatClient chatClient,
        String sessionId,
        List<QaRecord> qaRecords,
        String resumeText,
        String referenceContext
    ) {
        if (chatClient == null) {
            throw new IllegalArgumentException("chatClient must not be null");
        }

        List<QaRecord> normalizedRecords = normalizeRecords(qaRecords);
        String resumeContext = truncate(resumeText, properties.normalizedResumeMaxChars());
        String normalizedReferenceContext = normalizeReferenceContext(referenceContext);
        if (normalizedRecords.isEmpty()) {
            return convertToReport(
                sessionId,
                normalizedRecords,
                List.of(),
                DEFAULT_OVERALL_FEEDBACK,
                List.of(),
                List.of()
            );
        }

        List<BatchResult> batchResults = evaluateInBatches(
            chatClient,
            sessionId,
            resumeContext,
            normalizedRecords,
            normalizedReferenceContext
        );
        ensureAtLeastOneBatchSucceeded(sessionId, batchResults, normalizedRecords);

        List<QuestionEvaluationDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults, normalizedRecords);
        String fallbackOverallFeedback = mergeOverallFeedback(batchResults);
        List<String> fallbackStrengths = mergeListItems(batchResults, true);
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        SummaryDTO finalSummary = summarizeBatchResults(
            chatClient,
            sessionId,
            resumeContext,
            normalizedReferenceContext,
            normalizedRecords,
            mergedEvaluations,
            fallbackOverallFeedback,
            fallbackStrengths,
            fallbackImprovements
        );

        return convertToReport(
            sessionId,
            normalizedRecords,
            mergedEvaluations,
            finalSummary.overallFeedback(),
            finalSummary.strengths(),
            finalSummary.improvements()
        );
    }

    private PromptTemplate readPrompt(Resource resource) throws IOException {
        if (resource == null) {
            throw new IllegalArgumentException("Evaluation prompt resource must be configured");
        }
        return new PromptTemplate(resource.getContentAsString(StandardCharsets.UTF_8));
    }

    private List<BatchResult> evaluateInBatches(
        ChatClient chatClient,
        String sessionId,
        String resumeContext,
        List<QaRecord> qaRecords,
        String referenceContext
    ) {
        List<BatchResult> results = new ArrayList<>();
        int batchSize = properties.normalizedBatchSize();
        for (int start = 0; start < qaRecords.size(); start += batchSize) {
            int end = Math.min(start + batchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            BatchReportDTO report = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, batch, start, end);
            results.add(new BatchResult(start, end, report));
        }
        return results;
    }

    private void ensureAtLeastOneBatchSucceeded(
        String sessionId,
        List<BatchResult> batchResults,
        List<QaRecord> qaRecords
    ) {
        if (qaRecords.isEmpty()) {
            return;
        }
        boolean hasSuccessfulBatch = batchResults.stream().anyMatch(result -> result.report() != null);
        if (!hasSuccessfulBatch) {
            log.error("所有批次评估均失败，终止本次评估: sessionId={}, totalQuestions={}", sessionId, qaRecords.size());
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "所有批次评估均失败");
        }
    }

    private BatchReportDTO evaluateBatch(
        ChatClient chatClient,
        String sessionId,
        String resumeContext,
        String referenceContext,
        List<QaRecord> batchRecords,
        int start,
        int end
    ) {
        String qaRecords = buildQARecords(batchRecords);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("sessionId", nullToEmpty(sessionId));
        variables.put("resumeText", resumeContext);
        variables.put("referenceContext", referenceContext);
        variables.put("qaRecords", qaRecords);
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            BatchReportDTO dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败：",
                BATCH_CONTEXT,
                log
            );
            log.debug("批次评估完成: sessionId={}, range=[{}, {}), batchSize={}",
                sessionId, start, end, batchRecords.size());
            return dto;
        } catch (Exception e) {
            log.error("批次评估失败，降级为零分批次: sessionId={}, range=[{}, {}), error={}",
                sessionId, start, end, e.getMessage(), e);
            return null;
        }
    }

    private String buildQARecords(List<QaRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord record : records) {
            sb.append(String.format(
                "问题%d [%s]: %s%n",
                record.questionIndex() + 1,
                record.category(),
                record.question()
            ));
            sb.append(String.format(
                "回答: %s%n%n",
                hasText(record.userAnswer()) ? record.userAnswer() : "(未回答)"
            ));
        }
        return sb.toString();
    }

    private List<QuestionEvaluationDTO> mergeQuestionEvaluations(
        List<BatchResult> batchResults,
        List<QaRecord> qaRecords
    ) {
        List<QuestionEvaluationDTO> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            List<QuestionEvaluationDTO> current =
                result.report() != null && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();
            Map<Integer, QuestionEvaluationDTO> evaluationsByQuestionIndex = current.stream()
                .filter(evaluation -> evaluation != null && evaluation.questionIndex() >= 0)
                .collect(Collectors.toMap(
                    QuestionEvaluationDTO::questionIndex,
                    evaluation -> evaluation,
                    (first, ignored) -> first,
                    LinkedHashMap::new
                ));
            for (int i = result.startIndex(); i < result.endIndex(); i++) {
                int relativeIndex = i - result.startIndex();
                QaRecord record = qaRecords.get(i);
                QuestionEvaluationDTO evaluation = evaluationsByQuestionIndex.get(record.questionIndex());
                if (evaluation == null) {
                    evaluation = relativeIndex < current.size() ? current.get(relativeIndex) : null;
                }
                merged.add(normalizeQuestionEvaluation(record, evaluation));
            }
        }
        return merged;
    }

    private QuestionEvaluationDTO normalizeQuestionEvaluation(QaRecord record, QuestionEvaluationDTO evaluation) {
        if (evaluation == null) {
            return new QuestionEvaluationDTO(
                record.questionIndex(),
                0,
                DEFAULT_BATCH_FAILURE_FEEDBACK,
                "",
                List.of()
            );
        }
        return new QuestionEvaluationDTO(
            record.questionIndex(),
            clampScore(evaluation.score()),
            hasText(evaluation.feedback()) ? evaluation.feedback().trim() : DEFAULT_BATCH_FAILURE_FEEDBACK,
            nullToEmpty(evaluation.referenceAnswer()),
            sanitizeTextList(evaluation.keyPoints(), properties.normalizedListItemLimit())
        );
    }

    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        String feedback = batchResults.stream()
            .map(BatchResult::report)
            .filter(report -> report != null && hasText(report.overallFeedback()))
            .map(report -> report.overallFeedback().trim())
            .collect(Collectors.joining("\n\n"));
        return hasText(feedback) ? feedback : DEFAULT_OVERALL_FEEDBACK;
    }

    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            BatchReportDTO report = result.report();
            if (report == null) {
                continue;
            }
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            for (String item : sanitizeTextList(items, properties.normalizedListItemLimit())) {
                merged.add(item);
            }
        }
        return merged.stream().limit(properties.normalizedListItemLimit()).toList();
    }

    private SummaryDTO summarizeBatchResults(
        ChatClient chatClient,
        String sessionId,
        String resumeContext,
        String referenceContext,
        List<QaRecord> qaRecords,
        List<QuestionEvaluationDTO> evaluations,
        String fallbackOverallFeedback,
        List<String> fallbackStrengths,
        List<String> fallbackImprovements
    ) {
        try {
            String summarySystemPrompt = summarySystemPromptTemplate.render();
            Map<String, Object> variables = new HashMap<>();
            variables.put("resumeText", resumeContext);
            variables.put("referenceContext", referenceContext);
            variables.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            variables.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            variables.put("fallbackOverallFeedback", fallbackOverallFeedback);
            variables.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            variables.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUserPrompt = summaryUserPromptTemplate.render(variables);

            String systemPromptWithFormat = summarySystemPrompt + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                summaryUserPrompt,
                summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试总结失败：",
                SUMMARY_CONTEXT,
                log
            );

            String overallFeedback = dto != null && hasText(dto.overallFeedback())
                ? dto.overallFeedback().trim()
                : fallbackOverallFeedback;
            List<String> strengths = sanitizeSummaryItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeSummaryItems(dto != null ? dto.improvements() : null, fallbackImprovements);

            log.debug("二次汇总评估完成: sessionId={}", sessionId);
            return new SummaryDTO(overallFeedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
            return new SummaryDTO(fallbackOverallFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    private List<String> sanitizeSummaryItems(List<String> primary, List<String> fallback) {
        List<String> primaryItems = sanitizeTextList(primary, properties.normalizedListItemLimit());
        if (!primaryItems.isEmpty()) {
            return primaryItems;
        }
        return sanitizeTextList(fallback, properties.normalizedListItemLimit());
    }

    private String buildCategorySummary(List<QaRecord> records, List<QuestionEvaluationDTO> evaluations) {
        Map<String, List<Integer>> scoresByCategory = new LinkedHashMap<>();
        for (int i = 0; i < records.size(); i++) {
            QaRecord record = records.get(i);
            QuestionEvaluationDTO evaluation = i < evaluations.size() ? evaluations.get(i) : null;
            int score = hasText(record.userAnswer()) && evaluation != null ? clampScore(evaluation.score()) : 0;
            scoresByCategory.computeIfAbsent(record.category(), ignored -> new ArrayList<>()).add(score);
        }

        return scoresByCategory.entrySet().stream()
            .map(entry -> {
                int count = entry.getValue().size();
                int average = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), average, count);
            })
            .collect(Collectors.joining("\n"));
    }

    private String buildQuestionHighlights(List<QaRecord> records, List<QuestionEvaluationDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        int limit = Math.min(records.size(), properties.normalizedQuestionHighlightsLimit());
        for (int i = 0; i < limit; i++) {
            QaRecord record = records.get(i);
            QuestionEvaluationDTO evaluation = i < evaluations.size() ? evaluations.get(i) : null;
            int score = hasText(record.userAnswer()) && evaluation != null ? clampScore(evaluation.score()) : 0;
            String feedback = evaluation != null ? nullToEmpty(evaluation.feedback()) : "";
            highlights.add(String.format(
                "- Q%d | %s | 分数:%d | 反馈:%s",
                record.questionIndex() + 1,
                abbreviate(record.question(), 50),
                score,
                abbreviate(feedback, 80)
            ));
        }
        return String.join("\n", highlights);
    }

    private EvaluationReport convertToReport(
        String sessionId,
        List<QaRecord> records,
        List<QuestionEvaluationDTO> evaluations,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {
        List<EvaluationReport.QuestionEvaluation> questionEvaluations = new ArrayList<>();
        List<EvaluationReport.ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> scoresByCategory = new LinkedHashMap<>();

        for (int i = 0; i < records.size(); i++) {
            QaRecord record = records.get(i);
            QuestionEvaluationDTO evaluation = i < evaluations.size() ? evaluations.get(i) : null;
            boolean hasAnswer = hasText(record.userAnswer());
            int score = hasAnswer && evaluation != null ? clampScore(evaluation.score()) : 0;
            String feedback = evaluation != null && hasText(evaluation.feedback())
                ? evaluation.feedback().trim()
                : DEFAULT_BATCH_FAILURE_FEEDBACK;
            String referenceAnswer = evaluation != null ? nullToEmpty(evaluation.referenceAnswer()) : "";
            List<String> keyPoints = evaluation != null
                ? sanitizeTextList(evaluation.keyPoints(), properties.normalizedListItemLimit())
                : List.of();

            questionEvaluations.add(new EvaluationReport.QuestionEvaluation(
                record.questionIndex(),
                record.question(),
                record.category(),
                record.userAnswer(),
                score,
                feedback
            ));
            referenceAnswers.add(new EvaluationReport.ReferenceAnswer(
                record.questionIndex(),
                record.question(),
                referenceAnswer,
                keyPoints
            ));
            scoresByCategory.computeIfAbsent(record.category(), ignored -> new ArrayList<>()).add(score);
        }

        List<EvaluationReport.CategoryScore> categoryScores = scoresByCategory.entrySet().stream()
            .map(entry -> new EvaluationReport.CategoryScore(
                entry.getKey(),
                (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                entry.getValue().size()
            ))
            .toList();

        int overallScore = questionEvaluations.stream()
            .mapToInt(EvaluationReport.QuestionEvaluation::score)
            .average()
            .stream()
            .mapToInt(value -> (int) value)
            .findFirst()
            .orElse(0);

        return new EvaluationReport(
            sessionId,
            records.size(),
            overallScore,
            categoryScores,
            questionEvaluations,
            hasText(overallFeedback) ? overallFeedback : DEFAULT_OVERALL_FEEDBACK,
            sanitizeTextList(strengths, properties.normalizedListItemLimit()),
            sanitizeTextList(improvements, properties.normalizedListItemLimit()),
            referenceAnswers
        );
    }

    private List<QaRecord> normalizeRecords(List<QaRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<QaRecord> normalized = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            QaRecord record = records.get(i);
            if (record == null) {
                normalized.add(new QaRecord(i, "", "", null));
                continue;
            }
            int questionIndex = record.questionIndex() >= 0 ? record.questionIndex() : i;
            normalized.add(new QaRecord(questionIndex, record.question(), record.category(), record.userAnswer()));
        }
        return List.copyOf(normalized);
    }

    private String normalizeReferenceContext(String referenceContext) {
        if (!hasText(referenceContext)) {
            return EMPTY_REFERENCE_CONTEXT;
        }
        return truncate(referenceContext, properties.normalizedReferenceContextMaxChars());
    }

    private String truncate(String text, int maxChars) {
        String value = nullToEmpty(text);
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private String abbreviate(String text, int maxChars) {
        String value = nullToEmpty(text);
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)) + "...";
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private List<String> sanitizeTextList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(this::hasText)
            .map(String::trim)
            .distinct()
            .limit(Math.max(1, limit))
            .toList();
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private record BatchReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvaluationDTO> questionEvaluations
    ) {
    }

    private record QuestionEvaluationDTO(
        int questionIndex,
        int score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) {
    }

    private record BatchResult(
        int startIndex,
        int endIndex,
        BatchReportDTO report
    ) {
    }

    private record SummaryDTO(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {
        private SummaryDTO {
            overallFeedback = overallFeedback == null ? "" : overallFeedback;
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            improvements = improvements == null ? List.of() : List.copyOf(improvements);
        }
    }
}
