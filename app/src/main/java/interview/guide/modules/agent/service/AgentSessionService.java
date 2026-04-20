package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentMessageDTO;
import interview.guide.modules.agent.model.AgentMessageEntity;
import interview.guide.modules.agent.model.AgentSessionDTO;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.model.CreateAgentSessionRequest;
import interview.guide.modules.agent.repository.AgentMessageRepository;
import interview.guide.modules.agent.repository.AgentSessionRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agent 会话服务。
 */
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private static final Duration TURN_LEASE_DURATION = Duration.ofMinutes(10);

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentTurnRepository turnRepository;
    private final ObjectMapper objectMapper;
    private final AgentMemoryService memoryService;

    @Transactional
    public AgentSessionDTO createSession(CreateAgentSessionRequest request) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(UUID.randomUUID().toString());
        session.setTitle(resolveTitle(request.title(), request.goal()));
        session.setGoal(request.goal().trim());
        session.setResumeId(request.resumeId());
        session.setKnowledgeBaseIdsJson(writeJson(request.knowledgeBaseIds() == null ? List.of() : request.knowledgeBaseIds()));
        session.setStatus(AgentExecutionState.CREATED);
        memoryService.writeMemory(session, memoryService.createInitialSnapshot(request.goal()));
        AgentSessionEntity saved = sessionRepository.save(session);
        return toSessionDTO(saved);
    }

    public AgentSessionDTO getSession(String sessionId) {
        return toSessionDTO(getSessionEntity(sessionId));
    }

    public AgentSessionEntity getSessionEntity(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    @Transactional
    public StartedTurn startTurn(String sessionId, String userMessage) {
        AgentSessionEntity session = getSessionEntityForUpdate(sessionId);
        LocalDateTime now = LocalDateTime.now();

        reclaimExpiredRunningTurns(sessionId, now);
        rejectActiveRunningTurns(sessionId, now);

        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(UUID.randomUUID().toString());
        turn.setSession(session);
        turn.setStatus(AgentTurnStatus.RUNNING);
        turn.setStartedAt(now);
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(now.plus(TURN_LEASE_DURATION));

        AgentTurnEntity savedTurn = turnRepository.save(turn);
        appendMessage(session, savedTurn, AgentMessageEntity.MessageRole.USER, userMessage);
        touchSession(session, now);
        sessionRepository.save(session);
        return new StartedTurn(session, savedTurn.getTurnId());
    }

    @Transactional
    public AgentTurnEntity completeTurn(
        String turnId,
        String reply,
        AgentMemorySnapshot memorySnapshot,
        AgentCompletionMode completionMode
    ) {
        AgentTurnEntity turn = getTurnEntityForUpdate(turnId);
        ensureCompletable(turn);
        AgentSessionEntity session = getSessionEntityForUpdate(turn.getSession().getSessionId());
        LocalDateTime now = LocalDateTime.now();

        if (memorySnapshot != null) {
            memoryService.writeMemory(session, memorySnapshot);
        }
        appendMessage(session, turn, AgentMessageEntity.MessageRole.ASSISTANT, reply);

        turn.setStatus(AgentTurnStatus.COMPLETED);
        turn.setCompletionMode(completionMode);
        turn.setErrorMessage(null);
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(null);
        turn.setFinishedAt(now);

        touchSession(session, now);
        sessionRepository.save(session);
        return turnRepository.save(turn);
    }

    @Transactional
    public AgentTurnEntity failTurn(String turnId, Exception error) {
        AgentTurnEntity turn = getTurnEntityForUpdate(turnId);
        if (!isFailSafe(turn)) {
            return turn;
        }
        AgentSessionEntity session = getSessionEntityForUpdate(turn.getSession().getSessionId());
        LocalDateTime now = LocalDateTime.now();

        turn.setStatus(AgentTurnStatus.FAILED);
        turn.setCompletionMode(null);
        turn.setErrorMessage(sanitize(error));
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(null);
        turn.setFinishedAt(now);

        touchSession(session, now);
        sessionRepository.save(session);
        return turnRepository.save(turn);
    }

    public List<AgentMessageDTO> getMessages(String sessionId) {
        return messageRepository.findBySession_SessionIdOrderByMessageOrderAsc(sessionId).stream()
            .map(this::toMessageDTO)
            .toList();
    }

    public List<Long> readKnowledgeBaseIds(AgentSessionEntity session) {
        if (session.getKnowledgeBaseIdsJson() == null || session.getKnowledgeBaseIdsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(session.getKnowledgeBaseIdsJson(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "读取 knowledgeBaseIds 失败");
        }
    }

    private AgentSessionEntity getSessionEntityForUpdate(String sessionId) {
        return sessionRepository.findBySessionIdForUpdate(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    private AgentTurnEntity getTurnEntityForUpdate(String turnId) {
        return turnRepository.findByTurnIdForUpdate(turnId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "未找到 Agent turn: " + turnId));
    }

    private void reclaimExpiredRunningTurns(String sessionId, LocalDateTime now) {
        for (AgentTurnEntity runningTurn : turnRepository.findBySession_SessionIdAndStatusOrderByCreatedAtAsc(
            sessionId,
            AgentTurnStatus.RUNNING
        )) {
            if (isLeaseExpired(runningTurn, now)) {
                markTurnAborted(runningTurn, now, "stale_running_turn");
            }
        }
    }

    private void rejectActiveRunningTurns(String sessionId, LocalDateTime now) {
        boolean hasActiveRunningTurn = turnRepository.findBySession_SessionIdAndStatusOrderByCreatedAtAsc(
            sessionId,
            AgentTurnStatus.RUNNING
        ).stream().anyMatch(turn -> !isLeaseExpired(turn, now));

        if (hasActiveRunningTurn) {
            throw new BusinessException(ErrorCode.AGENT_TURN_CONFLICT, "当前会话已有运行中的 turn");
        }
    }

    private boolean isLeaseExpired(AgentTurnEntity turn, LocalDateTime now) {
        return turn.getLeaseExpiresAt() != null && !turn.getLeaseExpiresAt().isAfter(now);
    }

    private void markTurnAborted(AgentTurnEntity turn, LocalDateTime now, String reason) {
        turn.setStatus(AgentTurnStatus.ABORTED);
        turn.setCompletionMode(null);
        turn.setErrorMessage(reason);
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(null);
        turn.setFinishedAt(now);
        turnRepository.save(turn);
    }

    private boolean isCompletable(AgentTurnEntity turn) {
        return turn.getStatus() == AgentTurnStatus.RUNNING || turn.getStatus() == AgentTurnStatus.CREATED;
    }

    private boolean isFailSafe(AgentTurnEntity turn) {
        return turn.getStatus() == AgentTurnStatus.RUNNING || turn.getStatus() == AgentTurnStatus.CREATED;
    }

    private void ensureCompletable(AgentTurnEntity turn) {
        if (isCompletable(turn)) {
            return;
        }
        if (turn.getStatus() == AgentTurnStatus.ABORTED) {
            throw new BusinessException(ErrorCode.AGENT_TURN_EXPIRED, "当前 turn 已过期并被回收");
        }
        throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "turn 已处于终态: " + turn.getStatus());
    }

    // Serialize message writes inside a short transaction to keep ordering stable.
    private void appendMessage(
        AgentSessionEntity session,
        AgentTurnEntity turn,
        AgentMessageEntity.MessageRole role,
        String content
    ) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setSession(session);
        message.setTurn(turn);
        message.setRole(role);
        message.setContent(content == null ? "" : content.trim());
        message.setMessageOrder(nextMessageOrder(session.getSessionId()));
        messageRepository.save(message);
    }

    private int nextMessageOrder(String sessionId) {
        return messageRepository.findTopBySession_SessionIdOrderByMessageOrderDesc(sessionId)
            .map(AgentMessageEntity::getMessageOrder)
            .orElse(0) + 1;
    }

    private void touchSession(AgentSessionEntity session, LocalDateTime now) {
        session.setUpdatedAt(now);
    }

    private AgentSessionDTO toSessionDTO(AgentSessionEntity session) {
        return new AgentSessionDTO(
            session.getSessionId(),
            session.getTitle(),
            session.getGoal(),
            session.getResumeId(),
            readKnowledgeBaseIds(session),
            session.getStatus(),
            session.getCreatedAt(),
            session.getUpdatedAt(),
            getMessages(session.getSessionId())
        );
    }

    private AgentMessageDTO toMessageDTO(AgentMessageEntity message) {
        return new AgentMessageDTO(
            message.getRoleString(),
            message.getContent(),
            message.getMessageOrder(),
            message.getCreatedAt()
        );
    }

    private String resolveTitle(String title, String goal) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        String normalized = goal == null ? "Agent Session" : goal.trim();
        if (normalized.length() <= 24) {
            return normalized;
        }
        return normalized.substring(0, 24) + "...";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "序列化 Agent 会话失败");
        }
    }

    private String sanitize(Exception error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "unknown_error";
        }
        String message = error.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 1000 ? message.substring(0, 1000) + "..." : message;
    }

    public record StartedTurn(AgentSessionEntity session, String turnId) {
    }
}
