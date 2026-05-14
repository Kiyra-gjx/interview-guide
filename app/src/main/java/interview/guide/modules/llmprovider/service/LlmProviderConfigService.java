package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.llmprovider.dto.*;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderConfigService {

    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;
    private final LlmProviderRegistry registry;

    @Transactional
    public LlmProviderResponse create(CreateLlmProviderRequest request) {
        if (providerRepository.existsById(request.id())) {
            throw new IllegalArgumentException("Provider '" + request.id() + "' 已存在");
        }

        ApiKeyEncryptionService.EncryptedKey encrypted = encryptionService.encrypt(request.apiKey());

        LlmProviderEntity entity = LlmProviderEntity.builder()
            .id(request.id())
            .displayName(request.displayName())
            .baseUrl(request.baseUrl())
            .apiKeyNonce(encrypted.nonce())
            .apiKeyCiphertext(encrypted.ciphertext())
            .model(request.model())
            .embeddingModel(request.embeddingModel())
            .embeddingDimensions(request.embeddingDimensions())
            .supportsEmbedding(request.supportsEmbedding())
            .temperature(request.temperature() != null ? request.temperature() : 0.2)
            .enabled(true)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        providerRepository.save(entity);
        registry.reload();
        log.info("创建 LLM Provider: {}", request.id());
        return toResponse(entity);
    }

    @Transactional
    public LlmProviderResponse update(String id, UpdateLlmProviderRequest request) {
        LlmProviderEntity entity = providerRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Provider '" + id + "' 不存在"));

        if (request.displayName() != null) entity.setDisplayName(request.displayName());
        if (request.baseUrl() != null) entity.setBaseUrl(request.baseUrl());
        if (request.model() != null) entity.setModel(request.model());
        if (request.embeddingModel() != null) entity.setEmbeddingModel(request.embeddingModel());
        if (request.embeddingDimensions() != null) entity.setEmbeddingDimensions(request.embeddingDimensions());
        if (request.supportsEmbedding() != null) entity.setSupportsEmbedding(request.supportsEmbedding());
        if (request.temperature() != null) entity.setTemperature(request.temperature());
        if (request.enabled() != null) entity.setEnabled(request.enabled());

        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            ApiKeyEncryptionService.EncryptedKey encrypted = encryptionService.encrypt(request.apiKey());
            entity.setApiKeyNonce(encrypted.nonce());
            entity.setApiKeyCiphertext(encrypted.ciphertext());
        }

        entity.setUpdatedAt(LocalDateTime.now());
        providerRepository.save(entity);
        registry.reload();
        log.info("更新 LLM Provider: {}", id);
        return toResponse(entity);
    }

    @Transactional
    public void delete(String id) {
        if (!providerRepository.existsById(id)) {
            throw new IllegalArgumentException("Provider '" + id + "' 不存在");
        }
        providerRepository.deleteById(id);
        registry.reload();
        log.info("删除 LLM Provider: {}", id);
    }

    @Transactional(readOnly = true)
    public List<LlmProviderResponse> listAll() {
        return providerRepository.findAll().stream()
            .map(this::toResponse)
            .toList();
    }

    public String testConnection(String id) {
        try {
            var client = registry.getChatClient(id);
            String response = client.prompt()
                .user("Say 'OK' if you can hear me.")
                .call()
                .content();
            return "连通性测试成功: " + (response != null ? response.substring(0, Math.min(response.length(), 50)) : "empty");
        } catch (Exception e) {
            return "连通性测试失败: " + e.getMessage();
        }
    }

    @Transactional
    public DefaultsResponse updateDefaults(UpdateDefaultsRequest request) {
        LlmGlobalSettingEntity entity = globalSettingRepository
            .findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .orElse(new LlmGlobalSettingEntity());
        entity.setId(LlmGlobalSettingEntity.SINGLETON_ID);
        entity.setDefaultChatProviderId(request.defaultChatProviderId());
        if (request.defaultEmbeddingProviderId() != null) {
            entity.setDefaultEmbeddingProviderId(request.defaultEmbeddingProviderId());
        }
        globalSettingRepository.save(entity);
        registry.reload();
        log.info("更新默认 Provider: chat={}, embedding={}",
            request.defaultChatProviderId(), request.defaultEmbeddingProviderId());
        return new DefaultsResponse(entity.getDefaultChatProviderId(), entity.getDefaultEmbeddingProviderId());
    }

    @Transactional(readOnly = true)
    public DefaultsResponse getDefaults() {
        LlmGlobalSettingEntity entity = globalSettingRepository
            .findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .orElse(new LlmGlobalSettingEntity());
        return new DefaultsResponse(entity.getDefaultChatProviderId(), entity.getDefaultEmbeddingProviderId());
    }

    private LlmProviderResponse toResponse(LlmProviderEntity entity) {
        return new LlmProviderResponse(
            entity.getId(),
            entity.getDisplayName(),
            entity.getBaseUrl(),
            entity.getApiKeyCiphertext() != null && entity.getApiKeyCiphertext().length > 0,
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature(),
            entity.isEnabled()
        );
    }
}
