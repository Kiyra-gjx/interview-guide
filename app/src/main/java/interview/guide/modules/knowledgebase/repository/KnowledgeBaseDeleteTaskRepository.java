package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeBaseDeleteTaskRepository extends JpaRepository<KnowledgeBaseDeleteTaskEntity, Long> {

    Optional<KnowledgeBaseDeleteTaskEntity> findFirstByKnowledgeBaseIdAndStatusIn(
        Long knowledgeBaseId,
        Collection<KnowledgeBaseDeleteTaskStatus> statuses
    );

    Optional<KnowledgeBaseDeleteTaskEntity> findByIdAndStatusAndClaimToken(
        Long id,
        KnowledgeBaseDeleteTaskStatus status,
        String claimToken
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT t FROM KnowledgeBaseDeleteTaskEntity t
        WHERE t.id = :taskId
          AND t.status = interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskStatus.PROCESSING
          AND t.claimToken = :claimToken
        """)
    Optional<KnowledgeBaseDeleteTaskEntity> findProcessingTaskForUpdate(
        @Param("taskId") Long taskId,
        @Param("claimToken") String claimToken
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT t FROM KnowledgeBaseDeleteTaskEntity t
        WHERE (t.status IN :retryableStatuses AND t.nextRetryAt <= :now)
           OR (t.status = interview.guide.modules.knowledgebase.model.KnowledgeBaseDeleteTaskStatus.PROCESSING
               AND t.updatedAt <= :processingExpiredBefore)
        ORDER BY t.nextRetryAt ASC, t.id ASC
        """)
    List<KnowledgeBaseDeleteTaskEntity> findDueTasksForUpdate(
        @Param("retryableStatuses") Collection<KnowledgeBaseDeleteTaskStatus> retryableStatuses,
        @Param("now") LocalDateTime now,
        @Param("processingExpiredBefore") LocalDateTime processingExpiredBefore,
        Pageable pageable
    );
}
