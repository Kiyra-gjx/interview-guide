package interview.guide.common.evaluation;

import java.util.List;

/**
 * Unified evaluation report shared by interview flow and Agent tools.
 */
public record EvaluationReport(
    String sessionId,
    int totalQuestions,
    int overallScore,
    List<CategoryScore> categoryScores,
    List<QuestionEvaluation> questionEvaluations,
    String overallFeedback,
    List<String> strengths,
    List<String> improvements,
    List<ReferenceAnswer> referenceAnswers
) {
    public EvaluationReport {
        categoryScores = categoryScores == null ? List.of() : List.copyOf(categoryScores);
        questionEvaluations = questionEvaluations == null ? List.of() : List.copyOf(questionEvaluations);
        overallFeedback = overallFeedback == null ? "" : overallFeedback;
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        improvements = improvements == null ? List.of() : List.copyOf(improvements);
        referenceAnswers = referenceAnswers == null ? List.of() : List.copyOf(referenceAnswers);
    }

    public record CategoryScore(String category, int averageScore, int questionCount) {
        public CategoryScore {
            category = category == null ? "" : category;
        }
    }

    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String category,
        String userAnswer,
        int score,
        String feedback
    ) {
        public QuestionEvaluation {
            question = question == null ? "" : question;
            category = category == null ? "" : category;
            feedback = feedback == null ? "" : feedback;
        }
    }

    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {
        public ReferenceAnswer {
            question = question == null ? "" : question;
            referenceAnswer = referenceAnswer == null ? "" : referenceAnswer;
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        }
    }
}
