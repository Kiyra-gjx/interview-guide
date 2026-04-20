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
 * Agent 会话与 turn 生命周期服务。
 * 负责会话创建、消息持久化、turn 终态流转以及并发冲突处理。
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

    /**
     * 创建新的 Agent 会话，并初始化首份记忆快照。
     */
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

    /**
     * 按 sessionId 查询会话 DTO。
     */
    public AgentSessionDTO getSession(String sessionId) {
        return toSessionDTO(getSessionEntity(sessionId));
    }

    /**
     * 按 sessionId 查询会话实体。
     */
    public AgentSessionEntity getSessionEntity(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    /**
     * 开启新一轮 turn。
     * 这里会先回收过期 turn，再拒绝仍处于有效租约内的并发请求。
     */
    @Transactional
    public StartedTurn startTurn(String sessionId, String userMessage) {
        AgentSessionEntity session = getSessionEntityForUpdate(sessionId);
        LocalDateTime now = LocalDateTime.now();

        // 1. 先回收已经过期的运行中 turn，避免陈旧执行占住会话。
        reclaimExpiredRunningTurns(sessionId, now);
        // 2. 如果仍存在有效运行中的 turn，直接拒绝新请求。
        rejectActiveRunningTurns(sessionId, now);

        // 3. 创建本轮 turn，并为后续 assistant 回复预留统一归属。
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(UUID.randomUUID().toString());
        turn.setSession(session);
        turn.setStatus(AgentTurnStatus.RUNNING);
        turn.setStartedAt(now);
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(now.plus(TURN_LEASE_DURATION));

        AgentTurnEntity savedTurn = turnRepository.save(turn);

        // 4. 先落用户消息，再刷新会话更新时间。
        appendMessage(session, savedTurn, AgentMessageEntity.MessageRole.USER, userMessage);
        touchSession(session, now);
        sessionRepository.save(session);
        return new StartedTurn(session, savedTurn.getTurnId());
    }

    /**
     * 将 turn 标记为完成，并持久化 assistant 回复与最新记忆。
     */
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

        // 1. 先写记忆，再写 assistant 消息，确保本轮结果完整闭环。
        if (memorySnapshot != null) {
            memoryService.writeMemory(session, memorySnapshot);
        }
        appendMessage(session, turn, AgentMessageEntity.MessageRole.ASSISTANT, reply);

        // 2. 最后推进 turn 终态，避免中途失败时出现“已完成但无消息”的不一致状态。
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

    /**
     * 将 turn 标记为失败。
     * 只有仍处于可失败状态的 turn 才允许推进到 FAILED。
     */
    @Transactional
    public AgentTurnEntity failTurn(String turnId, Exception error) {
        AgentTurnEntity turn = getTurnEntityForUpdate(turnId);
        if (!isFailSafe(turn)) {
            return turn;
        }
        AgentSessionEntity session = getSessionEntityForUpdate(turn.getSession().getSessionId());
        LocalDateTime now = LocalDateTime.now();

        // 失败分支只更新 turn 元数据，不追加 assistant 消息或记忆。
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

    /**
     * 查询会话内的全部消息，并按展示顺序返回。
     */
    public List<AgentMessageDTO> getMessages(String sessionId) {
        return messageRepository.findBySession_SessionIdOrderByMessageOrderAsc(sessionId).stream()
            .map(this::toMessageDTO)
            .toList();
    }

    /**
     * 解析会话绑定的知识库 ID 列表。
     */
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

    /**
     * 以加锁方式读取会话实体，供修改流程使用。
     */
    private AgentSessionEntity getSessionEntityForUpdate(String sessionId) {
        return sessionRepository.findBySessionIdForUpdate(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    /**
     * 以加锁方式读取 turn 实体，保证终态更新串行化。
     */
    private AgentTurnEntity getTurnEntityForUpdate(String turnId) {
        return turnRepository.findByTurnIdForUpdate(turnId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "未找到 Agent turn: " + turnId));
    }

    /**
     * 回收租约已过期但仍停留在 RUNNING 的旧 turn。
     */
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

    /**
     * 拒绝当前仍有有效运行中 turn 的并发请求。
     */
    private void rejectActiveRunningTurns(String sessionId, LocalDateTime now) {
        boolean hasActiveRunningTurn = turnRepository.findBySession_SessionIdAndStatusOrderByCreatedAtAsc(
            sessionId,
            AgentTurnStatus.RUNNING
        ).stream().anyMatch(turn -> !isLeaseExpired(turn, now));

        if (hasActiveRunningTurn) {
            throw new BusinessException(ErrorCode.AGENT_TURN_CONFLICT, "当前会话已有运行中的 turn");
        }
    }

    /**
     * 判断 turn 的租约是否已经过期。
     */
    private boolean isLeaseExpired(AgentTurnEntity turn, LocalDateTime now) {
        return turn.getLeaseExpiresAt() != null && !turn.getLeaseExpiresAt().isAfter(now);
    }

    /**
     * 将旧 turn 回收为 ABORTED，供后续新 turn 接管会话。
     */
    private void markTurnAborted(AgentTurnEntity turn, LocalDateTime now, String reason) {
        turn.setStatus(AgentTurnStatus.ABORTED);
        turn.setCompletionMode(null);
        turn.setErrorMessage(reason);
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(null);
        turn.setFinishedAt(now);
        turnRepository.save(turn);
    }

    /**
     * 判断 turn 是否还允许走完成态。
     */
    private boolean isCompletable(AgentTurnEntity turn) {
        return turn.getStatus() == AgentTurnStatus.RUNNING || turn.getStatus() == AgentTurnStatus.CREATED;
    }

    /**
     * 判断 turn 是否还允许被标记为失败。
     */
    private boolean isFailSafe(AgentTurnEntity turn) {
        return turn.getStatus() == AgentTurnStatus.RUNNING || turn.getStatus() == AgentTurnStatus.CREATED;
    }

    /**
     * 校验当前 turn 是否还能完成。
     * 已被回收的旧 turn 要显式抛出过期错误，避免调用方误以为成功。
     */
    private void ensureCompletable(AgentTurnEntity turn) {
        if (isCompletable(turn)) {
            return;
        }
        if (turn.getStatus() == AgentTurnStatus.ABORTED) {
            throw new BusinessException(ErrorCode.AGENT_TURN_EXPIRED, "当前 turn 已过期并被回收");
        }
        throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "turn 已处于终态: " + turn.getStatus());
    }

    /**
     * 追加一条消息，并在短事务内分配稳定的消息顺序号。
     */
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

    /**
     * 计算会话中的下一条消息顺序号。
     */
    private int nextMessageOrder(String sessionId) {
        return messageRepository.findTopBySession_SessionIdOrderByMessageOrderDesc(sessionId)
            .map(AgentMessageEntity::getMessageOrder)
            .orElse(0) + 1;
    }

    /**
     * 刷新会话更新时间。
     */
    private void touchSession(AgentSessionEntity session, LocalDateTime now) {
        session.setUpdatedAt(now);
    }

    /**
     * 将会话实体转换为接口 DTO。
     */
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

    /**
     * 将消息实体转换为接口 DTO。
     */
    private AgentMessageDTO toMessageDTO(AgentMessageEntity message) {
        return new AgentMessageDTO(
            message.getRoleString(),
            message.getContent(),
            message.getMessageOrder(),
            message.getCreatedAt()
        );
    }

    /**
     * 生成会话标题。
     * 优先使用显式标题，否则用 goal 做一个截断后的摘要。
     */
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

    /**
     * 序列化 JSON 字段。
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "序列化 Agent 会话失败");
        }
    }

    /**
     * 清洗异常信息，避免过长或包含换行。
     */
    private String sanitize(Exception error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "unknown_error";
        }
        String message = error.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 1000 ? message.substring(0, 1000) + "..." : message;
    }

    /**
     * turn 启动结果，供编排层继续使用会话与 turnId。
     */
    public record StartedTurn(AgentSessionEntity session, String turnId) {
    }
}
