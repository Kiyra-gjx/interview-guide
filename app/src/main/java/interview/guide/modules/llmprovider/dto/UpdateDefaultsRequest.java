package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateDefaultsRequest(
    @NotBlank(message = "defaultChatProviderId 不能为空") String defaultChatProviderId,
    String defaultEmbeddingProviderId
) {}
