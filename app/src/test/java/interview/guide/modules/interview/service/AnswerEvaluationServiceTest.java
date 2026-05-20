package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerEvaluationServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private UnifiedEvaluationService unifiedEvaluationService;

    private AnswerEvaluationService answerEvaluationService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        answerEvaluationService = new AnswerEvaluationService(chatClientBuilder, unifiedEvaluationService);
    }

    @Test
    @DisplayName("should delegate interview evaluation to unified service and preserve report shape")
    void shouldDelegateInterviewEvaluationToUnifiedServiceAndPreserveReportShape() {
        String sessionId = "session-1";
        String resumeText = "Java backend developer";
        List<InterviewQuestionDTO> questions = List.of(
            createQuestion(0, "answer-1"),
            createQuestion(1, null)
        );
        EvaluationReport unifiedReport = new EvaluationReport(
            sessionId,
            2,
            40,
            List.of(new EvaluationReport.CategoryScore("project", 40, 2)),
            List.of(
                new EvaluationReport.QuestionEvaluation(0, "question-0", "project", "answer-1", 80, "solid"),
                new EvaluationReport.QuestionEvaluation(1, "question-1", "project", null, 0, "not answered")
            ),
            "overall feedback",
            List.of("strength"),
            List.of("improvement"),
            List.of(
                new EvaluationReport.ReferenceAnswer(0, "question-0", "reference-0", List.of("key-0")),
                new EvaluationReport.ReferenceAnswer(1, "question-1", "reference-1", List.of("key-1"))
            )
        );

        when(unifiedEvaluationService.evaluate(eq(chatClient), eq(sessionId), any(), eq(resumeText)))
            .thenReturn(unifiedReport);

        InterviewReportDTO report = answerEvaluationService.evaluateInterview(sessionId, resumeText, questions);

        assertThat(report.sessionId()).isEqualTo(sessionId);
        assertThat(report.totalQuestions()).isEqualTo(2);
        assertThat(report.overallScore()).isEqualTo(40);
        assertThat(report.categoryScores()).containsExactly(new InterviewReportDTO.CategoryScore("project", 40, 2));
        assertThat(report.questionDetails()).containsExactly(
            new InterviewReportDTO.QuestionEvaluation(0, "question-0", "project", "answer-1", 80, "solid"),
            new InterviewReportDTO.QuestionEvaluation(1, "question-1", "project", null, 0, "not answered")
        );
        assertThat(report.referenceAnswers()).containsExactly(
            new InterviewReportDTO.ReferenceAnswer(0, "question-0", "reference-0", List.of("key-0")),
            new InterviewReportDTO.ReferenceAnswer(1, "question-1", "reference-1", List.of("key-1"))
        );

        verify(unifiedEvaluationService).evaluate(
            eq(chatClient),
            eq(sessionId),
            eq(List.of(
                new QaRecord(0, "question-0", "project", "answer-1"),
                new QaRecord(1, "question-1", "project", null)
            )),
            eq(resumeText)
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
}
