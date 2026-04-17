package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.*;
import interview.guide.modules.agent.repository.AgentMessageRepository;
import interview.guide.modules.agent.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agent 会话服务。
 */
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
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
    public AgentMessageEntity addMessage(AgentSessionEntity session, AgentMessageEntity.MessageRole role, String content) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content == null ? "" : content.trim());
        message.setMessageOrder((int) messageRepository.countBySession_SessionId(session.getSessionId()) + 1);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
        return messageRepository.save(message);
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

    @Transactional
    public AgentSessionEntity saveSession(AgentSessionEntity session) {
        return sessionRepository.save(session);
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
}
