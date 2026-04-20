package interview.guide.modules.agent.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentChatRequest;
import interview.guide.modules.agent.model.AgentChatResponse;
import interview.guide.modules.agent.model.AgentCompletionMode;
import interview.guide.modules.agent.model.AgentDecisionDTO;
import interview.guide.modules.agent.model.AgentExecutionState;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentMessageDTO;
import interview.guide.modules.agent.model.AgentSessionEntity;
import interview.guide.modules.agent.model.AgentStepTraceEntity;
import interview.guide.modules.agent.model.AgentTraceDTO;
import interview.guide.modules.agent.support.AgentToolResult;
import interview.guide.modules.agent.tool.AgentTool;
import interview.guide.modules.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private AgentSessionService sessionService;
    @Mock
    private AgentMemoryService memoryService;
    @Mock
    private AgentTraceService traceService;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private AgentTool tool;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        orchestrator = new AgentOrchestrator(
            chatClientBuilder,
            structuredOutputInvoker,
            toolRegistry,
            sessionService,
            memoryService,
            traceService,
            promptService
        );
    }

    @Test
    @DisplayName("should degrade invalid tool decisions without using hallucinated direct answers")
    void shouldDegradeInvalidToolDecisionWithoutUsingHallucinatedDirectAnswer() {
        String sessionId = "session-invalid-tool";
        String turnId = "turn-invalid-tool";
        AgentChatRequest request = new AgentChatRequest("帮我看一下简历重点");
        AgentSessionEntity session = createSession(sessionId, "优化求职材料", 88L);
        AgentMemorySnapshot memory = createMemory();
        List<AgentTraceDTO> trace = List.of(createTrace("missing_tool", AgentExecutionState.FAILED));
        List<AgentMessageDTO> messages = List.of(createMessage("user", request.message(), 1));

        when(sessionService.startTurn(sessionId, request.message()))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(memoryService.readMemory(session)).thenReturn(memory);
        when(traceService.estimateNextStepIndex(sessionId)).thenReturn(1);
        when(toolRegistry.describeTools()).thenReturn("- get_resume_profile");
        when(promptService.buildDecisionSystemPrompt(anyString(), anyString())).thenReturn("decision-system");
        when(promptService.buildDecisionUserPrompt(session.getGoal(), request.message(), memory, 1)).thenReturn("decision-user");
        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(new AgentDecisionDTO(
            true,
            "missing_tool",
            Map.of("resumeId", 88L),
            "need tool",
            "hallucinated direct answer"
        ));
        when(toolRegistry.findTool("missing_tool")).thenReturn(Optional.empty());
        when(sessionService.getSessionEntity(sessionId)).thenReturn(session);
        when(traceService.getTrace(sessionId)).thenReturn(trace);
        when(sessionService.getMessages(sessionId)).thenReturn(messages);

        AgentChatResponse response = orchestrator.chat(sessionId, request);

        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(traceService).recordRejectedToolDecision(
            eq(turnId),
            eq("need tool"),
            eq("missing_tool"),
            eq(Map.of("resumeId", 88L)),
            errorCaptor.capture(),
            replyCaptor.capture()
        );
        verify(sessionService).completeTurn(
            eq(turnId),
            eq(replyCaptor.getValue()),
            eq(memory),
            eq(AgentCompletionMode.DEGRADED)
        );
        verify(sessionService, never()).failTurn(anyString(), any(Exception.class));

        assertThat(errorCaptor.getValue()).contains("toolName");
        assertThat(replyCaptor.getValue()).isNotBlank();
        assertThat(replyCaptor.getValue()).isNotEqualTo("hallucinated direct answer");
        assertThat(response.reply()).isEqualTo(replyCaptor.getValue());
        assertThat(response.memory()).isEqualTo(memory);
    }

    @Test
    @DisplayName("should keep trace session and reply semantics aligned when tool execution fails")
    void shouldKeepTraceSessionAndReplyAlignedWhenToolExecutionFails() {
        String sessionId = "session-tool-failure";
        String turnId = "turn-tool-failure";
        AgentChatRequest request = new AgentChatRequest("帮我总结这份简历");
        AgentSessionEntity session = createSession(sessionId, "准备面试", 42L);
        AgentMemorySnapshot memory = createMemory();
        AgentStepTraceEntity stepTrace = new AgentStepTraceEntity();
        List<AgentTraceDTO> trace = List.of(createTrace("get_resume_profile", AgentExecutionState.FAILED));
        List<AgentMessageDTO> messages = List.of(createMessage("user", request.message(), 1));

        when(sessionService.startTurn(sessionId, request.message()))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(memoryService.readMemory(session)).thenReturn(memory);
        when(traceService.estimateNextStepIndex(sessionId)).thenReturn(2);
        when(toolRegistry.describeTools()).thenReturn("- get_resume_profile");
        when(promptService.buildDecisionSystemPrompt(anyString(), anyString())).thenReturn("decision-system");
        when(promptService.buildDecisionUserPrompt(session.getGoal(), request.message(), memory, 2)).thenReturn("decision-user");
        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(new AgentDecisionDTO(
            true,
            "get_resume_profile",
            Map.of(),
            "need resume context",
            null
        ));
        when(toolRegistry.findTool("get_resume_profile")).thenReturn(Optional.of(tool));
        when(tool.name()).thenReturn("get_resume_profile");
        when(tool.requiredInputs()).thenReturn(List.of("resumeId"));
        when(traceService.startToolStep(
            eq(turnId),
            eq("need resume context"),
            eq("get_resume_profile"),
            anyMap()
        )).thenReturn(stepTrace);
        when(sessionService.readKnowledgeBaseIds(session)).thenReturn(List.of());
        when(tool.execute(anyMap(), any())).thenThrow(new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED, "tool boom"));
        when(sessionService.getSessionEntity(sessionId)).thenReturn(session);
        when(traceService.getTrace(sessionId)).thenReturn(trace);
        when(sessionService.getMessages(sessionId)).thenReturn(messages);

        AgentChatResponse response = orchestrator.chat(sessionId, request);

        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        verify(traceService).failToolStep(eq(stepTrace), any(Exception.class), replyCaptor.capture());
        verify(sessionService).completeTurn(
            eq(turnId),
            eq(replyCaptor.getValue()),
            eq(memory),
            eq(AgentCompletionMode.DEGRADED)
        );
        verify(traceService, never()).completeToolStep(any(), any(AgentToolResult.class));
        verify(memoryService, never()).updateAfterTool(any(), anyString(), any());
        verify(sessionService, never()).failTurn(anyString(), any(Exception.class));

        assertThat(replyCaptor.getValue()).isNotBlank();
        assertThat(response.reply()).isEqualTo(replyCaptor.getValue());
        assertThat(response.memory()).isEqualTo(memory);
    }

    @Test
    @DisplayName("should not mark a completed turn as failed when response assembly throws")
    void shouldNotMarkCompletedTurnAsFailedWhenResponseAssemblyThrows() {
        String sessionId = "session-response-error";
        String turnId = "turn-response-error";
        AgentChatRequest request = new AgentChatRequest("直接回答");
        AgentSessionEntity session = createSession(sessionId, "准备面试", 42L);
        AgentMemorySnapshot memory = createMemory();

        when(sessionService.startTurn(sessionId, request.message()))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(memoryService.readMemory(session))
            .thenReturn(memory)
            .thenThrow(new RuntimeException("response_assembly_failed"));
        when(traceService.estimateNextStepIndex(sessionId)).thenReturn(1);
        when(toolRegistry.describeTools()).thenReturn("- get_resume_profile");
        when(promptService.buildDecisionSystemPrompt(anyString(), anyString())).thenReturn("decision-system");
        when(promptService.buildDecisionUserPrompt(session.getGoal(), request.message(), memory, 1)).thenReturn("decision-user");
        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(new AgentDecisionDTO(
            false,
            null,
            Map.of(),
            "answer directly",
            "直接回复"
        ));
        when(sessionService.getSessionEntity(sessionId)).thenReturn(session);

        assertThatThrownBy(() -> orchestrator.chat(sessionId, request))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("response_assembly_failed");

        verify(sessionService).completeTurn(
            eq(turnId),
            eq("直接回复"),
            eq(memory),
            eq(AgentCompletionMode.SUCCESS)
        );
        verify(sessionService, never()).failTurn(anyString(), any(Exception.class));
    }

    @Test
    @DisplayName("should surface a stale turn error instead of returning a successful reply")
    void shouldSurfaceStaleTurnErrorInsteadOfReturningSuccessfulReply() {
        String sessionId = "session-stale-turn";
        String turnId = "turn-stale-turn";
        AgentChatRequest request = new AgentChatRequest("直接回答");
        AgentSessionEntity session = createSession(sessionId, "准备面试", 42L);
        AgentMemorySnapshot memory = createMemory();

        when(sessionService.startTurn(sessionId, request.message()))
            .thenReturn(new AgentSessionService.StartedTurn(session, turnId));
        when(memoryService.readMemory(session)).thenReturn(memory);
        when(traceService.estimateNextStepIndex(sessionId)).thenReturn(1);
        when(toolRegistry.describeTools()).thenReturn("- get_resume_profile");
        when(promptService.buildDecisionSystemPrompt(anyString(), anyString())).thenReturn("decision-system");
        when(promptService.buildDecisionUserPrompt(session.getGoal(), request.message(), memory, 1)).thenReturn("decision-user");
        when(structuredOutputInvoker.invoke(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any()
        )).thenReturn(new AgentDecisionDTO(
            false,
            null,
            Map.of(),
            "answer directly",
            "直接回复"
        ));
        when(sessionService.completeTurn(
            eq(turnId),
            eq("直接回复"),
            eq(memory),
            eq(AgentCompletionMode.SUCCESS)
        )).thenThrow(new BusinessException(ErrorCode.AGENT_TURN_EXPIRED, "当前 turn 已过期并被回收"));

        assertThatThrownBy(() -> orchestrator.chat(sessionId, request))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_TURN_EXPIRED.getCode()));

        verify(sessionService).failTurn(eq(turnId), any(Exception.class));
        verify(sessionService, never()).getSessionEntity(sessionId);
    }

    private AgentSessionEntity createSession(String sessionId, String goal, Long resumeId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setGoal(goal);
        session.setResumeId(resumeId);
        return session;
    }

    private AgentMemorySnapshot createMemory() {
        return new AgentMemorySnapshot(
            "prepare interview",
            "goal_received",
            List.of("fact-1"),
            List.of(),
            "need more context"
        );
    }

    private AgentTraceDTO createTrace(String selectedTool, AgentExecutionState status) {
        return new AgentTraceDTO(
            1,
            "decision",
            selectedTool,
            "{}",
            "{}",
            "observation",
            status,
            null,
            LocalDateTime.now()
        );
    }

    private AgentMessageDTO createMessage(String role, String content, int order) {
        return new AgentMessageDTO(role, content, order, LocalDateTime.now());
    }
}
