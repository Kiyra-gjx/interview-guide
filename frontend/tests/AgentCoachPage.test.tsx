import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AgentCoachPage from '../src/pages/AgentCoachPage';
import { agentApi } from '../src/api/agent';
import { historyApi } from '../src/api/history';
import { knowledgeBaseApi } from '../src/api/knowledgebase';
import type {
  AgentApproval,
  AgentChatResponse,
  AgentMemorySnapshot,
  AgentSession,
  AgentTurnDetail,
  AgentTurnSummary,
} from '../src/types/agent';

vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }: React.ComponentProps<'div'>) => <div {...props}>{children}</div>,
  },
}));

vi.mock('../src/api/agent', () => ({
  agentApi: {
    createSession: vi.fn(),
    getSession: vi.fn(),
    sendMessage: vi.fn(),
    getTrace: vi.fn(),
    getTurns: vi.fn(),
    getTurnDetail: vi.fn(),
    getMemory: vi.fn(),
    getApprovals: vi.fn(),
    approveApproval: vi.fn(),
    rejectApproval: vi.fn(),
  },
}));

vi.mock('../src/api/history', () => ({
  historyApi: {
    getResumes: vi.fn(),
  },
}));

vi.mock('../src/api/knowledgebase', () => ({
  knowledgeBaseApi: {
    getAllKnowledgeBases: vi.fn(),
  },
}));

const mockedAgentApi = vi.mocked(agentApi);
const mockedHistoryApi = vi.mocked(historyApi);
const mockedKnowledgeBaseApi = vi.mocked(knowledgeBaseApi);

/**
 * 构造一个最小会话快照，供工作台刷新路径复用。
 */
function createSession(sessionId: string, title: string): AgentSession {
  return {
    sessionId,
    title,
    goal: `${title} goal`,
    resumeId: null,
    knowledgeBaseIds: [],
    status: 'CREATED',
    createdAt: '2026-04-25T10:00:00',
    updatedAt: '2026-04-25T10:00:00',
  };
}

/**
 * 构造固定 memory，避免每个测试都重复铺设无关数据。
 */
function createMemorySnapshot(label: string): AgentMemorySnapshot {
  return {
    userGoal: `${label} goal`,
    currentPhase: `${label} phase`,
    confirmedFacts: [],
    usedTools: [],
    nextFocus: `${label} focus`,
  };
}

/**
 * 构造一个最小 turn 摘要，供审批队列场景触发默认 turn 明细加载。
 */
function createTurnSummary(turnId: string): AgentTurnSummary {
  return {
    turnId,
    status: 'WAITING_APPROVAL',
    completionMode: 'WAITING_APPROVAL',
    userMessagePreview: '请执行高风险操作',
    assistantReplyPreview: '该操作需要审批。',
    errorMessage: null,
    createdAt: '2026-04-25T10:10:00',
    startedAt: '2026-04-25T10:10:01',
    finishedAt: null,
  };
}

/**
 * 构造带审批信息的 turn 明细。
 */
function createTurnDetail(turnId: string, approvals: AgentApproval[]): AgentTurnDetail {
  return {
    turn: createTurnSummary(turnId),
    messages: [
      {
        role: 'user',
        content: '请执行高风险操作',
        messageOrder: 1,
        createdAt: '2026-04-25T10:10:00',
      },
      {
        role: 'assistant',
        content: '该操作需要审批。',
        messageOrder: 2,
        createdAt: '2026-04-25T10:10:02',
      },
    ],
    traceSteps: [],
    approvals,
    guardrailResults: [],
  };
}

/**
 * 构造 send/approve 共用的最小响应。
 */
function createChatResponse(turnId: string): AgentChatResponse {
  return {
    sessionId: 'unused-in-ui',
    turnId,
    turnStatus: 'COMPLETED',
    completionMode: 'SUCCESS',
    approval: null,
    reply: 'done',
    memory: createMemorySnapshot('response'),
    traceSteps: [],
    guardrailResults: [],
    messagesDelta: [],
  };
}

/**
 * 创建一个可控的 deferred promise，便于精确驱动竞态时序。
 */
function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((innerResolve, innerReject) => {
    resolve = innerResolve;
    reject = innerReject;
  });
  return { promise, resolve, reject };
}

/**
 * 渲染工作台并等待初始资源加载完成。
 */
async function renderPage() {
  render(<AgentCoachPage />);
  await waitFor(() => {
    expect(mockedHistoryApi.getResumes).toHaveBeenCalledTimes(1);
    expect(mockedKnowledgeBaseApi.getAllKnowledgeBases).toHaveBeenCalledTimes(1);
  });
}

/**
 * 通过 UI 创建一个新会话，并等待工作台切到该会话。
 */
async function createSessionThroughUi(expectedTitle: string) {
  await userEvent.click(screen.getByRole('button', { name: /创建 Agent 会话|重建会话/ }));
  await screen.findByText(expectedTitle);
}

describe('AgentCoachPage session-level stale response guards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedHistoryApi.getResumes.mockResolvedValue([]);
    mockedKnowledgeBaseApi.getAllKnowledgeBases.mockResolvedValue([]);
  });

  it('does not restore the old session when a stale send response returns after switching sessions', async () => {
    const sessionA = createSession('session-a', 'Session A');
    const sessionB = createSession('session-b', 'Session B');
    const sendDeferred = createDeferred<AgentChatResponse>();

    mockedAgentApi.createSession
      .mockResolvedValueOnce(sessionA)
      .mockResolvedValueOnce(sessionB);
    mockedAgentApi.getSession.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? sessionA : sessionB
    ));
    mockedAgentApi.getMemory.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? createMemorySnapshot('A') : createMemorySnapshot('B')
    ));
    mockedAgentApi.getApprovals.mockImplementation(async () => []);
    mockedAgentApi.getTurns.mockImplementation(async () => []);
    mockedAgentApi.sendMessage.mockReturnValue(sendDeferred.promise);

    await renderPage();

    // 1. 先创建会话 A，保证 send 请求有明确的归属会话。
    await createSessionThroughUi('Session A');
    await userEvent.click(screen.getByRole('button', { name: '发起新 Turn' }));

    // 2. 在 A 的 send 尚未完成时，切空工作台并创建会话 B。
    await userEvent.click(screen.getByRole('button', { name: '新建会话' }));
    await createSessionThroughUi('Session B');

    // 3. 最后让 A 的旧响应返回；正确行为是不允许它把页面拉回 A。
    await act(async () => {
      sendDeferred.resolve(createChatResponse('turn-a-late'));
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByText('Session B')).toBeInTheDocument();
    });
    expect(screen.queryByText('Session A')).not.toBeInTheDocument();
  });

  it('does not restore the old session when rebuild starts before the stale send response returns', async () => {
    const sessionA = createSession('session-a', 'Session A');
    const sessionB = createSession('session-b', 'Session B');
    const sendDeferred = createDeferred<AgentChatResponse>();

    mockedAgentApi.createSession
      .mockResolvedValueOnce(sessionA)
      .mockResolvedValueOnce(sessionB);
    mockedAgentApi.getSession.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? sessionA : sessionB
    ));
    mockedAgentApi.getMemory.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? createMemorySnapshot('A') : createMemorySnapshot('B')
    ));
    mockedAgentApi.getApprovals.mockResolvedValue([]);
    mockedAgentApi.getTurns.mockResolvedValue([]);
    mockedAgentApi.sendMessage.mockReturnValue(sendDeferred.promise);

    await renderPage();

    // 1. 先创建会话 A 并发起发送，让旧请求进入挂起态。
    await createSessionThroughUi('Session A');
    await userEvent.click(screen.getByRole('button', { name: '发起新 Turn' }));

    // 2. 不经过 reset，直接走“重建会话”路径创建 B。
    await userEvent.click(screen.getByRole('button', { name: '重建会话' }));
    await screen.findByText('Session B');

    // 3. A 的旧响应回来后，页面仍应保持在 B。
    await act(async () => {
      sendDeferred.resolve(createChatResponse('turn-a-late'));
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByText('Session B')).toBeInTheDocument();
    });
    expect(screen.queryByText('Session A')).not.toBeInTheDocument();
  });

  it('keeps the new session send entry available while a stale send is still pending', async () => {
    const sessionA = createSession('session-a', 'Session A');
    const sessionB = createSession('session-b', 'Session B');
    const sendDeferred = createDeferred<AgentChatResponse>();

    mockedAgentApi.createSession
      .mockResolvedValueOnce(sessionA)
      .mockResolvedValueOnce(sessionB);
    mockedAgentApi.getSession.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? sessionA : sessionB
    ));
    mockedAgentApi.getMemory.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? createMemorySnapshot('A') : createMemorySnapshot('B')
    ));
    mockedAgentApi.getApprovals.mockResolvedValue([]);
    mockedAgentApi.getTurns.mockResolvedValue([]);
    mockedAgentApi.sendMessage.mockReturnValue(sendDeferred.promise);

    await renderPage();

    // 1. 先在 A 发起一个会长时间挂起的 send。
    await createSessionThroughUi('Session A');
    await userEvent.click(screen.getByRole('button', { name: '发起新 Turn' }));

    // 2. 切到 B 后，新的发送入口应该立刻恢复可用。
    await userEvent.click(screen.getByRole('button', { name: '新建会话' }));
    await createSessionThroughUi('Session B');

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '发起新 Turn' })).toBeEnabled();
    });
  });

  it('does not keep the create button stuck after resetting away from a stale rebuild request', async () => {
    const sessionA = createSession('session-a', 'Session A');
    const rebuildDeferred = createDeferred<AgentSession>();

    mockedAgentApi.createSession
      .mockResolvedValueOnce(sessionA)
      .mockReturnValueOnce(rebuildDeferred.promise);
    mockedAgentApi.getSession.mockResolvedValue(sessionA);
    mockedAgentApi.getMemory.mockResolvedValue(createMemorySnapshot('A'));
    mockedAgentApi.getApprovals.mockResolvedValue([]);
    mockedAgentApi.getTurns.mockResolvedValue([]);

    await renderPage();

    // 1. 先进入已有会话状态，再触发一个挂起中的“重建会话”。
    await createSessionThroughUi('Session A');
    await userEvent.click(screen.getByRole('button', { name: '重建会话' }));

    // 2. reset 之后，创建入口不应该继续继承旧请求的 creating 锁。
    await userEvent.click(screen.getByRole('button', { name: '新建会话' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建 Agent 会话' })).toBeEnabled();
    });
  });

  it('keeps the new session approval action pending while a stale approval finally returns', async () => {
    const sessionA = createSession('session-a', 'Session A');
    const sessionB = createSession('session-b', 'Session B');
    const approvalA: AgentApproval = {
      approvalId: 'approval-a',
      sessionId: sessionA.sessionId,
      turnId: 'turn-a',
      selectedTool: 'delete_resume',
      riskLevel: 'REQUIRES_APPROVAL',
      status: 'PENDING',
      reason: '楂橀闄╂搷浣滈渶瑕佷汉宸ョ‘璁?',
      expiresAt: '2026-04-25T12:00:00',
      decidedAt: null,
      createdAt: '2026-04-25T10:10:00',
    };
    const approvalB: AgentApproval = {
      approvalId: 'approval-b',
      sessionId: sessionB.sessionId,
      turnId: 'turn-b',
      selectedTool: 'delete_knowledge_base',
      riskLevel: 'REQUIRES_APPROVAL',
      status: 'PENDING',
      reason: 'B 鐨勫鎵规搷浣滀篃闇€瑕佷汉宸ョ‘璁?',
      expiresAt: '2026-04-25T12:30:00',
      decidedAt: null,
      createdAt: '2026-04-25T10:20:00',
    };
    const approveDeferredA = createDeferred<AgentChatResponse>();
    const approveDeferredB = createDeferred<AgentChatResponse>();

    mockedAgentApi.createSession
      .mockResolvedValueOnce(sessionA)
      .mockResolvedValueOnce(sessionB);
    mockedAgentApi.getSession.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? sessionA : sessionB
    ));
    mockedAgentApi.getMemory.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? createMemorySnapshot('A') : createMemorySnapshot('B')
    ));
    mockedAgentApi.getApprovals.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? [approvalA] : [approvalB]
    ));
    mockedAgentApi.getTurns.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? [createTurnSummary('turn-a')] : [createTurnSummary('turn-b')]
    ));
    mockedAgentApi.getTurnDetail.mockImplementation(async (turnId) => (
      turnId === 'turn-a' ? createTurnDetail('turn-a', [approvalA]) : createTurnDetail('turn-b', [approvalB])
    ));
    mockedAgentApi.approveApproval.mockImplementation(async (approvalId) => (
      approvalId === 'approval-a' ? approveDeferredA.promise : approveDeferredB.promise
    ));

    await renderPage();

    // 1. 先在 A 会话发起审批，让旧请求进入挂起态。
    await createSessionThroughUi('Session A');
    const approvalCardA = (await screen.findByText('delete_resume')).closest('[class*="rounded-2xl"]');
    expect(approvalCardA).not.toBeNull();
    const [approveButtonA] = within(approvalCardA as HTMLElement).getAllByRole('button');
    await userEvent.click(approveButtonA);

    // 2. 切换到 B 后再发起新的审批，当前 pending 应该归属 B。
    await userEvent.click(screen.getByRole('button', { name: '新建会话' }));
    await createSessionThroughUi('Session B');
    const approvalCardB = (await screen.findByText('delete_knowledge_base')).closest('[class*="rounded-2xl"]');
    expect(approvalCardB).not.toBeNull();
    const [approveButtonB, rejectButtonB] = within(approvalCardB as HTMLElement).getAllByRole('button');
    await userEvent.click(approveButtonB);

    await waitFor(() => {
      expect(approveButtonB).toBeDisabled();
      expect(rejectButtonB).toBeDisabled();
    });

    // 3. 此时让 A 的旧 finally 返回，B 的当前审批状态不应被提前清掉。
    await act(async () => {
      approveDeferredA.resolve(createChatResponse('turn-a-late'));
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(approveButtonB).toBeDisabled();
      expect(rejectButtonB).toBeDisabled();
    });

    // 4. 收口 B 的挂起请求，避免测试间串扰。
    await act(async () => {
      approveDeferredB.resolve(createChatResponse('turn-b-late'));
      await Promise.resolve();
    });
  });

  it('does not surface a stale approval error after switching to a new session', async () => {
    const sessionA = createSession('session-a', 'Session A');
    const sessionB = createSession('session-b', 'Session B');
    const approvalA: AgentApproval = {
      approvalId: 'approval-a',
      sessionId: sessionA.sessionId,
      turnId: 'turn-a',
      selectedTool: 'delete_resume',
      riskLevel: 'REQUIRES_APPROVAL',
      status: 'PENDING',
      reason: '高风险操作需要人工确认',
      expiresAt: '2026-04-25T12:00:00',
      decidedAt: null,
      createdAt: '2026-04-25T10:10:00',
    };
    const approveDeferred = createDeferred<AgentChatResponse>();

    mockedAgentApi.createSession
      .mockResolvedValueOnce(sessionA)
      .mockResolvedValueOnce(sessionB);
    mockedAgentApi.getSession.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? sessionA : sessionB
    ));
    mockedAgentApi.getMemory.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? createMemorySnapshot('A') : createMemorySnapshot('B')
    ));
    mockedAgentApi.getApprovals.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? [approvalA] : []
    ));
    mockedAgentApi.getTurns.mockImplementation(async (sessionId) => (
      sessionId === 'session-a' ? [createTurnSummary('turn-a')] : []
    ));
    mockedAgentApi.getTurnDetail.mockImplementation(async () => createTurnDetail('turn-a', [approvalA]));
    mockedAgentApi.approveApproval.mockReturnValue(approveDeferred.promise);

    await renderPage();

    // 1. 先创建会话 A，并进入可以触发 approve 的审批场景。
    await createSessionThroughUi('Session A');
    await screen.findByRole('button', { name: '批准执行' });
    await userEvent.click(screen.getByRole('button', { name: '批准执行' }));

    // 2. 在旧审批尚未结束时切换到会话 B。
    await userEvent.click(screen.getByRole('button', { name: '新建会话' }));
    await createSessionThroughUi('Session B');

    // 3. 让 A 的审批失败返回；正确行为是不允许旧错误污染 B。
    await act(async () => {
      approveDeferred.reject(new Error('审批失败-A'));
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByText('Session B')).toBeInTheDocument();
    });
    expect(screen.queryByText('Session A')).not.toBeInTheDocument();
    expect(screen.queryByText('审批失败-A')).not.toBeInTheDocument();
  });
});
