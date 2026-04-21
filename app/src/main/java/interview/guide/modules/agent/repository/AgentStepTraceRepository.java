package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentStepTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 轨迹仓储。
 */
@Repository
public interface AgentStepTraceRepository extends JpaRepository<AgentStepTraceEntity, Long> {

    List<AgentStepTraceEntity> findBySession_SessionIdOrderByStepIndexAsc(String sessionId);

    List<AgentStepTraceEntity> findByTurn_TurnIdOrderByStepIndexAsc(String turnId);

    Optional<AgentStepTraceEntity> findBySession_SessionIdAndStepIndex(String sessionId, Integer stepIndex);

    long countBySession_SessionId(String sessionId);

    Optional<AgentStepTraceEntity> findTopBySession_SessionIdOrderByStepIndexDesc(String sessionId);
}
