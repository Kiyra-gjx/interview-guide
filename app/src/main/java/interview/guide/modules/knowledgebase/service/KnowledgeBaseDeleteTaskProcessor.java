package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteTaskProcessor {

    private final KnowledgeBaseDeleteTaskStore taskStore;
    private final KnowledgeBaseVectorService vectorService;
    private final FileStorageService storageService;

    @Scheduled(fixedDelayString = "${app.knowledge-base.delete-cleanup-interval-ms:30000}")
    public void processDueTasks() {
        List<KnowledgeBaseDeleteTaskEntity> tasks = taskStore.claimDueTasks();
        for (KnowledgeBaseDeleteTaskEntity task : tasks) {
            processClaimedTask(task.getId(), task.getClaimToken());
        }
    }

    public void processClaimedTask(Long taskId, String claimToken) {
        KnowledgeBaseDeleteTaskEntity task = taskStore.findProcessingTask(taskId, claimToken).orElse(null);
        if (task == null) {
            return;
        }

        try {
            Long kbId = task.getKnowledgeBaseId();
            vectorService.deleteByKnowledgeBaseId(kbId);
            deleteStoredFile(task.getStorageKey());
            vectorService.deleteByKnowledgeBaseId(kbId);

            taskStore.completeTask(taskId, claimToken);
            log.info("知识库清理任务完成: taskId={}, kbId={}", task.getId(), kbId);
        } catch (Exception e) {
            taskStore.markFailed(taskId, claimToken, e);
            log.warn("知识库清理任务失败，将稍后重试: taskId={}, kbId={}, error={}",
                task.getId(), task.getKnowledgeBaseId(), e.getMessage());
        }
    }

    private void deleteStoredFile(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        storageService.deleteKnowledgeBase(storageKey);
    }
}
