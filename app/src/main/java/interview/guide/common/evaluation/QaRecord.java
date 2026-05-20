package interview.guide.common.evaluation;

/**
 * Single interview question and answer record for evaluation.
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer
) {
    public QaRecord {
        question = question == null ? "" : question;
        category = category == null ? "" : category;
    }
}
