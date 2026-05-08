package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {

    private static final int MAX_BATCH_SIZE = 10;

    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.textSplitter = new TokenTextSplitter();
    }

    @Transactional
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        int contentLength = content == null ? 0 : content.length();
        log.info("开始向量化知识库: kbId={}, contentLength={}", knowledgeBaseId, contentLength);
        try {
            KnowledgeBaseEntity knowledgeBase = loadActiveKnowledgeBase(knowledgeBaseId);
            deleteByKnowledgeBaseId(knowledgeBaseId);
            ensureActive(knowledgeBaseId);

            List<Document> chunks = KnowledgeBaseChunkEvidenceMapper.buildChunkDocuments(
                knowledgeBaseId,
                knowledgeBase,
                content,
                textSplitter
            );

            log.info("文本分块完成: {} 个chunks", chunks.size());

            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
            log.info("开始分批向量化: 共 {} 个chunks，分 {} 批处理，每批最多 {} 个", totalChunks, batchCount, MAX_BATCH_SIZE);

            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批 chunks {}-{}", i + 1, batchCount, start + 1, end);
                ensureActive(knowledgeBaseId);
                vectorStore.add(batch);
            }

            knowledgeBase.setChunkCount(totalChunks);
            knowledgeBaseRepository.save(knowledgeBase);

            log.info("知识库向量化完成: kbId={}, chunks={}, batches={}", knowledgeBaseId, totalChunks, batchCount);
        } catch (Exception e) {
            log.error("向量化知识库失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new RuntimeException("向量化知识库失败: " + e.getMessage(), e);
        }
    }

    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}", query, knowledgeBaseIds, topK, minScore);

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            log.info("搜索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤 {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            int effectiveTopK = Math.max(topK, 1);
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(effectiveTopK * 3);
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(effectiveTopK)
                .toList();

            log.info("回退搜索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get(KnowledgeBaseChunkEvidenceMapper.METADATA_KB_ID);
        if (kbId == null) {
            kbId = doc.getMetadata().get(KnowledgeBaseChunkEvidenceMapper.METADATA_KB_ID_LONG);
        }
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long ? (Long) kbId : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return KnowledgeBaseChunkEvidenceMapper.METADATA_KB_ID + " in [" + values + "]";
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new RuntimeException("删除向量数据失败: " + e.getMessage(), e);
        }
    }

    private void ensureActive(Long knowledgeBaseId) {
        boolean active = knowledgeBaseRepository.findLifecycleStatusById(knowledgeBaseId)
            .map(status -> status == KnowledgeBaseLifecycleStatus.ACTIVE)
            .orElse(false);
        if (!active) {
            throw new IllegalStateException("知识库已不可向量化: kbId=" + knowledgeBaseId);
        }
    }

    private KnowledgeBaseEntity loadActiveKnowledgeBase(Long knowledgeBaseId) {
        return knowledgeBaseRepository.findByIdAndLifecycleStatus(knowledgeBaseId, KnowledgeBaseLifecycleStatus.ACTIVE)
            .orElseThrow(() -> new IllegalStateException("知识库已不可向量化: kbId=" + knowledgeBaseId));
    }
}
