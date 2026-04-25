package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentApprovalEntity;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.repository.AgentApprovalRepository;
import interview.guide.modules.agent.support.AgentAssembledContext;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 仅批准状态管理。
 * 工具执行和 turn 恢复保留在编排器层。
 */
@Service
@RequiredArgsConstructor
public class AgentApprovalService {

    private static final Duration APPROVAL_TTL = Duration.ofMinutes(10);

    private final AgentApprovalRepository approvalRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建一条待审批记录，并冻结恢复执行所需的最小上下文。
     * 这里会持久化工具名、工具输入、最新用户消息和审批原因，供后续 approve / reject / expire 使用。
     */
    @Transactional
    public AgentApprovalDTO createPendingApproval(CreateApprovalRequest request) {
        AgentApprovalEntity approval = new AgentApprovalEntity();
        approval.setApprovalId(UUID.randomUUID().toString());
        approval.setSession(request.session());
        approval.setTurn(request.turn());
        approval.setTrace(request.trace());
        approval.setSelectedTool(request.selectedTool());
        approval.setRiskLevel(request.riskLevel());
        approval.setStatus(AgentApprovalStatus.PENDING);
        approval.setDecisionSummary(request.decisionSummary());
        approval.setToolInputJson(writeJson(request.toolInput()));
        approval.setLatestUserMessage(normalize(request.latestUserMessage()));
        approval.setAssembledContextJson(writeJson(request.assembledContext()));
        approval.setReason(normalize(request.reason()));
        approval.setExpiresAt(LocalDateTime.now().plus(APPROVAL_TTL));
        return toDTO(approvalRepository.save(approval), LocalDateTime.now());
    }

    /**
     * 读取路径将过期的待批准审批标准化为 EXPIRED，而不改变存储。
     */
    public List<AgentApprovalDTO> getSessionApprovals(String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        return approvalRepository.findBySession_SessionIdOrderByCreatedAtDesc(sessionId).stream()
            .map(approval -> toDTO(approval, now))
            .toList();
    }

    /**
     * 读取单个 turn 关联的全部审批记录。
     * 工作台明细只关心当前 turn 的审批历史，因此这里单独提供 turn 级投影视图。
     */
    public List<AgentApprovalDTO> getTurnApprovals(String turnId) {
        LocalDateTime now = LocalDateTime.now();
        return approvalRepository.findByTurn_TurnIdOrderByCreatedAtDesc(turnId).stream()
            .map(approval -> toDTO(approval, now))
            .toList();
    }

    /**
     * 读取单条审批的对外视图。
     * 这里只做状态投影，不负责推进 turn 或 trace。
     */
    public AgentApprovalDTO getApproval(String approvalId) {
        return toDTO(getRequiredApproval(approvalId), LocalDateTime.now());
    }

    /**
     * 将审批实体转换为 DTO。
     * 对外暴露的状态会把“已过期但尚未落库”的 PENDING 映射成 EXPIRED。
     */
    public AgentApprovalDTO toDTO(AgentApprovalEntity approval) {
        return toDTO(approval, LocalDateTime.now());
    }

    /**
     * 以数据库行锁方式读取审批实体。
     * 供 approve / reject / expire 这类需要串行推进状态的路径调用。
     */
    @Transactional
    public AgentApprovalEntity getRequiredApprovalForUpdate(String approvalId) {
        return approvalRepository.findByApprovalIdForUpdate(approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "审批记录不存在: " + approvalId));
    }

    /**
     * 在整个回调过程中保持审批行锁定。
     * 调用方可以把“状态检查 + turn/trace 推进”放在同一个原子区间内完成。
     */
    @Transactional
    public <T> T withLockedApproval(String approvalId, Function<AgentApprovalEntity, T> action) {
        return action.apply(getRequiredApprovalForUpdate(approvalId));
    }

    /**
     * 按 approvalId 读取审批实体，不加锁。
     * 适用于只读快照返回。
     */
    public AgentApprovalEntity getRequiredApproval(String approvalId) {
        return approvalRepository.findByApprovalId(approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "审批记录不存在: " + approvalId));
    }

    /**
     * 读取指定会话下所有仍处于 PENDING 的审批。
     * 主要供新 turn 启动前的过期清理使用。
     */
    public List<AgentApprovalEntity> getPendingApprovals(String sessionId) {
        return approvalRepository.findBySession_SessionIdAndStatusOrderByCreatedAtAsc(
            sessionId,
            AgentApprovalStatus.PENDING
        );
    }

    /**
     * 将审批推进到 APPROVED。
     */
    @Transactional
    public AgentApprovalDTO markApproved(AgentApprovalEntity approval) {
        return updateStatus(approval, AgentApprovalStatus.APPROVED);
    }

    /**
     * 将审批推进到 REJECTED。
     */
    @Transactional
    public AgentApprovalDTO markRejected(AgentApprovalEntity approval) {
        return updateStatus(approval, AgentApprovalStatus.REJECTED);
    }

    /**
     * 将审批推进到 EXPIRED。
     */
    @Transactional
    public AgentApprovalDTO markExpired(AgentApprovalEntity approval) {
        return updateStatus(approval, AgentApprovalStatus.EXPIRED);
    }

    /**
     * 判断一条审批是否已经超时。
     * 这里不修改数据库，只做纯判断。
     */
    public boolean isExpired(AgentApprovalEntity approval, LocalDateTime now) {
        return approval != null
            && approval.getStatus() == AgentApprovalStatus.PENDING
            && approval.getExpiresAt() != null
            && !approval.getExpiresAt().isAfter(now);
    }

    /**
     * 读取审批冻结下来的工具输入。
     * 批准后的工具恢复必须使用这份冻结输入，避免重新拼参导致行为漂移。
     */
    public Map<String, Object> readToolInput(AgentApprovalEntity approval) {
        if (approval == null || approval.getToolInputJson() == null || approval.getToolInputJson().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> toolInput = objectMapper.readValue(approval.getToolInputJson(), new TypeReference<>() {
            });
            return toolInput == null ? Map.of() : Map.copyOf(toolInput);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "读取审批输入失败");
        }
    }

    /**
     * 读取审批冻结下来的统一上下文快照。
     * 旧审批如果还没有持久化该字段，这里返回 null，由调用方决定回退策略。
     */
    public AgentAssembledContext readAssembledContext(AgentApprovalEntity approval) {
        if (approval == null || approval.getAssembledContextJson() == null || approval.getAssembledContextJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(approval.getAssembledContextJson(), AgentAssembledContext.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "读取审批上下文快照失败");
        }
    }

    /**
     * 统一推进审批状态并记录决定时间。
     */
    private AgentApprovalDTO updateStatus(AgentApprovalEntity approval, AgentApprovalStatus status) {
        approval.setStatus(status);
        approval.setDecidedAt(LocalDateTime.now());
        return toDTO(approvalRepository.save(approval), LocalDateTime.now());
    }

    /**
     * 在固定时间点下生成审批 DTO。
     * 传入 `now` 是为了让同一批读取共享同一时间视角。
     */
    private AgentApprovalDTO toDTO(AgentApprovalEntity approval, LocalDateTime now) {
        return new AgentApprovalDTO(
            approval.getApprovalId(),
            approval.getSession().getSessionId(),
            approval.getTurn().getTurnId(),
            approval.getSelectedTool(),
            approval.getRiskLevel(),
            resolveVisibleStatus(approval, now),
            approval.getReason(),
            approval.getExpiresAt(),
            approval.getDecidedAt(),
            approval.getCreatedAt()
        );
    }

    /**
     * 计算审批对外可见的状态。
     * 过期但还没被正式推进的 PENDING，在列表和详情里也会显示为 EXPIRED。
     */
    private AgentApprovalStatus resolveVisibleStatus(AgentApprovalEntity approval, LocalDateTime now) {
        return isExpired(approval, now) ? AgentApprovalStatus.EXPIRED : approval.getStatus();
    }

    /**
     * 序列化审批恢复所需的工具输入。
     */
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "序列化审批输入失败");
        }
    }

    /**
     * 统一清洗可选字符串字段，空白值直接转成 null。
     */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 创建待审批记录时需要冻结的最小上下文。
     */
    public record CreateApprovalRequest(
        AgentSessionEntity session,
        AgentTurnEntity turn,
        AgentStepTraceEntity trace,
        String selectedTool,
        AgentToolRiskLevel riskLevel,
        Map<String, Object> toolInput,
        String latestUserMessage,
        AgentAssembledContext assembledContext,
        String decisionSummary,
        String reason
    ) {
    }
}
