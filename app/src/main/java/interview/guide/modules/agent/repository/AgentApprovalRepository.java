package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentApprovalEntity;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 审批仓储。
 */
@Repository
public interface AgentApprovalRepository extends JpaRepository<AgentApprovalEntity, Long> {

    Optional<AgentApprovalEntity> findByApprovalId(String approvalId);

    List<AgentApprovalEntity> findBySession_SessionIdOrderByCreatedAtDesc(String sessionId);

    List<AgentApprovalEntity> findByTurn_TurnIdOrderByCreatedAtDesc(String turnId);

    List<AgentApprovalEntity> findBySession_SessionIdAndStatusOrderByCreatedAtAsc(
        String sessionId,
        AgentApprovalStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AgentApprovalEntity a where a.approvalId = :approvalId")
    Optional<AgentApprovalEntity> findByApprovalIdForUpdate(@Param("approvalId") String approvalId);
}
