package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseDeleteTaskRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class KnowledgeBaseDeleteServiceTest {

    private KnowledgeBaseDeleteService deleteService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KnowledgeBaseDeleteTaskRepository deleteTaskRepository;

    @Mock
    private RagChatSessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        deleteService = new KnowledgeBaseDeleteService(
            knowledgeBaseRepository,
            deleteTaskRepository,
            sessionRepository
        );
    }

    @Test
    void deleteKnowledgeBaseMarksEntityAndCreatesCleanupTask() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(10L);
        kb.setStorageKey("knowledgebases/2026/05/07/test.pdf");

        when(knowledgeBaseRepository.findById(10L)).thenReturn(Optional.of(kb));
        when(sessionRepository.findByKnowledgeBaseIds(List.of(10L))).thenReturn(List.of());
        when(deleteTaskRepository.findFirstByKnowledgeBaseIdAndStatusIn(eq(10L), anyCollection()))
            .thenReturn(Optional.empty());
        when(deleteTaskRepository.save(any(KnowledgeBaseDeleteTaskEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        deleteService.deleteKnowledgeBase(10L);

        assertEquals(KnowledgeBaseLifecycleStatus.DELETING, kb.getLifecycleStatus());
        assertNotNull(kb.getDeleteRequestedAt());
        verify(knowledgeBaseRepository).save(kb);
        verify(deleteTaskRepository).save(any(KnowledgeBaseDeleteTaskEntity.class));
    }

    @Test
    void deleteKnowledgeBaseDoesNotCreateDuplicateActiveTask() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(10L);
        kb.setLifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING);

        KnowledgeBaseDeleteTaskEntity existingTask = new KnowledgeBaseDeleteTaskEntity();
        existingTask.setKnowledgeBaseId(10L);
        existingTask.setStatus(KnowledgeBaseDeleteTaskStatus.PENDING);

        when(knowledgeBaseRepository.findById(10L)).thenReturn(Optional.of(kb));
        when(deleteTaskRepository.findFirstByKnowledgeBaseIdAndStatusIn(eq(10L), anyCollection()))
            .thenReturn(Optional.of(existingTask));

        deleteService.deleteKnowledgeBase(10L);

        verify(deleteTaskRepository).findFirstByKnowledgeBaseIdAndStatusIn(eq(10L), anyCollection());
        verify(deleteTaskRepository, never()).save(any(KnowledgeBaseDeleteTaskEntity.class));
    }

    @Test
    void deleteKnowledgeBaseRetriesFailedTaskImmediately() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(10L);
        kb.setLifecycleStatus(KnowledgeBaseLifecycleStatus.DELETE_FAILED);

        KnowledgeBaseDeleteTaskEntity failedTask = new KnowledgeBaseDeleteTaskEntity();
        failedTask.setKnowledgeBaseId(10L);
        failedTask.setStatus(KnowledgeBaseDeleteTaskStatus.FAILED);
        failedTask.setLastError("previous failure");

        when(knowledgeBaseRepository.findById(10L)).thenReturn(Optional.of(kb));
        when(sessionRepository.findByKnowledgeBaseIds(List.of(10L))).thenReturn(List.of());
        when(deleteTaskRepository.findFirstByKnowledgeBaseIdAndStatusIn(eq(10L), anyCollection()))
            .thenReturn(Optional.of(failedTask));

        deleteService.deleteKnowledgeBase(10L);

        assertEquals(KnowledgeBaseLifecycleStatus.DELETING, kb.getLifecycleStatus());
        assertEquals(KnowledgeBaseDeleteTaskStatus.PENDING, failedTask.getStatus());
        verify(deleteTaskRepository).save(failedTask);
    }
}
