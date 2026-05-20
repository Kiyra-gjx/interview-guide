package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.structured-output")
@Data
public class StructuredOutputProperties {

    private int maxAttempts = 2;

    private boolean includeLastError = true;

    private boolean retryUseRepairPrompt = true;

    private Boolean retryAppendStrictJsonInstruction;

    /**
     * @deprecated Use {@code retry-append-strict-json-instruction}.
     */
    @Deprecated
    private boolean appendStrictJsonInstruction = true;

    private int errorMessageMaxLength = 200;

    private boolean metricsEnabled = true;

    public boolean shouldAppendStrictJsonInstruction() {
        return retryAppendStrictJsonInstruction != null
            ? retryAppendStrictJsonInstruction
            : appendStrictJsonInstruction;
    }
}
