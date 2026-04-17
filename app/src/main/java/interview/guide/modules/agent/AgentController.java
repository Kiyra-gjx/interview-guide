package interview.guide.modules.agent;

import interview.guide.common.result.Result;
import interview.guide.modules.agent.model.*;
import interview.guide.modules.agent.service.AgentMemoryService;
import interview.guide.modules.agent.service.AgentOrchestrator;
import interview.guide.modules.agent.service.AgentSessionService;
import interview.guide.modules.agent.service.AgentTraceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 控制器。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AgentSessionService sessionService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentTraceService traceService;
    private final AgentMemoryService memoryService;

    @PostMapping("/api/agent/sessions")
    public Result<AgentSessionDTO> createSession(@Valid @RequestBody CreateAgentSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    @GetMapping("/api/agent/sessions/{sessionId}")
    public Result<AgentSessionDTO> getSession(@PathVariable String sessionId) {
        return Result.success(sessionService.getSession(sessionId));
    }

    @PostMapping("/api/agent/sessions/{sessionId}/chat")
    public Result<AgentChatResponse> chat(
        @PathVariable String sessionId,
        @Valid @RequestBody AgentChatRequest request
    ) {
        log.info("收到 Agent chat 请求: sessionId={}", sessionId);
        return Result.success(agentOrchestrator.chat(sessionId, request));
    }

    @GetMapping("/api/agent/sessions/{sessionId}/trace")
    public Result<List<AgentTraceDTO>> getTrace(@PathVariable String sessionId) {
        return Result.success(traceService.getTrace(sessionId));
    }

    @GetMapping("/api/agent/sessions/{sessionId}/memory")
    public Result<AgentMemorySnapshot> getMemory(@PathVariable String sessionId) {
        return Result.success(memoryService.readMemory(sessionService.getSessionEntity(sessionId)));
    }
}
