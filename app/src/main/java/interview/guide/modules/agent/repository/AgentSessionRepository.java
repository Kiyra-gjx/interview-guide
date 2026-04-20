package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentSessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 会话仓储。
 */
@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, Long> {

    Optional<AgentSessionEntity> findBySessionId(String sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AgentSessionEntity s where s.sessionId = :sessionId")
    Optional<AgentSessionEntity> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    List<AgentSessionEntity> findAllByOrderByUpdatedAtDesc();
}
