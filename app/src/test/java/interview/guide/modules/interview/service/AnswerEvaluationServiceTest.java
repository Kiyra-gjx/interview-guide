package interview.guide.modules.interview.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerEvaluationServiceTest {

    private static final String BATCH_CONTEXT = "\u6279\u6b21\u8bc4\u4f30";
    private static final String SUMMARY_CONTEXT = "\u603b\u7ed3\u8bc4\u4f30";

    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    private AnswerEvaluationService answerEvaluationService;

    @BeforeEach
    void setUp() throws IOException {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        answerEvaluationService = createService(8);
    }

    @Test
    @DisplayName("should force score to zero when question has no answer")
    void shouldForceScoreToZero_whenQuestionHasNoAnswer() throws Exception {
        String sessionId = "session-1";
        String resumeText = "Java backend developer with Spring Boot";
        List<InterviewQuestionDTO> questions = List.of(createQuestion(0, null));

        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return createBatchEvaluationResult(
                    80,
                    "batch-feedback",
                    List.of("clear expression"),
                    List.of("add more detail"),
                    List.of(createQuestionEvaluation(0, 80, "good answer"))
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

        InterviewReportDTO report = answerEvaluationService.evaluateInterview(sessionId, resumeText, questions);

        assertEquals(0, report.questionDetails().get(0).score());
        assertEquals(0, report.overallScore());
    }

    @Test
    @DisplayName("should fallback to batch aggregation when summary fails")
    void shouldFallbackToBatchResults_whenSummaryFails() throws Exception {
        String sessionId = "session-2";
        String resumeText = "Java backend developer with Spring Boot";
        List<InterviewQuestionDTO> questions = List.of(createQuestion(0, "I built an admin system"));

        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return createBatchEvaluationResult(
                    75,
                    "batch-overall-feedback",
                    List.of("clear expression"),
                    List.of("need more project detail"),
                    List.of(createQuestionEvaluation(0, 75, "solid answer"))
                );
            }
            if (SUMMARY_CONTEXT.equals(logContext)) {
                throw new RuntimeException("summary failed");
            }
            throw new IllegalStateException("Unexpected logContext: " + logContext);
        });

        InterviewReportDTO report = answerEvaluationService.evaluateInterview(sessionId, resumeText, questions);

        assertEquals("batch-overall-feedback", report.overallFeedback());
        assertEquals(List.of("clear expression"), report.strengths());
        assertEquals(List.of("need more project detail"), report.improvements());
    }

    @Test
    @DisplayName("should evaluate in multiple batches when question count exceeds batch size")
    void shouldEvaluateInMultipleBatches_whenQuestionCountExceedsBatchSize() throws Exception {
        AnswerEvaluationService batchedService = createService(2);
        String sessionId = "session-3";
        String resumeText = "Java backend developer with Spring Boot";
        List<InterviewQuestionDTO> questions = List.of(
            createQuestion(0, "answer-1"),
            createQuestion(1, "answer-2"),
            createQuestion(2, "answer-3"),
            createQuestion(3, "answer-4"),
            createQuestion(4, "answer-5")
        );

        AtomicInteger batchInvocationCount = new AtomicInteger();
        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return switch (batchInvocationCount.getAndIncrement()) {
                    case 0 -> createBatchEvaluationResult(
                        90,
                        "batch-1",
                        List.of("strength-1"),
                        List.of("improvement-1"),
                        List.of(
                            createQuestionEvaluation(0, 80, "feedback-1"),
                            createQuestionEvaluation(1, 70, "feedback-2")
                        )
                    );
                    case 1 -> createBatchEvaluationResult(
                        70,
                        "batch-2",
                        List.of("strength-2"),
                        List.of("improvement-2"),
                        List.of(
                            createQuestionEvaluation(2, 60, "feedback-3"),
                            createQuestionEvaluation(3, 50, "feedback-4")
                        )
                    );
                    case 2 -> createBatchEvaluationResult(
                        50,
                        "batch-3",
                        List.of("strength-3"),
                        List.of("improvement-3"),
                        List.of(createQuestionEvaluation(4, 40, "feedback-5"))
                    );
                    default -> throw new IllegalStateException("Unexpected batch invocation");
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

        InterviewReportDTO report = batchedService.evaluateInterview(sessionId, resumeText, questions);

        assertEquals(5, report.questionDetails().size());
        assertEquals(80, report.questionDetails().get(0).score());
        assertEquals(70, report.questionDetails().get(1).score());
        assertEquals(60, report.questionDetails().get(2).score());
        assertEquals(50, report.questionDetails().get(3).score());
        assertEquals(40, report.questionDetails().get(4).score());
        assertEquals(60, report.overallScore());

        verify(structuredOutputInvoker, times(3)).invoke(
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
    @DisplayName("should fill missing question evaluations with zero when batch result is short")
    void shouldFillMissingQuestionEvaluationsWithZero_whenBatchResultIsShort() throws Exception {
        String sessionId = "session-4";
        String resumeText = "Java backend developer with Spring Boot";
        List<InterviewQuestionDTO> questions = List.of(
            createQuestion(0, "answer-1"),
            createQuestion(1, "answer-2")
        );

        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return createBatchEvaluationResult(
                    95,
                    "batch-feedback",
                    List.of("strength"),
                    List.of("improvement"),
                    List.of(createQuestionEvaluation(0, 90, "first-feedback"))
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

        InterviewReportDTO report = answerEvaluationService.evaluateInterview(sessionId, resumeText, questions);

        assertEquals(2, report.questionDetails().size());
        assertEquals(90, report.questionDetails().get(0).score());
        assertEquals(0, report.questionDetails().get(1).score());
        assertEquals(45, report.overallScore());
    }

    @Test
    @DisplayName("should calculate overall score from question scores instead of trusting ai overall score")
    void shouldCalculateOverallScoreFromQuestionScores_whenAiOverallScoreDiffers() throws Exception {
        String sessionId = "session-5";
        String resumeText = "Java backend developer with Spring Boot";
        List<InterviewQuestionDTO> questions = List.of(
            createQuestion(0, "answer-1"),
            createQuestion(1, "answer-2")
        );

        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenAnswer(invocation -> {
            String logContext = invocation.getArgument(6, String.class);
            if (BATCH_CONTEXT.equals(logContext)) {
                return createBatchEvaluationResult(
                    95,
                    "ai-overall-feedback",
                    List.of("strength"),
                    List.of("improvement"),
                    List.of(
                        createQuestionEvaluation(0, 80, "first-feedback"),
                        createQuestionEvaluation(1, 60, "second-feedback")
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

        InterviewReportDTO report = answerEvaluationService.evaluateInterview(sessionId, resumeText, questions);

        assertEquals(80, report.questionDetails().get(0).score());
        assertEquals(60, report.questionDetails().get(1).score());
        assertEquals(70, report.overallScore());
    }

    private AnswerEvaluationService createService(int evaluationBatchSize) throws IOException {
        return new AnswerEvaluationService(
            chatClientBuilder,
            structuredOutputInvoker,
            new ByteArrayResource("eval-system".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("eval-user".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("summary-system".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("summary-user".getBytes(StandardCharsets.UTF_8)),
            evaluationBatchSize
        );
    }

    private InterviewQuestionDTO createQuestion(int questionIndex, String userAnswer) {
        return new InterviewQuestionDTO(
            questionIndex,
            "question-" + questionIndex,
            InterviewQuestionDTO.QuestionType.PROJECT,
            "project",
            userAnswer,
            null,
            null,
            false,
            null
        );
    }

    private Object createQuestionEvaluation(int questionIndex, int score, String feedback) throws Exception {
        return newPrivateRecord(
            "interview.guide.modules.interview.service.AnswerEvaluationService$QuestionEvaluationDTO",
            questionIndex,
            score,
            feedback,
            "reference-answer",
            List.of("key-point-1")
        );
    }

    private Object createBatchEvaluationResult(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<Object> questionEvaluations
    ) throws Exception {
        return newPrivateRecord(
            "interview.guide.modules.interview.service.AnswerEvaluationService$EvaluationReportDTO",
            overallScore,
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
            "interview.guide.modules.interview.service.AnswerEvaluationService$FinalSummaryDTO",
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
