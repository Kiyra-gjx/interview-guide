package interview.guide.modules.llmprovider.dto;

public record LlmProviderResponse(
    String id,
    String displayName,
    String baseUrl,
    boolean hasApiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    boolean supportsEmbedding,
    Double temperature,
    boolean enabled
) {}
