package interview.guide.common.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Prompt and batching configuration for interview evaluation.
 */
@ConfigurationProperties(prefix = "app.ai.evaluation")
public class InterviewEvaluationProperties {

    private int batchSize = 5;
    private Resource systemPromptPath;
    private Resource userPromptPath;
    private Resource summarySystemPromptPath;
    private Resource summaryUserPromptPath;
    private int resumeMaxChars = 3000;
    private int referenceContextMaxChars = 6000;
    private int questionHighlightsLimit = 20;
    private int listItemLimit = 8;

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Resource getSystemPromptPath() {
        return systemPromptPath;
    }

    public void setSystemPromptPath(Resource systemPromptPath) {
        this.systemPromptPath = systemPromptPath;
    }

    public Resource getUserPromptPath() {
        return userPromptPath;
    }

    public void setUserPromptPath(Resource userPromptPath) {
        this.userPromptPath = userPromptPath;
    }

    public Resource getSummarySystemPromptPath() {
        return summarySystemPromptPath;
    }

    public void setSummarySystemPromptPath(Resource summarySystemPromptPath) {
        this.summarySystemPromptPath = summarySystemPromptPath;
    }

    public Resource getSummaryUserPromptPath() {
        return summaryUserPromptPath;
    }

    public void setSummaryUserPromptPath(Resource summaryUserPromptPath) {
        this.summaryUserPromptPath = summaryUserPromptPath;
    }

    public int getResumeMaxChars() {
        return resumeMaxChars;
    }

    public void setResumeMaxChars(int resumeMaxChars) {
        this.resumeMaxChars = resumeMaxChars;
    }

    public int getReferenceContextMaxChars() {
        return referenceContextMaxChars;
    }

    public void setReferenceContextMaxChars(int referenceContextMaxChars) {
        this.referenceContextMaxChars = referenceContextMaxChars;
    }

    public int getQuestionHighlightsLimit() {
        return questionHighlightsLimit;
    }

    public void setQuestionHighlightsLimit(int questionHighlightsLimit) {
        this.questionHighlightsLimit = questionHighlightsLimit;
    }

    public int getListItemLimit() {
        return listItemLimit;
    }

    public void setListItemLimit(int listItemLimit) {
        this.listItemLimit = listItemLimit;
    }

    int normalizedBatchSize() {
        return Math.max(1, batchSize);
    }

    int normalizedResumeMaxChars() {
        return Math.max(0, resumeMaxChars);
    }

    int normalizedReferenceContextMaxChars() {
        return Math.max(0, referenceContextMaxChars);
    }

    int normalizedQuestionHighlightsLimit() {
        return Math.max(1, questionHighlightsLimit);
    }

    int normalizedListItemLimit() {
        return Math.max(1, listItemLimit);
    }
}
