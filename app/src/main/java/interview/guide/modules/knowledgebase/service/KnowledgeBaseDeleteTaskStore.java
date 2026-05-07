package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseDeleteTaskRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeBaseDeleteTaskStore {

    private static final int MAX_BATCH_SIZE = 20;
    private static final int MAX_RETRY_DELAY_MINUTES = 60;
    private static final Set<KnowledgeBaseDeleteTaskStatus> DUE_STATUSES = Set.of(
        KnowledgeBaseDeleteTaskStatus.PENDING,
        KnowledgeBaseDeleteTaskStatus.FAILED
    );

    private final KnowledgeBaseDeleteTaskRepository deleteTaskRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final Duration processingTimeout;

    public KnowledgeBaseDeleteTaskStore(
        KnowledgeBaseDeleteTaskRepository deleteTaskRepository,
        KnowledgeBaseRepository knowledgeBaseRepository,
        @Value("${app.knowledge-base.delete-processing-timeout-ms:600000}") long processingTimeoutMs
    ) {
        this.deleteTaskRepository = deleteTaskRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.processingTimeout = Duration.ofMillis(processingTimeoutMs);
    }

    @Transactional
    public List<KnowledgeBaseDeleteTaskEntity> claimDueTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeBaseDeleteTaskEntity> dueTasks = deleteTaskRepository.findDueTasksForUpdate(
            DUE_STATUSES,
            now,
            now.minus(processingTimeout),
            PageRequest.of(0, MAX_BATCH_SIZE)
        );
        for (KnowledgeBaseDeleteTaskEntity task : dueTasks) {
            task.setStatus(KnowledgeBaseDeleteTaskStatus.PROCESSING);
            task.setClaimToken(UUID.randomUUID().toString());
            task.setLastError(null);
            deleteTaskRepository.save(task);
        }
        return dueTasks;
    }

    @Transactional(readOnly = true)
    public Optional<KnowledgeBaseDeleteTaskEntity> findProcessingTask(Long taskId, String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            return Optional.empty();
        }
        return deleteTaskRepository.findByIdAndStatusAndClaimToken(
            taskId,
            KnowledgeBaseDeleteTaskStatus.PROCESSING,
            claimToken
        );
    }

    @Transactional
    public void completeTask(Long taskId, String claimToken) {
        KnowledgeBaseDeleteTaskEntity task = findProcessingTaskForUpdate(taskId, claimToken).orElse(null);
        if (task == null) {
            return;
        }
        knowledgeBaseRepository.findById(task.getKnowledgeBaseId())
            .ifPresent(knowledgeBaseRepository::delete);
        task.setStatus(KnowledgeBaseDeleteTaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        task.setClaimToken(null);
        task.setLastError(null);
        deleteTaskRepository.save(task);
    }

    @Transactional
    public void markFailed(Long taskId, String claimToken, Exception e) {
        KnowledgeBaseDeleteTaskEntity task = findProcessingTaskForUpdate(taskId, claimToken).orElse(null);
        if (task == null) {
            return;
        }

        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int nextRetryCount = retryCount + 1;

        task.setRetryCount(nextRetryCount);
        task.setStatus(KnowledgeBaseDeleteTaskStatus.FAILED);
        task.setClaimToken(null);
        task.setLastError(truncate(e.getMessage()));
        task.setNextRetryAt(LocalDateTime.now().plusMinutes(resolveRetryDelayMinutes(nextRetryCount)));
        deleteTaskRepository.save(task);

        knowledgeBaseRepository.findById(task.getKnowledgeBaseId()).ifPresent(kb -> {
            kb.setLifecycleStatus(KnowledgeBaseLifecycleStatus.DELETE_FAILED);
            knowledgeBaseRepository.save(kb);
        });
    }

    private Optional<KnowledgeBaseDeleteTaskEntity> findProcessingTaskForUpdate(Long taskId, String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            return Optional.empty();
        }
        return deleteTaskRepository.findProcessingTaskForUpdate(taskId, claimToken);
    }

    private int resolveRetryDelayMinutes(int retryCount) {
        int delay = (int) Math.pow(2, Math.min(retryCount - 1, 6));
        return Math.min(delay, MAX_RETRY_DELAY_MINUTES);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
