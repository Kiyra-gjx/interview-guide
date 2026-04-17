package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Agent 消息仓储。
 */
@Repository
public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {

    List<AgentMessageEntity> findBySession_SessionIdOrderByMessageOrderAsc(String sessionId);

    long countBySession_SessionId(String sessionId);
}
