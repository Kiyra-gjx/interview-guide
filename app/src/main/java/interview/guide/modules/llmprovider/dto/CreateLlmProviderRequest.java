package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateLlmProviderRequest(
    @NotBlank(message = "id 不能为空") String id,
    String displayName,
    @NotBlank(message = "baseUrl 不能为空") String baseUrl,
    @NotBlank(message = "apiKey 不能为空") String apiKey,
    @NotBlank(message = "model 不能为空") String model,
    String embeddingModel,
    Integer embeddingDimensions,
    boolean supportsEmbedding,
    Double temperature
) {}
