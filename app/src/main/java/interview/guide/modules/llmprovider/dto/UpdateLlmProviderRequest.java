package interview.guide.modules.llmprovider.dto;

public record UpdateLlmProviderRequest(
    String displayName,
    String baseUrl,
    String apiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    Boolean supportsEmbedding,
    Double temperature,
    Boolean enabled
) {}
