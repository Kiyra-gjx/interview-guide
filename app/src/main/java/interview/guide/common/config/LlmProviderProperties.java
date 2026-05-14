package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "app.ai")
@Data
public class LlmProviderProperties {

    private String defaultProvider;
    private String defaultEmbeddingProvider;
    private Integer embeddingDimensions = 1024;
    private String encryptionKey;
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        private String embeddingModel;
        private Integer embeddingDimensions;
        private boolean supportsEmbedding;
        private Double temperature = 0.2;
    }
}
