package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedEvaluationServiceTest {

    private static final String BATCH_CONTEXT = "批次评估";
    private static final String SUMMARY_CONTEXT = "总结评估";

    @Mock
    private ChatClient chatClient;
    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    private UnifiedEvaluationService service;

    @BeforeEach
    void setUp() throws IOException {
        service = createService(5);
    }

    @Test
    @DisplayName("should evaluate in batches and use summary output")
    void shouldEvaluateInBatchesAndUseSummaryOutput() throws Exception {
        UnifiedEvaluationService batchedService = createService(2);
        List<QaRecord> records = List.of(
            record(0, "answer-1"),
            record(1, "answer-2"),
            record(2, "answer-3")
        );
        AtomicInteger batchInvocationCount = new AtomicInteger();

        whenInvoke().thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return switch (batchInvocationCount.getAndIncrement()) {
                    case 0 -> createBatchEvaluationResult(
                        "batch-1",
                        List.of("strength-1"),
                        List.of("improvement-1"),
                        List.of(
                            createQuestionEvaluation(0, 80, "feedback-1"),
                            createQuestionEvaluation(1, 60, "feedback-2")
                        )
                    );
                    case 1 -> createBatchEvaluationResult(
                        "batch-2",
                        List.of("strength-2"),
                        List.of("improvement-2"),
                        List.of(createQuestionEvaluation(2, 40, "feedback-3"))
                    );
                    default -> throw new IllegalStateException("unexpected batch");
                };
            }
            if (SUMMARY_CONTEXT.equals(logContext)) {
                return createSummaryResult(
                    "summary-feedback",
                    List.of("summary-strength"),
                    List.of("summary-improvement")
                );
            }
            throw new IllegalStateException("Unexpected logContext: " + logContext);
        });

        EvaluationReport report = batchedService.evaluate(chatClient, "session-1", records, "resume", "reference");

        assertThat(report.questionEvaluations()).extracting(EvaluationReport.QuestionEvaluation::score)
            .containsExactly(80, 60, 40);
        assertThat(report.overallScore()).isEqualTo(60);
        assertThat(report.overallFeedback()).isEqualTo("summary-feedback");
        assertThat(report.strengths()).containsExactly("summary-strength");
        assertThat(report.improvements()).containsExactly("summary-improvement");

        verify(structuredOutputInvoker, times(2)).invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            eq(BATCH_CONTEXT),
            any()
        );
        verify(structuredOutputInvoker, times(1)).invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            eq(SUMMARY_CONTEXT),
            any()
        );
    }

    @Test
    @DisplayName("should use zero-score placeholders when one batch fails")
    void shouldUseZeroScorePlaceholdersWhenOneBatchFails() throws Exception {
        UnifiedEvaluationService batchedService = createService(2);
        List<QaRecord> records = List.of(
            record(0, "answer-1"),
            record(1, "answer-2"),
            record(2, "answer-3")
        );
        AtomicInteger batchInvocationCount = new AtomicInteger();

        whenInvoke().thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                if (batchInvocationCount.getAndIncrement() == 0) {
                    throw new RuntimeException("batch failed");
                }
                return createBatchEvaluationResult(
                    "batch-2",
                    List.of("strength-2"),
                    List.of("improvement-2"),
                    List.of(createQuestionEvaluation(2, 90, "feedback-3"))
                );
            }
            if (SUMMARY_CONTEXT.equals(logContext)) {
                return createSummaryResult(
                    "summary-feedback",
                    List.of("summary-strength"),
                    List.of("summary-improvement")
                );
            }
            throw new IllegalStateException("Unexpected logContext: " + logContext);
        });

        EvaluationReport report = batchedService.evaluate(chatClient, "session-2", records, "resume");

        assertThat(report.questionEvaluations()).extracting(EvaluationReport.QuestionEvaluation::score)
            .containsExactly(0, 0, 90);
        assertThat(report.overallScore()).isEqualTo(30);
        assertThat(report.questionEvaluations().get(0).feedback()).contains("0 分");
    }

    @Test
    @DisplayName("should fallback to merged batch result when summary fails")
    void shouldFallbackToMergedBatchResultWhenSummaryFails() throws Exception {
        List<QaRecord> records = List.of(record(0, "answer-1"));

        whenInvoke().thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return createBatchEvaluationResult(
                    "batch-feedback",
                    List.of("batch-strength"),
                    List.of("batch-improvement"),
                    List.of(createQuestionEvaluation(0, 75, "feedback-1"))
                );
            }
            if (SUMMARY_CONTEXT.equals(logContext)) {
                throw new RuntimeException("summary failed");
            }
            throw new IllegalStateException("Unexpected logContext: " + logContext);
        });

        EvaluationReport report = service.evaluate(chatClient, "session-3", records, "resume");

        assertThat(report.overallFeedback()).isEqualTo("batch-feedback");
        assertThat(report.strengths()).containsExactly("batch-strength");
        assertThat(report.improvements()).containsExactly("batch-improvement");
    }

    @Test
    @DisplayName("should merge question evaluations by question index when model returns them out of order")
    void shouldMergeQuestionEvaluationsByQuestionIndexWhenModelReturnsThemOutOfOrder() throws Exception {
        List<QaRecord> records = List.of(
            record(0, "answer-1"),
            record(1, "answer-2")
        );

        whenInvoke().thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return createBatchEvaluationResult(
                    "batch-feedback",
                    List.of("strength"),
                    List.of("improvement"),
                    List.of(
                        createQuestionEvaluation(1, 30, "second-feedback"),
                        createQuestionEvaluation(0, 90, "first-feedback")
                    )
                );
            }
            if (SUMMARY_CONTEXT.equals(logContext)) {
                return createSummaryResult(
                    "summary-feedback",
                    List.of("summary-strength"),
                    List.of("summary-improvement")
                );
            }
            throw new IllegalStateException("Unexpected logContext: " + logContext);
        });

        EvaluationReport report = service.evaluate(chatClient, "session-reordered", records, "resume");

        assertThat(report.questionEvaluations()).extracting(EvaluationReport.QuestionEvaluation::score)
            .containsExactly(90, 30);
        assertThat(report.questionEvaluations()).extracting(EvaluationReport.QuestionEvaluation::feedback)
            .containsExactly("first-feedback", "second-feedback");
    }

    @Test
    @DisplayName("should force unanswered questions to zero")
    void shouldForceUnansweredQuestionsToZero() throws Exception {
        List<QaRecord> records = List.of(record(0, null));

        whenInvoke().thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return createBatchEvaluationResult(
                    "batch-feedback",
                    List.of("strength"),
                    List.of("improvement"),
                    List.of(createQuestionEvaluation(0, 88, "feedback-1"))
                );
            }
            if (SUMMARY_CONTEXT.equals(logContext)) {
                return createSummaryResult(
                    "summary-feedback",
                    List.of("summary-strength"),
                    List.of("summary-improvement")
                );
            }
            throw new IllegalStateException("Unexpected logContext: " + logContext);
        });

        EvaluationReport report = service.evaluate(chatClient, "session-4", records, "resume");

        assertThat(report.questionEvaluations().get(0).score()).isZero();
        assertThat(report.overallScore()).isZero();
        assertThat(capturedSummaryUserPrompt()).contains("分数:0");
        assertThat(capturedSummaryUserPrompt()).doesNotContain("分数:88");
    }

    @Test
    @DisplayName("should fail evaluation when every non-empty batch fails")
    void shouldFailEvaluationWhenEveryNonEmptyBatchFails() {
        List<QaRecord> records = List.of(record(0, "answer-1"), record(1, "answer-2"));

        whenInvoke().thenThrow(new RuntimeException("all failed"));

        assertThatThrownBy(() -> service.evaluate(chatClient, "session-5", records, "resume"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.INTERVIEW_EVALUATION_FAILED.getCode()))
            .hasMessageContaining("所有批次评估均失败");
    }

    @Test
    @DisplayName("should return empty report without model calls when qa records are empty")
    void shouldReturnEmptyReportWithoutModelCallsWhenQaRecordsAreEmpty() {
        EvaluationReport report = service.evaluate(chatClient, "session-empty", List.of(), "resume");

        assertThat(report.sessionId()).isEqualTo("session-empty");
        assertThat(report.totalQuestions()).isZero();
        assertThat(report.overallScore()).isZero();
        assertThat(report.questionEvaluations()).isEmpty();
        assertThat(report.categoryScores()).isEmpty();
        verify(structuredOutputInvoker, times(0)).invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        );
    }

    private org.mockito.stubbing.OngoingStubbing<Object> whenInvoke() {
        return when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        ));
    }

    private String capturedSummaryUserPrompt() {
        var invocations = org.mockito.Mockito.mockingDetails(structuredOutputInvoker).getInvocations();
        return invocations.stream()
            .filter(invocation -> SUMMARY_CONTEXT.equals(invocation.getArgument(6, String.class)))
            .map(invocation -> invocation.getArgument(2, String.class))
            .findFirst()
            .orElse("");
    }

    private UnifiedEvaluationService createService(int batchSize) throws IOException {
        InterviewEvaluationProperties properties = new InterviewEvaluationProperties();
        properties.setBatchSize(batchSize);
        properties.setSystemPromptPath(resource("eval-system"));
        properties.setUserPromptPath(resource("eval-user {sessionId} {resumeText} {referenceContext} {qaRecords}"));
        properties.setSummarySystemPromptPath(resource("summary-system"));
        properties.setSummaryUserPromptPath(resource("summary-user {resumeText} {referenceContext} {categorySummary} {questionHighlights} {fallbackOverallFeedback} {fallbackStrengths} {fallbackImprovements}"));
        return new UnifiedEvaluationService(structuredOutputInvoker, properties);
    }

    private ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private QaRecord record(int questionIndex, String userAnswer) {
        return new QaRecord(questionIndex, "question-" + questionIndex, "project", userAnswer);
    }

    private Object createQuestionEvaluation(int questionIndex, int score, String feedback) throws Exception {
        return newPrivateRecord(
            "interview.guide.common.evaluation.UnifiedEvaluationService$QuestionEvaluationDTO",
            questionIndex,
            score,
            feedback,
            "reference-answer",
            List.of("key-point-1")
        );
    }

    private Object createBatchEvaluationResult(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<Object> questionEvaluations
    ) throws Exception {
        return newPrivateRecord(
            "interview.guide.common.evaluation.UnifiedEvaluationService$BatchReportDTO",
            80,
            overallFeedback,
            strengths,
            improvements,
            questionEvaluations
        );
    }

    private Object createSummaryResult(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) throws Exception {
        return newPrivateRecord(
            "interview.guide.common.evaluation.UnifiedEvaluationService$SummaryDTO",
            overallFeedback,
            strengths,
            improvements
        );
    }

    private Object newPrivateRecord(String className, Object... args) throws Exception {
        Class<?> clazz = Class.forName(className);
        var constructor = clazz.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }
}
