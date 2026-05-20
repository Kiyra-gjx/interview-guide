package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Interview module adapter for the unified evaluation pipeline.
 */
@Service
public class AnswerEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final ChatClient chatClient;
    private final UnifiedEvaluationService unifiedEvaluationService;

    public AnswerEvaluationService(
        ChatClient.Builder chatClientBuilder,
        UnifiedEvaluationService unifiedEvaluationService
    ) {
        this.chatClient = chatClientBuilder.build();
        this.unifiedEvaluationService = unifiedEvaluationService;
    }

    /**
     * Evaluates a completed interview and returns the existing interview API report model.
     */
    public InterviewReportDTO evaluateInterview(
        String sessionId,
        String resumeText,
        List<InterviewQuestionDTO> questions
    ) {
        List<InterviewQuestionDTO> safeQuestions = questions == null ? List.of() : questions;
        log.info("开始评估面试: sessionId={}, questionCount={}", sessionId, safeQuestions.size());

        EvaluationReport report = unifiedEvaluationService.evaluate(
            chatClient,
            sessionId,
            toQaRecords(safeQuestions),
            resumeText
        );
        return toInterviewReport(report);
    }

    private List<QaRecord> toQaRecords(List<InterviewQuestionDTO> questions) {
        return questions.stream()
            .map(question -> new QaRecord(
                question.questionIndex(),
                question.question(),
                question.category(),
                question.userAnswer()
            ))
            .toList();
    }

    private InterviewReportDTO toInterviewReport(EvaluationReport report) {
        return new InterviewReportDTO(
            report.sessionId(),
            report.totalQuestions(),
            report.overallScore(),
            report.categoryScores().stream()
                .map(score -> new InterviewReportDTO.CategoryScore(
                    score.category(),
                    score.averageScore(),
                    score.questionCount()
                ))
                .toList(),
            report.questionEvaluations().stream()
                .map(question -> new InterviewReportDTO.QuestionEvaluation(
                    question.questionIndex(),
                    question.question(),
                    question.category(),
                    question.userAnswer(),
                    question.score(),
                    question.feedback()
                ))
                .toList(),
            report.overallFeedback(),
            report.strengths(),
            report.improvements(),
            report.referenceAnswers().stream()
                .map(reference -> new InterviewReportDTO.ReferenceAnswer(
                    reference.questionIndex(),
                    reference.question(),
                    reference.referenceAnswer(),
                    reference.keyPoints()
                ))
                .toList()
        );
    }
}
