package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseDeleteTaskRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseDeleteTaskStoreTest {

    private KnowledgeBaseDeleteTaskStore taskStore;

    @Mock
    private KnowledgeBaseDeleteTaskRepository deleteTaskRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        taskStore = new KnowledgeBaseDeleteTaskStore(deleteTaskRepository, knowledgeBaseRepository, 600_000L);
    }

    @Test
    void claimDueTasksReclaimsExpiredProcessingTasks() {
        KnowledgeBaseDeleteTaskEntity task = task(1L, 10L);
        task.setStatus(KnowledgeBaseDeleteTaskStatus.PROCESSING);

        when(deleteTaskRepository.findDueTasksForUpdate(
            anyCollection(),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            any(Pageable.class)
        ))
            .thenReturn(List.of(task));

        List<KnowledgeBaseDeleteTaskEntity> claimed = taskStore.claimDueTasks();

        assertEquals(List.of(task), claimed);
        assertEquals(KnowledgeBaseDeleteTaskStatus.PROCESSING, task.getStatus());
        assertNotNull(task.getClaimToken());
        verify(deleteTaskRepository).save(task);
    }

    @Test
    void completeTaskDeletesMetadataAfterResourcesWereDeleted() {
        KnowledgeBaseDeleteTaskEntity task = task(1L, 10L);
        task.setClaimToken("claim-1");
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(10L);

        when(deleteTaskRepository.findProcessingTaskForUpdate(1L, "claim-1"))
            .thenReturn(Optional.of(task));
        when(knowledgeBaseRepository.findById(10L)).thenReturn(Optional.of(kb));

        taskStore.completeTask(1L, "claim-1");

        verify(knowledgeBaseRepository).delete(kb);
        assertEquals(KnowledgeBaseDeleteTaskStatus.COMPLETED, task.getStatus());
        assertNotNull(task.getCompletedAt());
    }

    @Test
    void completeTaskIgnoresStaleClaimToken() {
        KnowledgeBaseDeleteTaskEntity task = task(1L, 10L);
        task.setClaimToken("claim-1");

        when(deleteTaskRepository.findProcessingTaskForUpdate(1L, "stale-claim"))
            .thenReturn(Optional.empty());

        taskStore.completeTask(1L, "stale-claim");

        verify(knowledgeBaseRepository, never()).delete(any(KnowledgeBaseEntity.class));
        assertEquals(KnowledgeBaseDeleteTaskStatus.PROCESSING, task.getStatus());
    }

    @Test
    void markFailedKeepsMetadataAndSchedulesRetry() {
        KnowledgeBaseDeleteTaskEntity task = task(1L, 10L);
        task.setClaimToken("claim-1");
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(10L);
        kb.setLifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING);

        when(deleteTaskRepository.findProcessingTaskForUpdate(1L, "claim-1"))
            .thenReturn(Optional.of(task));
        when(knowledgeBaseRepository.findById(10L)).thenReturn(Optional.of(kb));

        taskStore.markFailed(1L, "claim-1", new RuntimeException("vector unavailable"));

        assertEquals(KnowledgeBaseDeleteTaskStatus.FAILED, task.getStatus());
        assertEquals(1, task.getRetryCount());
        assertNotNull(task.getLastError());
        assertNotNull(task.getNextRetryAt());
        assertEquals(KnowledgeBaseLifecycleStatus.DELETE_FAILED, kb.getLifecycleStatus());
        verify(knowledgeBaseRepository).save(kb);
    }

    @Test
    void markFailedIgnoresStaleClaimToken() {
        KnowledgeBaseDeleteTaskEntity task = task(1L, 10L);
        task.setClaimToken("claim-1");

        when(deleteTaskRepository.findProcessingTaskForUpdate(1L, "stale-claim"))
            .thenReturn(Optional.empty());

        taskStore.markFailed(1L, "stale-claim", new RuntimeException("vector unavailable"));

        assertEquals(KnowledgeBaseDeleteTaskStatus.PROCESSING, task.getStatus());
        assertEquals(0, task.getRetryCount());
        verify(knowledgeBaseRepository, never()).save(any(KnowledgeBaseEntity.class));
        verify(deleteTaskRepository, never()).save(task);
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
