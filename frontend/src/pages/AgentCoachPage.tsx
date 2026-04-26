import { startTransition, useEffect, useRef, useState } from 'react';
import { motion } from 'framer-motion';
import {
  Bot,
  BrainCircuit,
  Database,
  FileText,
  RefreshCcw,
  ShieldCheck,
  Sparkles,
  Waypoints,
} from 'lucide-react';
import { agentApi } from '../api/agent';
import { getErrorMessage } from '../api/request';
import { historyApi, type ResumeListItem } from '../api/history';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import type {
  AgentApproval,
  AgentMemorySnapshot,
  AgentSession,
  AgentTurnDetail,
  AgentTurnSummary,
} from '../types/agent';
import AgentApprovalQueue from '../components/agent/AgentApprovalQueue';
import AgentDemoScenarioPanel from '../components/agent/AgentDemoScenarioPanel';
import AgentExecutionNarrativePanel from '../components/agent/AgentExecutionNarrativePanel';
import AgentStatusBadge from '../components/agent/AgentStatusBadge';
import AgentTraceExplorer from '../components/agent/AgentTraceExplorer';
import AgentTurnConversation from '../components/agent/AgentTurnConversation';
import AgentTurnList from '../components/agent/AgentTurnList';
import type { AgentDemoScenario } from '../components/agent/agentDemoFlow';

/**
 * Stage 4 的 Agent Workbench 页面。
 * 该页面消费既有 turn、trace、memory、approval 数据面，把 Agent 从聊天页收口为可观察的工作台。
 */
export default function AgentCoachPage() {
  const [goal, setGoal] = useState('根据我的简历，帮我准备一轮 Java 后端面试，优先关注 Redis 和并发。');
  const [message, setMessage] = useState('先结合我的背景，给我一个 3 步训练建议。');
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [selectedResumeId, setSelectedResumeId] = useState<number | undefined>(undefined);
  const [selectedKnowledgeBaseIds, setSelectedKnowledgeBaseIds] = useState<number[]>([]);
  const [session, setSession] = useState<AgentSession | null>(null);
  const [turns, setTurns] = useState<AgentTurnSummary[]>([]);
  const [selectedTurnId, setSelectedTurnId] = useState<string | null>(null);
  const [selectedTurnDetail, setSelectedTurnDetail] = useState<AgentTurnDetail | null>(null);
  const [memory, setMemory] = useState<AgentMemorySnapshot | null>(null);
  const [approvals, setApprovals] = useState<AgentApproval[]>([]);
  const [loadingOptions, setLoadingOptions] = useState(true);
  const [creating, setCreating] = useState(false);
  const [sending, setSending] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [actingApprovalId, setActingApprovalId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const sessionEpochRef = useRef(0);
  const selectedTurnIdRef = useRef<string | null>(null);
  const workbenchRequestSequenceRef = useRef(0);
  const turnSelectionSequenceRef = useRef(0);
  const detailRequestSequenceRef = useRef(0);

  useEffect(() => {
    void loadOptions();
  }, []);

  const pendingApprovals = approvals.filter((approval) => approval.status === 'PENDING').length;

  /**
   * 同步更新当前选中的 turnId 与 ref，避免异步请求读取到旧选中值。
   */
  function updateSelectedTurnId(nextTurnId: string | null) {
    selectedTurnIdRef.current = nextTurnId;
    setSelectedTurnId(nextTurnId);
  }

  /**
   * 让当前 workbench 相关异步请求全部失效。
   * 会话切换或显式重置时，需要阻止旧请求把前一个上下文写回页面。
   */
  function invalidateWorkbenchRequests() {
    // 1. 让旧的 workbench 刷新结果失效。
    workbenchRequestSequenceRef.current += 1;
    // 2. 让旧的 turn 选择与明细请求也一起失效。
    turnSelectionSequenceRef.current += 1;
    detailRequestSequenceRef.current += 1;
    // 3. 旧请求既然已经失效，对应的 loading 也应该同步结束。
    setRefreshing(false);
    setDetailLoading(false);
  }

  /**
   * 推进当前会话代际，并让旧会话上下文的异步结果全部失效。
   * reset、创建新会话和重建会话都属于“切换会话上下文”，旧 action 结果不允许再写回页面。
   */
  function advanceSessionEpoch(): number {
    sessionEpochRef.current += 1;
    invalidateWorkbenchRequests();
    // 1. 会话上下文已经切换，旧会话遗留的 create/send pending 不应继续锁住新会话。
    setCreating(false);
    setSending(false);
    // 2. 审批中的状态也必须跟随会话代际切换，避免旧会话的审批锁残留到新会话。
    setActingApprovalId(null);
    return sessionEpochRef.current;
  }

  /**
   * 判断某个会话级 action 是否仍然属于当前会话代际。
   */
  function isCurrentSessionEpoch(epoch: number) {
    return sessionEpochRef.current === epoch;
  }

  /**
   * 开始一次新的 turn 明细读取，并把 loading 控制权交给最新请求。
   */
  function beginDetailRequest(): number {
    detailRequestSequenceRef.current += 1;
    setDetailLoading(true);
    return detailRequestSequenceRef.current;
  }

  /**
   * 只有最新一次明细请求才允许结束 loading，避免旧请求提前关掉加载态。
   */
  function finishDetailRequest(requestSequence: number) {
    if (detailRequestSequenceRef.current === requestSequence) {
      setDetailLoading(false);
    }
  }

  /**
   * 判断某次 workbench 刷新是否仍然是当前最新请求。
   */
  function isLatestWorkbenchRequest(requestSequence: number) {
    return workbenchRequestSequenceRef.current === requestSequence;
  }

  /**
   * 判断某次 turn 选择结果是否仍然允许写回页面。
   */
  function shouldApplyTurnSelection(selectionSequence: number, detailRequestSequence: number) {
    return (
      turnSelectionSequenceRef.current === selectionSequence
      && detailRequestSequenceRef.current === detailRequestSequence
    );
  }

  /**
   * 加载会话创建所需的候选资源。
   */
  async function loadOptions() {
    setLoadingOptions(true);
    try {
      // 1. 并行读取简历和知识库，减少工作台冷启动时间。
      const [resumeList, knowledgeBaseList] = await Promise.all([
        historyApi.getResumes(),
        knowledgeBaseApi.getAllKnowledgeBases(undefined, 'COMPLETED'),
      ]);

      // 2. 一次性写入候选资源，避免页面出现局部抖动。
      startTransition(() => {
        setResumes(resumeList);
        setKnowledgeBases(knowledgeBaseList);
      });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoadingOptions(false);
    }
  }

  /**
   * 刷新整个 workbench 的会话级快照，并自动对齐当前选中的 turn。
   */
  async function refreshWorkbench(sessionId: string, preferredTurnId?: string | null) {
    const requestSequence = workbenchRequestSequenceRef.current + 1;
    workbenchRequestSequenceRef.current = requestSequence;
    const selectionSequence = turnSelectionSequenceRef.current;
    let detailRequestSequence: number | null = null;
    setRefreshing(true);
    try {
      // 1. 先并行读取 session、memory、approval 和 turn 摘要。
      const [sessionData, memoryData, approvalsData, turnsData] = await Promise.all([
        agentApi.getSession(sessionId),
        agentApi.getMemory(sessionId),
        agentApi.getApprovals(sessionId),
        agentApi.getTurns(sessionId),
      ]);

      if (!isLatestWorkbenchRequest(requestSequence)) {
        return;
      }

      // 2. 再根据最新 turn 列表决定当前应该落在哪一轮明细上。
      const shouldSyncTurnSelection = selectionSequence === turnSelectionSequenceRef.current;
      const nextSelectedTurnId = resolveNextSelectedTurnId(turnsData, preferredTurnId, selectedTurnIdRef.current);
      let detailData: AgentTurnDetail | null = null;
      if (nextSelectedTurnId && shouldSyncTurnSelection) {
        detailRequestSequence = beginDetailRequest();
        detailData = await agentApi.getTurnDetail(nextSelectedTurnId);
        if (
          !isLatestWorkbenchRequest(requestSequence)
          || !shouldApplyTurnSelection(selectionSequence, detailRequestSequence)
        ) {
          return;
        }
      }

      // 3. 最后批量提交状态，保证工作台各面板看到的是同一份快照。
      startTransition(() => {
        setSession(sessionData);
        setMemory(memoryData);
        setApprovals(approvalsData);
        setTurns(turnsData);
        if (shouldSyncTurnSelection) {
          updateSelectedTurnId(nextSelectedTurnId);
          setSelectedTurnDetail(detailData);
        }
      });
    } catch (err) {
      const canApplyDetailError = detailRequestSequence === null
        || shouldApplyTurnSelection(selectionSequence, detailRequestSequence);
      if (isLatestWorkbenchRequest(requestSequence) && canApplyDetailError) {
        setError(getErrorMessage(err));
      }
    } finally {
      if (isLatestWorkbenchRequest(requestSequence)) {
        setRefreshing(false);
      }
      if (detailRequestSequence !== null) {
        finishDetailRequest(detailRequestSequence);
      }
    }
  }

  /**
   * 创建新的 Agent 会话，并切换工作台到该会话。
   */
  async function handleCreateSession() {
    if (!goal.trim()) {
      setError('请先输入本轮训练目标。');
      return;
    }

    const sessionEpoch = advanceSessionEpoch();
    setCreating(true);
    setError('');
    try {
      // 1. 先创建会话实体，确保目标和绑定资源落库。
      const created = await agentApi.createSession({
        goal: goal.trim(),
        resumeId: selectedResumeId,
        knowledgeBaseIds: selectedKnowledgeBaseIds,
      });

      // 2. 只有当前代际仍然有效时，才允许把工作台切到新会话。
      if (!isCurrentSessionEpoch(sessionEpoch)) {
        return;
      }
      await refreshWorkbench(created.sessionId, null);
    } catch (err) {
      if (isCurrentSessionEpoch(sessionEpoch)) {
        setError(getErrorMessage(err));
      }
    } finally {
      // 只有当前代际仍然有效时，才能结束本次 create 的 pending，避免旧 finally 提前清掉新请求状态。
      if (isCurrentSessionEpoch(sessionEpoch)) {
        setCreating(false);
      }
    }
  }

  /**
   * 发送一条新消息，驱动下一轮 turn 执行。
   */
  async function handleSendMessage() {
    if (!session) {
      setError('请先创建一个 Agent 会话。');
      return;
    }
    if (!message.trim()) {
      setError('请输入本轮问题。');
      return;
    }

    const actionSessionId = session.sessionId;
    const sessionEpoch = sessionEpochRef.current;
    setSending(true);
    setError('');
    try {
      // 1. 先发起本轮 turn，让后端完成决策、工具执行和持久化。
      const response = await agentApi.sendMessage(actionSessionId, {
        message: message.trim(),
      });

      // 2. 只有当发送时绑定的会话代际仍有效，才允许写回输入框和工作台。
      if (!isCurrentSessionEpoch(sessionEpoch)) {
        return;
      }
      setMessage('');
      await refreshWorkbench(actionSessionId, response.turnId);
    } catch (err) {
      if (isCurrentSessionEpoch(sessionEpoch)) {
        setError(getErrorMessage(err));
      }
    } finally {
      // 只有当前会话代际的 send 才能回收 pending，避免旧请求在 finally 中干扰新会话交互状态。
      if (isCurrentSessionEpoch(sessionEpoch)) {
        setSending(false);
      }
    }
  }

  /**
   * 切换当前查看的 turn，并单独刷新该 turn 明细。
   */
  async function handleSelectTurn(turnId: string) {
    if (!session || turnId === selectedTurnIdRef.current) {
      return;
    }

    const previousTurnId = selectedTurnIdRef.current;
    const previousDetail = selectedTurnDetail;
    const selectionSequence = turnSelectionSequenceRef.current + 1;
    turnSelectionSequenceRef.current = selectionSequence;
    const detailRequestSequence = beginDetailRequest();
    setError('');
    try {
      // 先切换选中态并清空旧明细，避免“新 turn 标题 + 旧 turn 内容”同时出现。
      updateSelectedTurnId(turnId);
      setSelectedTurnDetail(null);
      const detail = await agentApi.getTurnDetail(turnId);
      if (!shouldApplyTurnSelection(selectionSequence, detailRequestSequence)) {
        return;
      }
      startTransition(() => {
        setSelectedTurnDetail(detail);
      });
    } catch (err) {
      if (shouldApplyTurnSelection(selectionSequence, detailRequestSequence)) {
        updateSelectedTurnId(previousTurnId);
        setSelectedTurnDetail(previousDetail);
        setError(getErrorMessage(err));
      }
    } finally {
      finishDetailRequest(detailRequestSequence);
    }
  }

  /**
   * 执行审批动作，并把工作台重新对齐到审批对应的 turn。
   */
  async function handleApprovalAction(approvalId: string, action: 'approve' | 'reject') {
    if (!session) {
      return;
    }

    const actionSessionId = session.sessionId;
    const sessionEpoch = sessionEpochRef.current;
    setActingApprovalId(approvalId);
    setError('');
    try {
      // 1. 先调用审批接口，让后端推进 approval/trace/turn 状态。
      const response = action === 'approve'
        ? await agentApi.approveApproval(approvalId)
        : await agentApi.rejectApproval(approvalId);

      // 2. 只有旧审批仍然属于当前会话代际时，才允许刷新工作台。
      if (!isCurrentSessionEpoch(sessionEpoch)) {
        return;
      }
      await refreshWorkbench(actionSessionId, response.turnId);
    } catch (err) {
      if (isCurrentSessionEpoch(sessionEpoch)) {
        setError(getErrorMessage(err));
      }
    } finally {
      // 只有当前会话代际的审批动作才能回收 pending，避免旧 finally 干扰新会话的审批交互状态。
      if (isCurrentSessionEpoch(sessionEpoch)) {
        setActingApprovalId(null);
      }
    }
  }

  /**
   * 切换知识库勾选状态。
   */
  function toggleKnowledgeBase(id: number) {
    // 用集合式写法保证勾选和取消勾选都只影响目标项。
    setSelectedKnowledgeBaseIds((current) =>
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id],
    );
  }

  /**
   * 重置当前工作台到未选会话状态。
   */
  function resetSession() {
    advanceSessionEpoch();
    // 会话切空时同时清掉 turn、审批和 memory，避免旧数据残留在工作台面板中。
    setSession(null);
    setTurns([]);
    updateSelectedTurnId(null);
    setSelectedTurnDetail(null);
    setMemory(null);
    setApprovals([]);
    setRefreshing(false);
    setDetailLoading(false);
    setError('');
  }

  /**
   * 把推荐 demo 场景写入当前输入区。
   * 这里只改本地草稿，不隐式创建或重建会话，避免用户还没确认资源绑定就被自动提交。
   */
  function handleApplyDemoScenario(scenario: AgentDemoScenario) {
    // 1. 同步替换训练目标，让用户明确知道这次演示想验证什么。
    setGoal(scenario.goal);
    // 2. 同步替换待发送问题，保证点击后即可直接开始主路径演示。
    setMessage(scenario.message);
    // 3. 应用场景本身不算错误，顺手清掉旧错误提示，避免把 demo 引导和旧异常混在一起。
    setError('');
  }

  return (
    <div className="space-y-6">
      {/* 顶部标题负责明确 Stage 4 workbench 的定位，而不是泛化成普通 Agent 聊天页。 */}
      <motion.div
        className="flex flex-col gap-4 2xl:flex-row 2xl:items-end 2xl:justify-between"
        initial={{ opacity: 0, y: -16 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <div>
          <div className="inline-flex items-center gap-2 rounded-full bg-primary-100 px-3 py-1 text-xs font-semibold text-primary-700 dark:bg-primary-500/15 dark:text-primary-300">
            <Sparkles className="h-3.5 w-3.5" />
            Stage 4 · Agent Workbench
          </div>
          <h1 className="mt-3 text-3xl font-bold text-slate-900 dark:text-white">Interview Guide Agent 工作台</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-500 dark:text-slate-400">
            这里同时展示 turn 时间线、当前 turn 的用户视角、会话级 memory、审批队列和 trace 调试面，用来验证单 Agent 的执行闭环。
          </p>
        </div>

        {/* 顶部操作区只保留工作台级动作：刷新和切换到新会话。 */}
        <div className="flex flex-wrap items-center gap-2">
          {session && (
            <>
              <AgentStatusBadge kind="execution" value={session.status} />
              <span className="inline-flex items-center rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                {turns.length} 个 turn
              </span>
              <span className="inline-flex items-center rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                {pendingApprovals} 条待审批
              </span>
            </>
          )}
          <button
            type="button"
            onClick={() => session && void refreshWorkbench(session.sessionId, selectedTurnId)}
            disabled={!session || refreshing}
            className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-600 transition hover:border-slate-300 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700 dark:text-slate-300 dark:hover:border-slate-500 dark:hover:bg-slate-800"
          >
            <RefreshCcw className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} />
            刷新工作台
          </button>
          <button
            type="button"
            onClick={resetSession}
            disabled={!session}
            className="inline-flex items-center rounded-2xl bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-60 dark:bg-white dark:text-slate-900 dark:hover:bg-slate-200"
          >
            新建会话
          </button>
        </div>
      </motion.div>

      {error && (
        <div className="rounded-3xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200">
          {error}
        </div>
      )}

      {/* 先用场景入口和执行解释把 demo flow 讲清楚，再进入工作台的细粒度观察面。 */}
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)]">
        <AgentDemoScenarioPanel
          selectedResumeId={selectedResumeId}
          selectedKnowledgeBaseIds={selectedKnowledgeBaseIds}
          hasSession={!!session}
          onApplyScenario={handleApplyDemoScenario}
        />
        <AgentExecutionNarrativePanel
          session={session}
          detail={selectedTurnDetail}
          approvals={approvals}
          loading={detailLoading}
          turnCount={turns.length}
        />
      </div>

      {/* 顶部主工作区拆成配置/turn 列表、当前 turn 对话、会话级观测三列。 */}
      <div className="grid gap-6 2xl:grid-cols-[340px_minmax(0,1fr)_360px]">
        <div className="space-y-6">
          <section className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="flex items-center gap-2 text-lg font-semibold text-slate-900 dark:text-white">
                  <Bot className="h-5 w-5 text-primary-500" />
                  会话配置
                </h2>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                  绑定简历和知识库后，创建一个可追踪的单 Agent 会话。
                </p>
              </div>
              {loadingOptions && <span className="text-xs text-slate-400">加载资源中...</span>}
            </div>

            {/* 配置区保留 goal、resume 和知识库三类上下文来源。 */}
            <div className="mt-5 space-y-5">
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">训练目标</span>
                <textarea
                  value={goal}
                  onChange={(event) => setGoal(event.target.value)}
                  rows={4}
                  className="w-full rounded-3xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-primary-400 focus:bg-white dark:border-slate-700 dark:bg-slate-950 dark:text-white"
                  placeholder="例如：根据我的简历，帮我准备一轮 Java 后端一面模拟。"
                />
              </label>

              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">关联简历</span>
                <select
                  value={selectedResumeId ?? ''}
                  onChange={(event) => setSelectedResumeId(event.target.value ? Number(event.target.value) : undefined)}
                  className="w-full rounded-3xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-primary-400 focus:bg-white dark:border-slate-700 dark:bg-slate-950 dark:text-white"
                >
                  <option value="">不绑定</option>
                  {resumes.map((resume) => (
                    <option key={resume.id} value={resume.id}>
                      #{resume.id} {resume.filename}
                    </option>
                  ))}
                </select>
              </label>

              <div>
                <span className="mb-2 flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-300">
                  <Database className="h-4 w-4 text-primary-500" />
                  知识库
                </span>
                <div className="max-h-44 space-y-2 overflow-y-auto rounded-3xl border border-slate-200 bg-slate-50 p-3 dark:border-slate-700 dark:bg-slate-950">
                  {knowledgeBases.length === 0 ? (
                    <p className="text-sm text-slate-400">暂无可用知识库</p>
                  ) : (
                    knowledgeBases.map((knowledgeBase) => (
                      <label
                        key={knowledgeBase.id}
                        className="flex cursor-pointer items-start gap-3 rounded-2xl px-3 py-2 transition hover:bg-white dark:hover:bg-slate-900"
                      >
                        <input
                          type="checkbox"
                          checked={selectedKnowledgeBaseIds.includes(knowledgeBase.id)}
                          onChange={() => toggleKnowledgeBase(knowledgeBase.id)}
                          className="mt-1 h-4 w-4 rounded border-slate-300 text-primary-500 focus:ring-primary-400"
                        />
                        <div className="min-w-0">
                          <p className="truncate text-sm font-medium text-slate-700 dark:text-slate-200">
                            {knowledgeBase.name}
                          </p>
                          <p className="text-xs text-slate-400">{knowledgeBase.category || '未分类'}</p>
                        </div>
                      </label>
                    ))
                  )}
                </div>
              </div>

              <button
                type="button"
                onClick={handleCreateSession}
                disabled={creating}
                className="inline-flex items-center gap-2 rounded-2xl bg-primary-500 px-5 py-3 text-sm font-semibold text-white shadow-lg shadow-primary-500/20 transition hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {creating ? '创建中...' : session ? '重建会话' : '创建 Agent 会话'}
              </button>
            </div>

            {session && (
              <div className="mt-5 rounded-3xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-700 dark:bg-slate-950/60">
                <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">当前会话</p>
                <p className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">{session.title}</p>
                <div className="mt-3 space-y-1 text-xs text-slate-500 dark:text-slate-400">
                  <p>Session ID: {session.sessionId}</p>
                  <p>Resume: {session.resumeId ?? '未绑定'}</p>
                  <p>Knowledge Bases: {session.knowledgeBaseIds.length}</p>
                </div>
              </div>
            )}
          </section>

          <section className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="flex items-center gap-2 text-lg font-semibold text-slate-900 dark:text-white">
                  <Waypoints className="h-5 w-5 text-primary-500" />
                  Turn 时间线
                </h2>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                  按 turn 查看执行结果、状态和关键预览。
                </p>
              </div>
              {session && (
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                  {turns.length} 条
                </span>
              )}
            </div>
            <div className="mt-5">
              {session ? (
                <AgentTurnList
                  turns={turns}
                  selectedTurnId={selectedTurnId}
                  onSelectTurn={handleSelectTurn}
                  loading={refreshing}
                />
              ) : (
                <div className="rounded-2xl border border-dashed border-slate-300/80 px-4 py-8 text-center text-sm text-slate-400 dark:border-slate-700 dark:text-slate-500">
                  创建会话后，时间线会显示每一轮 turn 的状态与摘要。
                </div>
              )}
            </div>
          </section>
        </div>

        <AgentTurnConversation
          hasSession={!!session}
          detail={selectedTurnDetail}
          draft={message}
          sending={sending}
          onDraftChange={setMessage}
          onSend={handleSendMessage}
        />

        <div className="space-y-6">
          <section className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="flex items-center gap-2 text-lg font-semibold text-slate-900 dark:text-white">
                  <BrainCircuit className="h-5 w-5 text-primary-500" />
                  Session Memory
                </h2>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                  会话级记忆展示当前阶段、确认事实和下一步焦点。
                </p>
              </div>
              {memory?.currentPhase && (
                <span className="rounded-full bg-primary-100 px-3 py-1 text-xs font-semibold text-primary-700 dark:bg-primary-500/15 dark:text-primary-300">
                  {memory.currentPhase}
                </span>
              )}
            </div>

            {memory ? (
              <div className="mt-5 space-y-4 text-sm text-slate-600 dark:text-slate-300">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">下一步焦点</p>
                  <p className="mt-2 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 leading-6 dark:border-slate-700 dark:bg-slate-950/60">
                    {memory.nextFocus || '暂无'}
                  </p>
                </div>
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">确认事实</p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {memory.confirmedFacts.length > 0 ? (
                      memory.confirmedFacts.map((fact) => (
                        <span
                          key={fact}
                          className="inline-flex items-center rounded-full bg-slate-200 px-3 py-1 text-xs text-slate-700 dark:bg-slate-700 dark:text-slate-200"
                        >
                          {fact}
                        </span>
                      ))
                    ) : (
                      <span className="text-sm text-slate-400">暂无确认事实</span>
                    )}
                  </div>
                </div>
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">已使用工具</p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {memory.usedTools.length > 0 ? (
                      memory.usedTools.map((tool) => (
                        <span
                          key={tool}
                          className="inline-flex items-center rounded-full bg-primary-100 px-3 py-1 text-xs font-semibold text-primary-700 dark:bg-primary-500/15 dark:text-primary-300"
                        >
                          {tool}
                        </span>
                      ))
                    ) : (
                      <span className="text-sm text-slate-400">当前还没有工具写回 memory</span>
                    )}
                  </div>
                </div>
              </div>
            ) : (
              <p className="mt-4 text-sm text-slate-400 dark:text-slate-500">创建会话后展示。</p>
            )}
          </section>

          <section className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="flex items-center gap-2 text-lg font-semibold text-slate-900 dark:text-white">
                  <ShieldCheck className="h-5 w-5 text-primary-500" />
                  审批队列
                </h2>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                  这里展示当前会话下所有审批记录，并允许对待决项直接处理。
                </p>
              </div>
              <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                {approvals.length} 条
              </span>
            </div>
            <div className="mt-5">
              <AgentApprovalQueue
                approvals={approvals}
                activeTurnId={selectedTurnId}
                actingApprovalId={actingApprovalId}
                onApprove={(approvalId) => void handleApprovalAction(approvalId, 'approve')}
                onReject={(approvalId) => void handleApprovalAction(approvalId, 'reject')}
              />
            </div>
          </section>
        </div>
      </div>

      {/* 底部大面板专门留给 trace/debug 数据，避免和用户视角挤在一起。 */}
      <section className="space-y-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="flex items-center gap-2 text-xl font-semibold text-slate-900 dark:text-white">
              <FileText className="h-5 w-5 text-primary-500" />
              Trace Browser
            </h2>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              调试区只消费 turn 明细，不引入任何新的多步执行逻辑。
            </p>
          </div>
          {selectedTurnDetail?.turn && (
            <div className="flex flex-wrap gap-2">
              <AgentStatusBadge kind="turn" value={selectedTurnDetail.turn.status} />
              <AgentStatusBadge kind="completion" value={selectedTurnDetail.turn.completionMode} />
            </div>
          )}
        </div>
        <AgentTraceExplorer detail={selectedTurnDetail} loading={detailLoading} />
      </section>
    </div>
  );
}

/**
 * 在最新 turn 列表里决定工作台应该聚焦哪一轮。
 */
function resolveNextSelectedTurnId(
  turns: AgentTurnSummary[],
  preferredTurnId?: string | null,
  currentTurnId?: string | null,
): string | null {
  // 1. 优先尊重新产生的 turn 或审批恢复对应的 turn。
  if (preferredTurnId && turns.some((turn) => turn.turnId === preferredTurnId)) {
    return preferredTurnId;
  }

  // 2. 如果当前选中的 turn 仍然存在，就保持焦点不变。
  if (currentTurnId && turns.some((turn) => turn.turnId === currentTurnId)) {
    return currentTurnId;
  }

  // 3. 否则退回到最新的一轮 turn。
  return turns[0]?.turnId ?? null;
}
