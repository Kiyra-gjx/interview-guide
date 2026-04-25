package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentMessageDTO;
import interview.guide.modules.agent.model.AgentMessageEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.model.AgentTurnDetailDTO;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnSummaryDTO;
import interview.guide.modules.agent.repository.AgentMessageRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Workbench 只读聚合服务。
 * 负责把 runtime 已经落库的数据组织成更适合前端工作台消费的 turn 级读模型。
 */
@Service
@RequiredArgsConstructor
public class AgentWorkbenchService {

    private static final int MESSAGE_PREVIEW_LIMIT = 120;

    private final AgentSessionService sessionService;
    private final AgentTurnRepository turnRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentTraceService traceService;
    private final AgentApprovalService approvalService;

    /**
     * 读取会话下的全部 turn 摘要，供工作台左侧 turn 列表使用。
     */
    public List<AgentTurnSummaryDTO> getSessionTurns(String sessionId) {
        // 1. 先校验 session 存在，避免把“会话不存在”误判成“没有 turn”。
        sessionService.getSessionEntity(sessionId);

        // 2. 再一次性读取 turn 与消息，避免 turn 列表路径落入 N+1 查询。
        List<AgentTurnEntity> turns = turnRepository.findBySession_SessionIdOrderByCreatedAtDesc(sessionId);
        Map<String, List<AgentMessageDTO>> turnMessages = buildTurnMessageMap(sessionId, turns);

        // 3. 最后按创建时间倒序组装摘要，保证工作台默认看到最近一轮执行。
        return turns.stream()
            .map(turn -> toTurnSummary(turn, turnMessages.getOrDefault(turn.getTurnId(), List.of())))
            .toList();
    }

    /**
     * 读取单个 turn 的工作台明细。
     */
    public AgentTurnDetailDTO getTurnDetail(String turnId) {
        // 1. 先读取 turn 主体，保证后续消息、trace、approval 都围绕同一个 turnId 聚合。
        AgentTurnEntity turn = getRequiredTurn(turnId);

        // 2. 再分别读取消息、trace 与审批，保持运行时主链路和工作台读模型解耦。
        List<AgentMessageDTO> messages = messageRepository.findByTurn_TurnIdOrderByMessageOrderAsc(turnId).stream()
            .map(this::toMessageDTO)
            .toList();
        List<AgentTraceDTO> traceSteps = traceService.getTurnTrace(turnId);
        List<AgentApprovalDTO> approvals = approvalService.getTurnApprovals(turnId);

        // 3. 最后从 trace 中聚合 guardrail 结果，给前端一个稳定的“本轮风险命中”视图。
        return new AgentTurnDetailDTO(
            toTurnSummary(turn, messages),
            messages,
            traceSteps,
            approvals,
            collectGuardrailResults(traceSteps)
        );
    }

    /**
     * 使用已经读取好的 turn 消息构造摘要，避免 turn 详情路径重复查询。
     */
    private AgentTurnSummaryDTO toTurnSummary(AgentTurnEntity turn, List<AgentMessageDTO> messages) {
        return new AgentTurnSummaryDTO(
            turn.getTurnId(),
            turn.getStatus(),
            turn.getCompletionMode(),
            previewMessage(messages, "user", false),
            previewMessage(messages, "assistant", true),
            turn.getErrorMessage(),
            turn.getCreatedAt(),
            turn.getStartedAt(),
            turn.getFinishedAt()
        );
    }

    /**
     * 按会话批量读取 turn 消息，并在内存中按 turnId 分组。
     * 这样可以把 turn 列表路径收敛成固定查询次数，同时继续复用现有消息 DTO 转换逻辑。
     */
    private Map<String, List<AgentMessageDTO>> buildTurnMessageMap(String sessionId, List<AgentTurnEntity> turns) {
        if (turns == null || turns.isEmpty()) {
            return Map.of();
        }

        // 1. 先记录当前会话真正要展示的 turnId，避免把非 turn 消息或脏数据混进摘要计算。
        Set<String> turnIds = new LinkedHashSet<>();
        Map<String, List<AgentMessageDTO>> messagesByTurnId = new LinkedHashMap<>();
        for (AgentTurnEntity turn : turns) {
            turnIds.add(turn.getTurnId());
            messagesByTurnId.put(turn.getTurnId(), new ArrayList<>());
        }

        // 2. 再一次性读取整个会话的消息，并按 turnId 写回对应桶里。
        for (AgentMessageEntity message : messageRepository.findBySession_SessionIdOrderByMessageOrderAsc(sessionId)) {
            if (message.getTurn() == null || message.getTurn().getTurnId() == null) {
                continue;
            }
            String turnId = message.getTurn().getTurnId();
            if (!turnIds.contains(turnId)) {
                continue;
            }
            messagesByTurnId.computeIfAbsent(turnId, ignored -> new ArrayList<>()).add(toMessageDTO(message));
        }

        // 3. 最后冻结列表，避免后续摘要拼装意外修改共享集合。
        Map<String, List<AgentMessageDTO>> readonlyMessagesByTurnId = new LinkedHashMap<>();
        for (Map.Entry<String, List<AgentMessageDTO>> entry : messagesByTurnId.entrySet()) {
            readonlyMessagesByTurnId.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return readonlyMessagesByTurnId;
    }

    /**
     * 从单个 turn 的消息列表中提取用户或助手的预览文本。
     * 用户消息取首条，助手消息取最后一条，符合 turn 的执行语义。
     */
    private String previewMessage(List<AgentMessageDTO> messages, String role, boolean pickLast) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        Comparator<AgentMessageDTO> comparator = Comparator.comparingInt(AgentMessageDTO::messageOrder);
        return (pickLast
            ? messages.stream().filter(message -> role.equals(message.role())).max(comparator)
            : messages.stream().filter(message -> role.equals(message.role())).min(comparator))
            .map(AgentMessageDTO::content)
            .map(this::clipPreview)
            .orElse(null);
    }

    /**
     * 将消息实体转换为只读 DTO。
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
     * 聚合单个 turn 下所有命中的 guardrail 结果，并按记录顺序去重。
     */
    private List<AgentGuardrailResult> collectGuardrailResults(List<AgentTraceDTO> traceSteps) {
        if (traceSteps == null || traceSteps.isEmpty()) {
            return List.of();
        }
        return traceSteps.stream()
            .flatMap(step -> step.guardrailResults().stream())
            .distinct()
            .toList();
    }

    /**
     * 按 turnId 读取 turn 实体，不存在时直接抛出标准业务异常。
     */
    private AgentTurnEntity getRequiredTurn(String turnId) {
        return turnRepository.findByTurnId(turnId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TURN_NOT_FOUND, "未找到 Agent turn: " + turnId));
    }

    /**
     * 控制工作台摘要中的消息长度，避免列表被超长文本撑坏。
     */
    private String clipPreview(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > MESSAGE_PREVIEW_LIMIT
            ? normalized.substring(0, MESSAGE_PREVIEW_LIMIT) + "..."
            : normalized;
    }
}
