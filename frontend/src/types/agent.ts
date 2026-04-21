export type AgentExecutionState = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export type AgentTurnStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ABORTED';
export type AgentCompletionMode = 'SUCCESS' | 'DEGRADED';

export interface AgentMessage {
  role: 'user' | 'assistant';
  content: string;
  messageOrder: number;
  createdAt: string;
}

export interface AgentMemorySnapshot {
  userGoal: string;
  currentPhase: string;
  confirmedFacts: string[];
  usedTools: string[];
  nextFocus: string;
}

export interface AgentTraceStep {
  stepIndex: number;
  decisionSummary: string | null;
  selectedTool: string | null;
  toolInputJson: string | null;
  toolOutputJson: string | null;
  observationSummary: string | null;
  memoryBefore: AgentMemorySnapshot | null;
  memoryAfter: AgentMemorySnapshot | null;
  status: AgentExecutionState;
  errorMessage: string | null;
  createdAt: string;
}

export interface AgentSession {
  sessionId: string;
  title: string;
  goal: string;
  resumeId: number | null;
  knowledgeBaseIds: number[];
  status: AgentExecutionState;
  createdAt: string;
  updatedAt: string;
  messages: AgentMessage[];
}

export interface CreateAgentSessionRequest {
  title?: string;
  goal: string;
  resumeId?: number;
  knowledgeBaseIds?: number[];
}

export interface AgentChatRequest {
  message: string;
}

export interface AgentChatResponse {
  sessionId: string;
  turnId: string;
  turnStatus: AgentTurnStatus;
  completionMode: AgentCompletionMode;
  reply: string;
  memory: AgentMemorySnapshot;
  traceSteps: AgentTraceStep[];
  messagesDelta: AgentMessage[];
}
