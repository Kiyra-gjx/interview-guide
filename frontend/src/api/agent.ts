import { request } from './request';
import type {
  AgentApproval,
  AgentChatRequest,
  AgentChatResponse,
  AgentMemorySnapshot,
  AgentSession,
  AgentTurnDetail,
  AgentTurnSummary,
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

  async getTurns(sessionId: string): Promise<AgentTurnSummary[]> {
    return request.get<AgentTurnSummary[]>(`/api/agent/sessions/${sessionId}/turns`);
  },

  async getTurnDetail(turnId: string): Promise<AgentTurnDetail> {
    return request.get<AgentTurnDetail>(`/api/agent/turns/${turnId}`);
  },

  async getMemory(sessionId: string): Promise<AgentMemorySnapshot> {
    return request.get<AgentMemorySnapshot>(`/api/agent/sessions/${sessionId}/memory`);
  },

  async getApprovals(sessionId: string): Promise<AgentApproval[]> {
    return request.get<AgentApproval[]>(`/api/agent/sessions/${sessionId}/approvals`);
  },

  async approveApproval(approvalId: string): Promise<AgentChatResponse> {
    return request.post<AgentChatResponse>(`/api/agent/approvals/${approvalId}/approve`);
  },

  async rejectApproval(approvalId: string): Promise<AgentChatResponse> {
    return request.post<AgentChatResponse>(`/api/agent/approvals/${approvalId}/reject`);
  },
};
