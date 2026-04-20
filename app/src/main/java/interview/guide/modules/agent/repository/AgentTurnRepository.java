package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent turn 仓储。
 */
@Repository
public interface AgentTurnRepository extends JpaRepository<AgentTurnEntity, Long> {

    Optional<AgentTurnEntity> findByTurnId(String turnId);

    List<AgentTurnEntity> findBySession_SessionIdAndStatusOrderByCreatedAtAsc(String sessionId, AgentTurnStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AgentTurnEntity t where t.turnId = :turnId")
    Optional<AgentTurnEntity> findByTurnIdForUpdate(@Param("turnId") String turnId);
}
