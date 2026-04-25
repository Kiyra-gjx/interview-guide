package interview.guide.modules.agent.service;

import interview.guide.modules.agent.guardrail.AgentGuardrailAction;
import interview.guide.modules.agent.guardrail.AgentGuardrailCode;
import interview.guide.modules.agent.guardrail.AgentGuardrailResolution;
import interview.guide.modules.agent.guardrail.AgentGuardrailResult;
import interview.guide.modules.agent.guardrail.AgentGuardrailStage;
import interview.guide.modules.agent.model.AgentApprovalDTO;
import interview.guide.modules.agent.model.AgentApprovalStatus;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentMessageEntity;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.model.AgentTurnDetailDTO;
import interview.guide.modules.agent.model.AgentTurnEntity;
import interview.guide.modules.agent.model.AgentTurnStatus;
import interview.guide.modules.agent.model.AgentTurnSummaryDTO;
import interview.guide.modules.agent.repository.AgentMessageRepository;
import interview.guide.modules.agent.repository.AgentTurnRepository;
import interview.guide.modules.agent.tool.AgentToolRiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentWorkbenchServiceTest {

    @Mock
    private AgentSessionService sessionService;
    @Mock
    private AgentTurnRepository turnRepository;
    @Mock
    private AgentMessageRepository messageRepository;
    @Mock
    private AgentTraceService traceService;
    @Mock
    private AgentApprovalService approvalService;

    private AgentWorkbenchService workbenchService;

    @BeforeEach
    void setUp() {
        workbenchService = new AgentWorkbenchService(
            sessionService,
            turnRepository,
            messageRepository,
            traceService,
            approvalService
        );
    }

    @Test
    @DisplayName("should build session turn summaries with user and assistant previews")
    void shouldBuildSessionTurnSummariesWithUserAndAssistantPreviews() {
        String sessionId = "session-workbench";
        AgentSessionEntity session = createSession(sessionId);
        AgentTurnEntity latestTurn = createTurn(
            "turn-latest",
            session,
            AgentTurnStatus.WAITING_APPROVAL,
            AgentCompletionMode.WAITING_APPROVAL,
            LocalDateTime.parse("2026-04-25T15:10:00")
        );
        AgentTurnEntity olderTurn = createTurn(
            "turn-older",
            session,
            AgentTurnStatus.COMPLETED,
            AgentCompletionMode.SUCCESS,
            LocalDateTime.parse("2026-04-25T15:00:00")
        );

        when(sessionService.getSessionEntity(sessionId)).thenReturn(session);
        when(turnRepository.findBySession_SessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(List.of(latestTurn, olderTurn));
        when(messageRepository.findBySession_SessionIdOrderByMessageOrderAsc(sessionId)).thenReturn(List.of(
            createMessage(olderTurn, AgentMessageEntity.MessageRole.USER, "先分析我的简历重点", 1),
            createMessage(olderTurn, AgentMessageEntity.MessageRole.ASSISTANT, "已经整理出三个重点方向。", 2),
            createMessage(latestTurn, AgentMessageEntity.MessageRole.USER, "请直接删除这份简历", 3),
            createMessage(latestTurn, AgentMessageEntity.MessageRole.ASSISTANT, "高风险操作，等待审批。", 4)
        ));

        List<AgentTurnSummaryDTO> summaries = workbenchService.getSessionTurns(sessionId);

        assertThat(summaries).hasSize(2);
        assertThat(summaries.getFirst().turnId()).isEqualTo("turn-latest");
        assertThat(summaries.getFirst().status()).isEqualTo(AgentTurnStatus.WAITING_APPROVAL);
        assertThat(summaries.getFirst().userMessagePreview()).isEqualTo("请直接删除这份简历");
        assertThat(summaries.getFirst().assistantReplyPreview()).isEqualTo("高风险操作，等待审批。");
        assertThat(summaries.get(1).turnId()).isEqualTo("turn-older");
        assertThat(summaries.get(1).assistantReplyPreview()).isEqualTo("已经整理出三个重点方向。");
        verify(messageRepository).findBySession_SessionIdOrderByMessageOrderAsc(sessionId);
        verify(messageRepository, never()).findByTurn_TurnIdOrderByMessageOrderAsc("turn-latest");
        verify(messageRepository, never()).findByTurn_TurnIdOrderByMessageOrderAsc("turn-older");
    }

    @Test
    @DisplayName("should build turn detail with trace approvals and aggregated guardrails")
    void shouldBuildTurnDetailWithTraceApprovalsAndAggregatedGuardrails() {
        String turnId = "turn-detail";
        AgentSessionEntity session = createSession("session-detail");
        AgentTurnEntity turn = createTurn(
            turnId,
            session,
            AgentTurnStatus.COMPLETED,
            AgentCompletionMode.DEGRADED,
            LocalDateTime.parse("2026-04-25T16:00:00")
        );
        AgentGuardrailResult guardrailResult = new AgentGuardrailResult(
            AgentGuardrailStage.TOOL,
            AgentGuardrailCode.TOOL_REQUIRES_APPROVAL,
            AgentGuardrailAction.REQUIRE_APPROVAL,
            AgentGuardrailResolution.WAIT_FOR_APPROVAL,
            "高风险工具必须先审批后执行"
        );
        AgentTraceDTO trace = new AgentTraceDTO(
            1,
            "need approval",
            "delete_resume",
            "{\"resumeId\":42}",
            "{\"kind\":\"approval_pending\"}",
            null,
            "waiting approval",
            null,
            null,
            List.of(guardrailResult),
            AgentExecutionState.WAITING_APPROVAL,
            null,
            LocalDateTime.parse("2026-04-25T16:00:05")
        );
        AgentApprovalDTO approval = new AgentApprovalDTO(
            "approval-1",
            session.getSessionId(),
            turnId,
            "delete_resume",
            AgentToolRiskLevel.REQUIRES_APPROVAL,
            AgentApprovalStatus.PENDING,
            "高风险工具必须先审批后执行",
            LocalDateTime.parse("2026-04-25T16:10:00"),
            null,
            LocalDateTime.parse("2026-04-25T16:00:06")
        );

        when(turnRepository.findByTurnId(turnId)).thenReturn(Optional.of(turn));
        when(messageRepository.findByTurn_TurnIdOrderByMessageOrderAsc(turnId)).thenReturn(List.of(
            createMessage(turn, AgentMessageEntity.MessageRole.USER, "帮我删掉当前简历", 1),
            createMessage(turn, AgentMessageEntity.MessageRole.ASSISTANT, "该操作需要审批，已暂停执行。", 2)
        ));
        when(traceService.getTurnTrace(turnId)).thenReturn(List.of(trace));
        when(approvalService.getTurnApprovals(turnId)).thenReturn(List.of(approval));

        AgentTurnDetailDTO detail = workbenchService.getTurnDetail(turnId);

        assertThat(detail.turn().turnId()).isEqualTo(turnId);
        assertThat(detail.messages()).hasSize(2);
        assertThat(detail.traceSteps()).containsExactly(trace);
        assertThat(detail.approvals()).containsExactly(approval);
        assertThat(detail.guardrailResults()).containsExactly(guardrailResult);
        assertThat(detail.turn().assistantReplyPreview()).isEqualTo("该操作需要审批，已暂停执行。");
    }

    /**
     * 构造一个最小会话实体，供工作台只读聚合测试复用。
     */
    private AgentSessionEntity createSession(String sessionId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setTitle("Workbench Session");
        session.setGoal("准备一轮 Java 面试");
        return session;
    }

    /**
     * 构造一个最小 turn 实体，并补齐工作台会消费的时间字段。
     */
    private AgentTurnEntity createTurn(
        String turnId,
        AgentSessionEntity session,
        AgentTurnStatus status,
        AgentCompletionMode completionMode,
        LocalDateTime createdAt
    ) {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setTurnId(turnId);
        turn.setSession(session);
        turn.setStatus(status);
        turn.setCompletionMode(completionMode);
        turn.setCreatedAt(createdAt);
        turn.setStartedAt(createdAt);
        turn.setFinishedAt(createdAt.plusSeconds(8));
        return turn;
    }

    /**
     * 构造归属于指定 turn 的消息实体。
     */
    private AgentMessageEntity createMessage(
        AgentTurnEntity turn,
        AgentMessageEntity.MessageRole role,
        String content,
        int order
    ) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setSession(turn.getSession());
        message.setTurn(turn);
        message.setRole(role);
        message.setContent(content);
        message.setMessageOrder(order);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }
}
