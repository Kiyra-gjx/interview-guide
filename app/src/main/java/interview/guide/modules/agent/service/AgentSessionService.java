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
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 会话与 turn 生命周期服务。
 * 负责会话创建、消息持久化、turn 终态流转以及并发冲突处理。
 */
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private static final Duration TURN_LEASE_DURATION = Duration.ofMinutes(10);
    private static final List<AgentTurnStatus> OPEN_TURN_STATUSES = List.of(
        AgentTurnStatus.RUNNING,
        AgentTurnStatus.WAITING_APPROVAL
    );

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentTurnRepository turnRepository;
    private final ResumeRepository resumeRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper;
    private final AgentMemoryService memoryService;

    /**
     * 创建新的 Agent 会话，并初始化首份记忆快照。
     */
    @Transactional
    public AgentSessionDTO createSession(CreateAgentSessionRequest request) {
        String goal = request.goal().trim();
        Long resumeId = validateResumeId(request.resumeId());
        List<Long> knowledgeBaseIds = validateKnowledgeBaseIds(request.knowledgeBaseIds());

        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(UUID.randomUUID().toString());
        session.setTitle(resolveTitle(request.title(), goal));
        session.setGoal(goal);
        session.setResumeId(resumeId);
        session.setKnowledgeBaseIdsJson(writeJson(knowledgeBaseIds));
        session.setStatus(AgentExecutionState.CREATED);
        memoryService.writeMemory(session, memoryService.createInitialSnapshot(goal));
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
        int reclaimedExpiredTurnCount = reclaimExpiredRunningTurns(sessionId, now);
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
        return new StartedTurn(session, savedTurn.getTurnId(), reclaimedExpiredTurnCount);
    }

    /**
     * 将 turn 停在等待审批状态，并先返回一条“等待审批”的 assistant 消息。
     */
    @Transactional
    public AgentTurnEntity waitForApproval(
        String turnId,
        String reply,
        LocalDateTime expiresAt,
        AgentCompletionMode completionMode
    ) {
        AgentTurnEntity turnSnapshot = getTurnEntity(turnId);
        ensureCompletable(turnSnapshot);
        LockedTurn lockedTurn = lockTurnForMutation(turnId, turnSnapshot.getSession().getSessionId());
        AgentTurnEntity turn = lockedTurn.turn();
        ensureCompletable(turn);
        AgentSessionEntity session = lockedTurn.session();
        LocalDateTime now = LocalDateTime.now();

        appendMessage(session, turn, AgentMessageEntity.MessageRole.ASSISTANT, reply);
        turn.setStatus(AgentTurnStatus.WAITING_APPROVAL);
        turn.setCompletionMode(completionMode);
        turn.setErrorMessage(null);
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(expiresAt);
        turn.setFinishedAt(null);

        touchSession(session, now);
        sessionRepository.save(session);
        return turnRepository.save(turn);
    }

    /**
     * 将已经通过审批的 turn 从 WAITING_APPROVAL 恢复为 RUNNING。
     * 这里只推进本地持久化状态，不在这里执行远程工具。
     */
    @Transactional
    public AgentTurnEntity resumeTurnFromApproval(String turnId) {
        AgentTurnEntity turnSnapshot = getTurnEntity(turnId);
        ensureWaitingApproval(turnSnapshot);
        LockedTurn lockedTurn = lockTurnForMutation(turnId, turnSnapshot.getSession().getSessionId());
        AgentTurnEntity turn = lockedTurn.turn();
        ensureWaitingApproval(turn);
        AgentSessionEntity session = lockedTurn.session();
        LocalDateTime now = LocalDateTime.now();

        return activateRunningTurn(turn, session, now);
    }

    /**
     * 为审批恢复链路尝试抢占 turn 的执行权。
     * WAITING_APPROVAL 可以直接恢复；已经变成 RUNNING 的旧 turn 只有在租约过期后才能被回收。
     */
    @Transactional
    public ApprovedTurnClaim claimTurnForApprovedExecution(String turnId) {
        AgentTurnEntity turnSnapshot = getTurnEntity(turnId);
        LocalDateTime now = LocalDateTime.now();
        if (!canClaimApprovedExecution(turnSnapshot, now)) {
            return new ApprovedTurnClaim(false, turnSnapshot);
        }
        LockedTurn lockedTurn = lockTurnForMutation(turnId, turnSnapshot.getSession().getSessionId());
        AgentTurnEntity turn = lockedTurn.turn();
        if (!canClaimApprovedExecution(turn, now)) {
            return new ApprovedTurnClaim(false, turn);
        }
        return new ApprovedTurnClaim(true, activateRunningTurn(turn, lockedTurn.session(), now));
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
        AgentTurnEntity turnSnapshot = getTurnEntity(turnId);
        ensureCompletable(turnSnapshot);
        LockedTurn lockedTurn = lockTurnForMutation(turnId, turnSnapshot.getSession().getSessionId());
        AgentTurnEntity turn = lockedTurn.turn();
        ensureCompletable(turn);
        AgentSessionEntity session = lockedTurn.session();
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
        return failTurn(turnId, error, null);
    }

    @Transactional
    public AgentTurnEntity failTurn(String turnId, Exception error, String assistantReply) {
        AgentTurnEntity turnSnapshot = getTurnEntity(turnId);
        if (!isFailSafe(turnSnapshot)) {
            return turnSnapshot;
        }
        LockedTurn lockedTurn = lockTurnForMutation(turnId, turnSnapshot.getSession().getSessionId());
        AgentTurnEntity turn = lockedTurn.turn();
        if (!isFailSafe(turn)) {
            return turn;
        }
        AgentSessionEntity session = lockedTurn.session();
        LocalDateTime now = LocalDateTime.now();

        // 失败分支只更新 turn 元数据，不追加 assistant 消息或记忆。
        turn.setStatus(AgentTurnStatus.FAILED);
        turn.setCompletionMode(null);
        turn.setErrorMessage(sanitize(error));
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(null);
        turn.setFinishedAt(now);
        appendAssistantReplyIfAbsent(session, turn, assistantReply);

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
     * 查询指定 turn 的消息增量。
     */
    public List<AgentMessageDTO> getTurnMessages(String turnId) {
        getTurnEntity(turnId);
        return messageRepository.findByTurn_TurnIdOrderByMessageOrderAsc(turnId).stream()
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
     * 按 turnId 读取 turn 实体，用于获取会话上下文。
     */
    private AgentTurnEntity getTurnEntity(String turnId) {
        return turnRepository.findByTurnId(turnId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TURN_NOT_FOUND, "未找到 Agent turn: " + turnId));
    }

    /**
     * 以加锁方式读取 turn 实体，保证终态更新串行化。
     */
    private AgentTurnEntity getTurnEntityForUpdate(String turnId) {
        return turnRepository.findByTurnIdForUpdate(turnId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TURN_NOT_FOUND, "未找到 Agent turn: " + turnId));
    }

    /**
     * 终态写入统一按 session -> turn 的顺序加锁，避免与 startTurn 的回收路径形成锁顺序反转。
     */
    private LockedTurn lockTurnForMutation(String turnId, String sessionId) {
        AgentSessionEntity session = getSessionEntityForUpdate(sessionId);
        AgentTurnEntity turn = getTurnEntityForUpdate(turnId);
        return new LockedTurn(session, turn);
    }

    /**
     * 回收租约已过期但仍停留在 RUNNING 的旧 turn。
     */
    private int reclaimExpiredRunningTurns(String sessionId, LocalDateTime now) {
        int reclaimedCount = 0;
        for (AgentTurnEntity openTurn : findOpenTurns(sessionId)) {
            if (openTurn.getStatus() == AgentTurnStatus.RUNNING && isLeaseExpired(openTurn, now)) {
                markTurnAborted(openTurn, now, "stale_running_turn");
                reclaimedCount++;
            }
        }
        return reclaimedCount;
    }

    /**
     * 拒绝当前仍有有效运行中 turn 的并发请求。
     */
    private void rejectActiveRunningTurns(String sessionId, LocalDateTime now) {
        boolean hasActiveRunningTurn = findOpenTurns(sessionId).stream()
            .anyMatch(turn -> !isLeaseExpired(turn, now));

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
     * 判断审批恢复链路此刻是否还能安全抢占该 turn。
     */
    private boolean canClaimApprovedExecution(AgentTurnEntity turn, LocalDateTime now) {
        if (turn.getStatus() == AgentTurnStatus.WAITING_APPROVAL) {
            return true;
        }
        if (turn.getStatus() != AgentTurnStatus.RUNNING) {
            return false;
        }
        return turn.getLeaseExpiresAt() == null || isLeaseExpired(turn, now);
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
        return turn.getStatus() == AgentTurnStatus.RUNNING
            || turn.getStatus() == AgentTurnStatus.CREATED
            || turn.getStatus() == AgentTurnStatus.WAITING_APPROVAL;
    }

    /**
     * 判断 turn 是否还允许被标记为失败。
     */
    private boolean isFailSafe(AgentTurnEntity turn) {
        return turn.getStatus() == AgentTurnStatus.RUNNING
            || turn.getStatus() == AgentTurnStatus.CREATED
            || turn.getStatus() == AgentTurnStatus.WAITING_APPROVAL;
    }

    private void ensureWaitingApproval(AgentTurnEntity turn) {
        if (turn.getStatus() == AgentTurnStatus.WAITING_APPROVAL) {
            return;
        }
        if (turn.getStatus() == AgentTurnStatus.ABORTED) {
            throw new BusinessException(ErrorCode.AGENT_TURN_EXPIRED, "当前 turn 已过期并被回收");
        }
        throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "turn 未处于等待审批状态: " + turn.getStatus());
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
    private void appendAssistantReplyIfAbsent(AgentSessionEntity session, AgentTurnEntity turn, String assistantReply) {
        if (assistantReply == null || assistantReply.isBlank()) {
            return;
        }
        String normalizedReply = assistantReply.trim();
        if (normalizedReply.isEmpty()) {
            return;
        }
        AgentMessageEntity latestMessage = messageRepository.findTopBySession_SessionIdOrderByMessageOrderDesc(session.getSessionId())
            .orElse(null);
        if (latestMessage != null
            && latestMessage.getTurn() != null
            && turn.getTurnId().equals(latestMessage.getTurn().getTurnId())
            && latestMessage.getRole() == AgentMessageEntity.MessageRole.ASSISTANT
            && normalizedReply.equals(latestMessage.getContent())) {
            return;
        }
        appendMessage(session, turn, AgentMessageEntity.MessageRole.ASSISTANT, normalizedReply);
    }

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
     * 统一读取会话下仍占用执行权的 turn。
     */
    private List<AgentTurnEntity> findOpenTurns(String sessionId) {
        return turnRepository.findBySession_SessionIdAndStatusInOrderByCreatedAtAsc(sessionId, OPEN_TURN_STATUSES);
    }

    /**
     * 刷新会话更新时间。
     */
    private void touchSession(AgentSessionEntity session, LocalDateTime now) {
        session.setUpdatedAt(now);
    }

    /**
     * 将 turn 激活为新的 RUNNING 租约。
     */
    private AgentTurnEntity activateRunningTurn(AgentTurnEntity turn, AgentSessionEntity session, LocalDateTime now) {
        turn.setStatus(AgentTurnStatus.RUNNING);
        turn.setCompletionMode(null);
        turn.setErrorMessage(null);
        turn.setHeartbeatAt(now);
        turn.setLeaseExpiresAt(now.plus(TURN_LEASE_DURATION));
        turn.setFinishedAt(null);

        touchSession(session, now);
        sessionRepository.save(session);
        return turnRepository.save(turn);
    }

    /**
     * 将会话实体转换为接口 DTO。
     */
    private AgentSessionDTO toSessionDTO(AgentSessionEntity session) {
        // 工作台会话 DTO 不再携带全量消息历史，消息查看统一走 turn/detail 读模型。
        return new AgentSessionDTO(
            session.getSessionId(),
            session.getTitle(),
            session.getGoal(),
            session.getResumeId(),
            readKnowledgeBaseIds(session),
            session.getStatus(),
            session.getCreatedAt(),
            session.getUpdatedAt()
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
     * 校验会话绑定的简历资源是否合法。
     */
    private Long validateResumeId(Long resumeId) {
        if (resumeId == null) {
            return null;
        }
        if (!resumeRepository.existsById(resumeId)) {
            throw new BusinessException(ErrorCode.AGENT_INVALID_INPUT, "resumeId 对应的简历不存在: " + resumeId);
        }
        return resumeId;
    }

    /**
     * 校验并规范化会话绑定的知识库资源。
     * 这里会去重，并阻止不存在或空值 ID 落库。
     */
    private List<Long> validateKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        if (knowledgeBaseIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.AGENT_INVALID_INPUT, "knowledgeBaseIds 不能包含空值");
        }

        List<Long> normalizedIds = new ArrayList<>(new LinkedHashSet<>(knowledgeBaseIds));
        Set<Long> existingIds = knowledgeBaseRepository.findAllById(normalizedIds).stream()
            .map(KnowledgeBaseEntity::getId)
            .collect(java.util.stream.Collectors.toSet());
        if (existingIds.size() != normalizedIds.size()) {
            List<Long> missingIds = normalizedIds.stream()
                .filter(id -> !existingIds.contains(id))
                .toList();
            throw new BusinessException(
                ErrorCode.AGENT_INVALID_INPUT,
                "knowledgeBaseIds 包含不存在的知识库: " + missingIds
            );
        }
        return normalizedIds;
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
    public record StartedTurn(AgentSessionEntity session, String turnId, int reclaimedExpiredTurnCount) {
        public StartedTurn(AgentSessionEntity session, String turnId) {
            this(session, turnId, 0);
        }
    }

    /**
     * 审批恢复场景下的抢占结果。
     * `claimed=true` 表示当前调用方拿到了继续执行该 turn 的权利。
     */
    public record ApprovedTurnClaim(boolean claimed, AgentTurnEntity turn) {
    }

    private record LockedTurn(AgentSessionEntity session, AgentTurnEntity turn) {
    }
}
