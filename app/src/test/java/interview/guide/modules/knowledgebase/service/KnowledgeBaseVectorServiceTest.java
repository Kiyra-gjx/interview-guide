package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("知识库向量服务测试")
@SuppressWarnings("unchecked")
class KnowledgeBaseVectorServiceTest {

    private KnowledgeBaseVectorService vectorService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private VectorRepository vectorRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(knowledgeBaseRepository.findLifecycleStatusById(anyLong()))
            .thenReturn(Optional.of(KnowledgeBaseLifecycleStatus.ACTIVE));
        when(knowledgeBaseRepository.findByIdAndLifecycleStatus(anyLong(), eq(KnowledgeBaseLifecycleStatus.ACTIVE)))
            .thenAnswer(invocation -> {
                Long id = invocation.getArgument(0, Long.class);
                KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
                entity.setId(id);
                entity.setName("知识库-" + id);
                entity.setOriginalFilename("knowledge-base-" + id + ".md");
                entity.setLifecycleStatus(KnowledgeBaseLifecycleStatus.ACTIVE);
                return Optional.of(entity);
            });
        vectorService = new KnowledgeBaseVectorService(vectorStore, vectorRepository, knowledgeBaseRepository);
    }

    private KnowledgeBaseEntity createActiveKnowledgeBase(Long id, String name, String originalFilename) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setOriginalFilename(originalFilename);
        entity.setLifecycleStatus(KnowledgeBaseLifecycleStatus.ACTIVE);
        return entity;
    }

    private List<Document> createMockDocuments(int count, String kbId) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> {
                Map<String, Object> metadata = new HashMap<>();
                if (kbId != null) {
                    metadata.put("kb_id", kbId);
                }
                return new Document("文档内容 " + index, metadata);
            })
            .toList();
    }

    private Document createDocumentWithLongKbId(Long kbId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", kbId);
        return new Document("Long kb_id 文档", metadata);
    }

    private Document createDocumentWithInvalidKbId(String invalidKbId) {
        Map<String, Object> metadata = new HashMap<>();
        if (invalidKbId != null) {
            metadata.put("kb_id", invalidKbId);
        }
        return new Document("无效 kb_id 文档", metadata);
    }

    @Nested
    @DisplayName("向量化存储测试")
    class VectorizeAndStoreTests {

        @Test
        @DisplayName("向量化后应写入 chunk 元数据和 chunkCount")
        void shouldWriteChunkMetadataAndChunkCount() {
            Long knowledgeBaseId = 123L;
            String content = """
                # Java并发面试知识

                这是概述。

                ## synchronized

                锁的可重入性和互斥性。

                ## volatile

                可见性与有序性。
                """;

            when(knowledgeBaseRepository.findByIdAndLifecycleStatus(knowledgeBaseId, KnowledgeBaseLifecycleStatus.ACTIVE))
                .thenReturn(Optional.of(createActiveKnowledgeBase(knowledgeBaseId, "Java并发面试知识", "java-concurrency.md")));

            ArgumentCaptor<List<Document>> batchCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<KnowledgeBaseEntity> entityCaptor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);

            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            verify(vectorStore, atLeastOnce()).add(batchCaptor.capture());
            verify(knowledgeBaseRepository).save(entityCaptor.capture());

            List<Document> allDocs = batchCaptor.getAllValues().stream().flatMap(List::stream).toList();
            assertFalse(allDocs.isEmpty());

            Document firstDoc = allDocs.getFirst();
            assertEquals("123", firstDoc.getMetadata().get("kb_id"));
            assertEquals("Java并发面试知识", firstDoc.getMetadata().get("source_title"));
            assertEquals("概述", firstDoc.getMetadata().get("section_title"));
            assertNotNull(firstDoc.getMetadata().get("chunk_index"));
            assertNotNull(firstDoc.getMetadata().get("preview"));
            assertEquals(allDocs.size(), entityCaptor.getValue().getChunkCount());
        }

        @Test
        @DisplayName("分批向量化时每批不应超过上限")
        void shouldBatchWithinLimit() {
            Long knowledgeBaseId = 2L;
            String content = """
                # Java并发面试知识
                ## 章节1
                %s
                """.formatted("正文 ".repeat(800));

            ArgumentCaptor<List<Document>> batchCaptor = ArgumentCaptor.forClass(List.class);

            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            verify(vectorStore, atLeastOnce()).add(batchCaptor.capture());
            for (List<Document> batch : batchCaptor.getAllValues()) {
                assertTrue(batch.size() <= 10);
            }
        }

        @Test
        @DisplayName("空内容应删除旧向量但不新增")
        void shouldDeleteOldDataForEmptyContent() {
            Long knowledgeBaseId = 1L;
            vectorService.vectorizeAndStore(knowledgeBaseId, "");

            verify(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);
            verify(vectorStore, never()).add(anyList());
        }

        @Test
        @DisplayName("null content should not fail before cleanup")
        void shouldHandleNullContentWithoutLoggingNpe() {
            Long knowledgeBaseId = 1L;

            vectorService.vectorizeAndStore(knowledgeBaseId, null);

            verify(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);
            verify(vectorStore, never()).add(anyList());
        }
    }

    @Nested
    @DisplayName("相似度搜索测试")
    class SimilaritySearchTests {

        @Test
        @DisplayName("应按知识库 ID 过滤")
        void shouldFilterByKnowledgeBaseId() {
            String query = "Java 开发经验";
            List<Long> knowledgeBaseIds = List.of(1L, 2L);
            int topK = 10;

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                createMockDocuments(2, "1")
            );

            List<Document> results = vectorService.similaritySearch(query, knowledgeBaseIds, topK, 0.0);

            assertEquals(2, results.size());
            for (Document doc : results) {
                assertEquals("1", doc.getMetadata().get("kb_id"));
            }
        }

        @Test
        @DisplayName("无效 kb_id 应被忽略")
        void shouldIgnoreInvalidKbId() {
            String query = "测试";
            List<Long> knowledgeBaseIds = List.of(1L);

            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("mock vector filter failure"))
                .thenReturn(List.of(
                    createDocumentWithInvalidKbId("not_a_number"),
                    createDocumentWithInvalidKbId(null),
                    createDocumentWithLongKbId(1L)
                ));

            List<Document> results = vectorService.similaritySearch(query, knowledgeBaseIds, 10, 0.0);

            assertEquals(1, results.size());
            assertEquals("1", results.getFirst().getMetadata().get("kb_id").toString());
        }

        @Test
        @DisplayName("topK 生效")
        void shouldApplyTopK() {
            String query = "测试查询";
            int topK = 3;

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(createMockDocuments(3, "1"));

            List<Document> results = vectorService.similaritySearch(query, List.of(1L), topK, 0.0);

            assertEquals(topK, results.size());
        }

        @Test
        @DisplayName("fallback should clamp non-positive topK")
        void shouldClampNonPositiveTopKInFallback() {
            String query = "fallback query";

            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("mock vector filter failure"))
                .thenReturn(createMockDocuments(3, "1"));

            List<Document> results = vectorService.similaritySearch(query, List.of(1L), 0, 0.0);

            assertEquals(1, results.size());
        }
    }

    @Nested
    @DisplayName("删除测试")
    class DeleteVectorDataTests {

        @Test
        @DisplayName("应删除向量数据")
        void shouldDeleteVectorData() {
            Long knowledgeBaseId = 1L;
            when(vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId)).thenReturn(5);

            vectorService.deleteByKnowledgeBaseId(knowledgeBaseId);

            verify(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);
        }
    }
}
