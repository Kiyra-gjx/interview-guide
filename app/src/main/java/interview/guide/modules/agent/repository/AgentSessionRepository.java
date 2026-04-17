package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 会话仓储。
 */
@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, Long> {

    Optional<AgentSessionEntity> findBySessionId(String sessionId);

    List<AgentSessionEntity> findAllByOrderByUpdatedAtDesc();
}
