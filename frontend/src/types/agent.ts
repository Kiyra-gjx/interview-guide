export type AgentExecutionState = 'CREATED' | 'RUNNING' | 'WAITING_APPROVAL' | 'COMPLETED' | 'FAILED';
export type AgentTurnStatus = 'CREATED' | 'RUNNING' | 'WAITING_APPROVAL' | 'COMPLETED' | 'FAILED' | 'ABORTED';
export type AgentCompletionMode = 'SUCCESS' | 'DEGRADED' | 'WAITING_APPROVAL';
export type AgentApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
export type AgentToolRiskLevel = 'READ_ONLY' | 'REQUIRES_APPROVAL';

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

export interface AgentToolOutputNormalization {
  summaryTruncated: boolean;
  answerTruncated: boolean;
  debugTruncated: boolean;
  factsTruncated: boolean;
}

export interface AgentToolOutput {
  kind: string;
  summary: string;
  reply: string;
  answer: Record<string, unknown>;
  debug: Record<string, unknown>;
  facts: string[];
  normalization: AgentToolOutputNormalization;
}

export interface AgentTraceStep {
  stepIndex: number;
  decisionSummary: string | null;
  selectedTool: string | null;
  toolInputJson: string | null;
  toolOutputJson: string | null;
  toolOutput: AgentToolOutput | null;
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

export interface AgentApproval {
  approvalId: string;
  sessionId: string;
  turnId: string;
  selectedTool: string;
  riskLevel: AgentToolRiskLevel;
  status: AgentApprovalStatus;
  reason: string | null;
  expiresAt: string | null;
  decidedAt: string | null;
  createdAt: string;
}

export interface AgentChatResponse {
  sessionId: string;
  turnId: string;
  turnStatus: AgentTurnStatus;
  completionMode: AgentCompletionMode;
  approval?: AgentApproval | null;
  reply: string;
  memory: AgentMemorySnapshot;
  traceSteps: AgentTraceStep[];
  messagesDelta: AgentMessage[];
}
