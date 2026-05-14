package interview.guide.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "llm_global_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmGlobalSettingEntity {

    public static final String SINGLETON_ID = "global";

    @Id
    @Column(length = 50)
    @Builder.Default
    private String id = SINGLETON_ID;

    @Column(name = "default_chat_provider_id", length = 50)
    private String defaultChatProviderId;

    @Column(name = "default_embedding_provider_id", length = 50)
    private String defaultEmbeddingProviderId;
}
