export type AgentExecutionState = 'CREATED' | 'RUNNING' | 'WAITING_APPROVAL' | 'TERMINATED' | 'COMPLETED' | 'FAILED';
export type AgentTurnStatus = 'CREATED' | 'RUNNING' | 'WAITING_APPROVAL' | 'COMPLETED' | 'FAILED' | 'ABORTED';
export type AgentCompletionMode = 'SUCCESS' | 'DEGRADED' | 'WAITING_APPROVAL';
export type AgentTerminalState = 'SUCCESS' | 'DEGRADED' | 'EXHAUSTED' | 'WAITING_APPROVAL' | 'FAILED';
export type AgentApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
export type AgentToolRiskLevel = 'READ_ONLY' | 'REQUIRES_APPROVAL';
export type AgentLoopStopReason =
  | 'INPUT_GUARDRAIL_BLOCKED'
  | 'DIRECT_REPLY'
  | 'DEGRADED_REPLY'
  | 'HANDOFF_NOT_ALLOWED'
  | 'HANDOFF_COMPLETED_SINGLE_STEP'
  | 'HANDOFF_EXECUTION_FAILED'
  | 'TOOL_COMPLETED_SINGLE_STEP'
  | 'PENDING_APPROVAL'
  | 'APPROVAL_REJECTED'
  | 'APPROVAL_EXPIRED'
  | 'APPROVAL_REPLAY_BLOCKED'
  | 'APPROVAL_RESUME_FAILED'
  | 'STEP_BUDGET_EXHAUSTED'
  | 'TIME_BUDGET_EXHAUSTED'
  | 'TOKEN_BUDGET_EXHAUSTED'
  | 'TOOL_EXECUTION_FAILED'
  | 'TOOL_POST_PROCESSING_FAILED'
  | 'UNHANDLED_ERROR';
export type AgentGuardrailStage = 'INPUT' | 'TOOL' | 'OUTPUT';
export type AgentGuardrailCode =
  | 'INPUT_INTERNAL_DATA_REQUEST'
  | 'INPUT_MESSAGE_TOO_LONG'
  | 'INPUT_CONTROL_CHARACTERS'
  | 'TOOL_REQUIRES_APPROVAL'
  | 'TOOL_UNEXPECTED_INPUT'
  | 'TOOL_MISSING_REQUIRED_INPUT'
  | 'OUTPUT_EMPTY_REPLY'
  | 'OUTPUT_RAW_JSON_REPLY'
  | 'OUTPUT_SENSITIVE_FIELD_LEAK';
export type AgentGuardrailAction = 'REJECT' | 'DEGRADE' | 'REQUIRE_APPROVAL';
export type AgentGuardrailResolution =
  | 'RETURN_SAFE_REPLY'
  | 'BLOCK_TOOL_CALL'
  | 'REPLACE_WITH_FALLBACK_REPLY'
  | 'WAIT_FOR_APPROVAL';

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

export interface AgentGuardrailResult {
  stage: AgentGuardrailStage | null;
  code: AgentGuardrailCode | null;
  action: AgentGuardrailAction | null;
  resolution: AgentGuardrailResolution | null;
  reason: string | null;
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
  guardrailResults: AgentGuardrailResult[];
  status: AgentExecutionState;
  errorMessage: string | null;
  terminalState: AgentTerminalState | null;
  stopReason: AgentLoopStopReason | null;
  recoverable: boolean;
  recoveryHint: string | null;
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
}

export interface AgentRuntimeConfig {
  multiStepEnabled?: boolean;
  maxSteps?: number;
  maxDurationMillis?: number;
  maxEstimatedModelTokens?: number;
}

export interface AgentExecutionSummary {
  multiStepEnabled: boolean;
  maxSteps: number;
  executedSteps: number;
  remainingSteps: number;
  maxDurationMillis: number;
  elapsedMillis: number;
  remainingDurationMillis: number;
  maxEstimatedModelTokens: number;
  estimatedModelTokensUsed: number;
  remainingEstimatedModelTokens: number;
  stopReason: AgentLoopStopReason | null;
  budgetStopReason: AgentLoopStopReason | null;
  terminalState: AgentTerminalState | null;
  recoverable: boolean;
  recoveryHint: string | null;
}

export interface CreateAgentSessionRequest {
  title?: string;
  goal: string;
  resumeId?: number;
  knowledgeBaseIds?: number[];
}

export interface AgentChatRequest {
  message: string;
  runtimeConfig?: AgentRuntimeConfig;
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

export interface AgentTurnSummary {
  turnId: string;
  status: AgentTurnStatus;
  completionMode: AgentCompletionMode | null;
  userMessagePreview: string | null;
  assistantReplyPreview: string | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface AgentTurnDetail {
  turn: AgentTurnSummary;
  messages: AgentMessage[];
  traceSteps: AgentTraceStep[];
  approvals: AgentApproval[];
  guardrailResults: AgentGuardrailResult[];
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
  guardrailResults: AgentGuardrailResult[];
  messagesDelta: AgentMessage[];
  execution?: AgentExecutionSummary | null;
}
