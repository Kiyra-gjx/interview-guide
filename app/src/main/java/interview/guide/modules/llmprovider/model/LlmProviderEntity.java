package interview.guide.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_providers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderEntity {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "base_url", length = 500, nullable = false)
    private String baseUrl;

    @Column(name = "api_key_nonce")
    private byte[] apiKeyNonce;

    @Column(name = "api_key_ciphertext")
    private byte[] apiKeyCiphertext;

    @Column(length = 100, nullable = false)
    private String model;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(name = "embedding_dimensions")
    private Integer embeddingDimensions;

    @Column(name = "supports_embedding")
    private boolean supportsEmbedding;

    @Column
    private Double temperature;

    @Column
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
