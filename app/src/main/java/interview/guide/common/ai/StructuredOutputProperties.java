package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.structured-output")
@Data
public class StructuredOutputProperties {

    private int maxAttempts = 2;

    private boolean includeLastError = true;

    private boolean retryUseRepairPrompt = true;

    private boolean appendStrictJsonInstruction = true;

    private int errorMessageMaxLength = 200;

    private boolean metricsEnabled = true;
}
