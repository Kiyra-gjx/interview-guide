package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseDeleteTaskRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteService {

    private static final Set<KnowledgeBaseDeleteTaskStatus> ACTIVE_TASK_STATUSES = Set.of(
        KnowledgeBaseDeleteTaskStatus.PENDING,
        KnowledgeBaseDeleteTaskStatus.PROCESSING,
        KnowledgeBaseDeleteTaskStatus.FAILED
    );

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseDeleteTaskRepository deleteTaskRepository;
    private final RagChatSessionRepository sessionRepository;

    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long id) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        if (kb.getLifecycleStatus() == KnowledgeBaseLifecycleStatus.DELETING) {
            log.info("知识库删除任务已存在: kbId={}", id);
            ensureDeleteTask(kb, false);
            return;
        }

        boolean retryNow = false;
        if (kb.getLifecycleStatus() == KnowledgeBaseLifecycleStatus.DELETE_FAILED) {
            log.info("重新提交删除失败的知识库清理任务: kbId={}", id);
            retryNow = true;
        }

        List<RagChatSessionEntity> sessions = sessionRepository.findByKnowledgeBaseIds(List.of(id));
        for (RagChatSessionEntity session : sessions) {
            session.getKnowledgeBases().removeIf(kbEntity -> kbEntity.getId().equals(id));
            sessionRepository.save(session);
            log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.getId(), id);
        }

        kb.setLifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING);
        kb.setDeleteRequestedAt(LocalDateTime.now());
        knowledgeBaseRepository.save(kb);

        ensureDeleteTask(kb, retryNow);
        log.info("知识库已标记为删除中: kbId={}", id);
    }

    private void ensureDeleteTask(KnowledgeBaseEntity kb, boolean retryNow) {
        deleteTaskRepository.findFirstByKnowledgeBaseIdAndStatusIn(kb.getId(), ACTIVE_TASK_STATUSES)
            .ifPresentOrElse(task -> {
                if (retryNow && task.getStatus() == KnowledgeBaseDeleteTaskStatus.FAILED) {
                    task.setStatus(KnowledgeBaseDeleteTaskStatus.PENDING);
                    task.setNextRetryAt(LocalDateTime.now());
                    task.setLastError(null);
                    deleteTaskRepository.save(task);
                }
            }, () -> {
                KnowledgeBaseDeleteTaskEntity task = new KnowledgeBaseDeleteTaskEntity();
                task.setKnowledgeBaseId(kb.getId());
                task.setStorageKey(kb.getStorageKey());
                task.setStatus(KnowledgeBaseDeleteTaskStatus.PENDING);
                task.setRetryCount(0);
                task.setNextRetryAt(LocalDateTime.now());
                deleteTaskRepository.save(task);
            });
    }
}
