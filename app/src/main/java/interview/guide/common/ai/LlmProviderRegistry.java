package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@EnableConfigurationProperties(LlmProviderProperties.class)
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    private final ConcurrentHashMap<String, ChatClient> chatClientCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatClient> plainChatClientCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EmbeddingModel> embeddingCache = new ConcurrentHashMap<>();

    public LlmProviderRegistry(
        LlmProviderProperties properties,
        LlmProviderRepository providerRepository,
        LlmGlobalSettingRepository globalSettingRepository,
        ApiKeyEncryptionService encryptionService
    ) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
    }

    public ChatClient getChatClient(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return getDefaultChatClient();
        }
        return chatClientCache.computeIfAbsent(providerId, this::buildChatClient);
    }

    public ChatClient getDefaultChatClient() {
        String defaultId = resolveDefaultChatProviderId();
        return chatClientCache.computeIfAbsent(defaultId, this::buildChatClient);
    }

    public ChatClient getChatClientOrDefault(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return getDefaultChatClient();
        }
        return getChatClient(providerId);
    }

    public ChatClient getPlainChatClient(String providerId) {
        String id = (providerId == null || providerId.isBlank()) ? resolveDefaultChatProviderId() : providerId;
        return plainChatClientCache.computeIfAbsent(id, this::buildPlainChatClient);
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        String defaultId = resolveDefaultEmbeddingProviderId();
        return embeddingCache.computeIfAbsent(defaultId, this::buildEmbeddingModel);
    }

    public EmbeddingModel getEmbeddingModel(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return getDefaultEmbeddingModel();
        }
        return embeddingCache.computeIfAbsent(providerId, this::buildEmbeddingModel);
    }

    public void reload() {
        chatClientCache.clear();
        plainChatClientCache.clear();
        embeddingCache.clear();
        log.info("LlmProviderRegistry 缓存已清空，下次访问将重新创建客户端");
    }

    private ChatClient buildChatClient(String providerId) {
        ProviderSpec spec = resolveProviderSpec(providerId);
        ChatModel chatModel = createChatModel(spec);
        return ChatClient.builder(chatModel).build();
    }

    private ChatClient buildPlainChatClient(String providerId) {
        ProviderSpec spec = resolveProviderSpec(providerId);
        ChatModel chatModel = createChatModel(spec);
        return ChatClient.builder(chatModel).build();
    }

    private EmbeddingModel buildEmbeddingModel(String providerId) {
        ProviderSpec spec = resolveProviderSpec(providerId);
        if (!spec.supportsEmbedding()) {
            throw new IllegalArgumentException("Provider '" + providerId + "' 不支持 Embedding");
        }
        validateEmbeddingModel(spec.embeddingModel());
        OpenAiApi api = new OpenAiApi.Builder()
            .baseUrl(spec.baseUrl())
            .apiKey(spec.apiKey())
            .build();
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions.Builder()
            .model(spec.embeddingModel())
            .dimensions(spec.embeddingDimensions())
            .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    private ChatModel createChatModel(ProviderSpec spec) {
        OpenAiApi api = new OpenAiApi.Builder()
            .baseUrl(spec.baseUrl())
            .apiKey(spec.apiKey())
            .build();
        OpenAiChatOptions options = new OpenAiChatOptions.Builder()
            .model(spec.model())
            .temperature(spec.temperature())
            .build();
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build();
    }

    private ProviderSpec resolveProviderSpec(String providerId) {
        // DB 优先
        LlmProviderEntity dbProvider = providerRepository.findById(providerId).orElse(null);
        if (dbProvider != null) {
            if (!dbProvider.isEnabled()) {
                throw new IllegalStateException("Provider '" + providerId + "' 已禁用");
            }
            String apiKey = encryptionService.decrypt(dbProvider.getApiKeyNonce(), dbProvider.getApiKeyCiphertext());
            return new ProviderSpec(
                dbProvider.getBaseUrl(),
                apiKey,
                dbProvider.getModel(),
                dbProvider.getEmbeddingModel(),
                dbProvider.getEmbeddingDimensions(),
                dbProvider.isSupportsEmbedding(),
                dbProvider.getTemperature() != null ? dbProvider.getTemperature() : 0.2
            );
        }

        // 回退到 Properties
        LlmProviderProperties.ProviderConfig propConfig = properties.getProviders().get(providerId);
        if (propConfig == null) {
            throw new IllegalArgumentException("未知的 LLM Provider: " + providerId);
        }
        return new ProviderSpec(
            propConfig.getBaseUrl(),
            propConfig.getApiKey(),
            propConfig.getModel(),
            propConfig.getEmbeddingModel(),
            propConfig.getEmbeddingDimensions(),
            propConfig.isSupportsEmbedding(),
            propConfig.getTemperature() != null ? propConfig.getTemperature() : 0.2
        );
    }

    private String resolveDefaultChatProviderId() {
        LlmGlobalSettingEntity global = globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID).orElse(null);
        if (global != null && global.getDefaultChatProviderId() != null) {
            return global.getDefaultChatProviderId();
        }
        if (properties.getDefaultProvider() != null) {
            return properties.getDefaultProvider();
        }
        Map<String, LlmProviderProperties.ProviderConfig> providers = properties.getProviders();
        if (!providers.isEmpty()) {
            return providers.keySet().iterator().next();
        }
        throw new IllegalStateException("未配置任何 LLM Provider");
    }

    private String resolveDefaultEmbeddingProviderId() {
        LlmGlobalSettingEntity global = globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID).orElse(null);
        if (global != null && global.getDefaultEmbeddingProviderId() != null) {
            return global.getDefaultEmbeddingProviderId();
        }
        if (properties.getDefaultEmbeddingProvider() != null) {
            return properties.getDefaultEmbeddingProvider();
        }
        // 回退到第一个支持 embedding 的 provider
        for (Map.Entry<String, LlmProviderProperties.ProviderConfig> entry : properties.getProviders().entrySet()) {
            if (entry.getValue().isSupportsEmbedding()) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("未配置任何支持 Embedding 的 LLM Provider");
    }

    private void validateEmbeddingModel(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        String lower = model.toLowerCase();
        if (lower.startsWith("glm-") || lower.startsWith("deepseek")
            || lower.startsWith("qwen") || lower.startsWith("ernie")) {
            throw new IllegalArgumentException(
                "Embedding 模型配置疑似为 Chat 模型: '" + model + "'，请检查配置");
        }
    }

    private record ProviderSpec(
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        boolean supportsEmbedding,
        double temperature
    ) {}
}
