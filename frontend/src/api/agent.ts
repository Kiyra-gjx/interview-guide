import { request } from './request';
import type {
  AgentChatRequest,
  AgentChatResponse,
  AgentMemorySnapshot,
  AgentSession,
  AgentTraceStep,
  CreateAgentSessionRequest,
} from '../types/agent';

export const agentApi = {
  async createSession(req: CreateAgentSessionRequest): Promise<AgentSession> {
    return request.post<AgentSession>('/api/agent/sessions', req);
  },

  async getSession(sessionId: string): Promise<AgentSession> {
    return request.get<AgentSession>(`/api/agent/sessions/${sessionId}`);
  },

  async sendMessage(sessionId: string, req: AgentChatRequest): Promise<AgentChatResponse> {
    return request.post<AgentChatResponse>(`/api/agent/sessions/${sessionId}/chat`, req, {
      timeout: 120000,
    });
  },

  async getTrace(sessionId: string): Promise<AgentTraceStep[]> {
    return request.get<AgentTraceStep[]>(`/api/agent/sessions/${sessionId}/trace`);
  },

  async getMemory(sessionId: string): Promise<AgentMemorySnapshot> {
    return request.get<AgentMemorySnapshot>(`/api/agent/sessions/${sessionId}/memory`);
  },
};
