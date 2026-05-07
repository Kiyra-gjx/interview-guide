package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseDeleteTaskProcessorTest {

    private KnowledgeBaseDeleteTaskProcessor processor;

    @Mock
    private KnowledgeBaseDeleteTaskStore taskStore;

    @Mock
    private KnowledgeBaseVectorService vectorService;

    @Mock
    private FileStorageService storageService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new KnowledgeBaseDeleteTaskProcessor(
            taskStore,
            vectorService,
            storageService
        );
    }

    @Test
    void processClaimedTaskDeletesResourcesAndCompletesTaskAfterSuccess() {
        KnowledgeBaseDeleteTaskEntity task = task(1L, 10L);
        task.setClaimToken("claim-1");

        when(taskStore.findProcessingTask(1L, "claim-1")).thenReturn(Optional.of(task));

        processor.processClaimedTask(1L, "claim-1");

        verify(vectorService, times(2)).deleteByKnowledgeBaseId(10L);
        verify(storageService).deleteKnowledgeBase(task.getStorageKey());
        verify(taskStore).completeTask(1L, "claim-1");
    }

    @Test
    void processClaimedTaskMarksFailedAfterResourceDeleteFailure() {
        KnowledgeBaseDeleteTaskEntity task = task(1L, 10L);
        task.setClaimToken("claim-1");

        when(taskStore.findProcessingTask(1L, "claim-1")).thenReturn(Optional.of(task));
        RuntimeException failure = new RuntimeException("vector unavailable");
        doThrow(failure).when(vectorService).deleteByKnowledgeBaseId(10L);

        processor.processClaimedTask(1L, "claim-1");

        verify(taskStore).markFailed(1L, "claim-1", failure);
    }

    private KnowledgeBaseDeleteTaskEntity task(Long taskId, Long kbId) {
        KnowledgeBaseDeleteTaskEntity task = new KnowledgeBaseDeleteTaskEntity();
        task.setId(taskId);
        task.setKnowledgeBaseId(kbId);
        task.setStorageKey("knowledgebases/2026/05/07/test.pdf");
        task.setStatus(KnowledgeBaseDeleteTaskStatus.PROCESSING);
        task.setRetryCount(0);
        return task;
    }
}
