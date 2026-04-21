import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { agentApi } from '../api/agent';
import { getErrorMessage } from '../api/request';
import { historyApi, type ResumeListItem } from '../api/history';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import type {
  AgentCompletionMode,
  AgentMemorySnapshot,
  AgentMessage,
  AgentSession,
  AgentTraceStep,
  AgentTurnStatus,
} from '../types/agent';

function prettyJson(jsonText: string | null): string {
  if (!jsonText) {
    return '暂无';
  }
  try {
    return JSON.stringify(JSON.parse(jsonText), null, 2);
  } catch {
    return jsonText;
  }
}

export default function AgentCoachPage() {
  const [goal, setGoal] = useState('根据我的简历，帮我准备一轮 Java 后端面试，优先关注 Redis 和并发。');
  const [message, setMessage] = useState('先结合我的背景，给我一个 3 步训练建议。');
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [selectedResumeId, setSelectedResumeId] = useState<number | undefined>(undefined);
  const [selectedKnowledgeBaseIds, setSelectedKnowledgeBaseIds] = useState<number[]>([]);
  const [session, setSession] = useState<AgentSession | null>(null);
  const [messages, setMessages] = useState<AgentMessage[]>([]);
  const [memory, setMemory] = useState<AgentMemorySnapshot | null>(null);
  const [traceSteps, setTraceSteps] = useState<AgentTraceStep[]>([]);
  const [lastTurnMeta, setLastTurnMeta] = useState<{
    turnId: string;
    turnStatus: AgentTurnStatus;
    completionMode: AgentCompletionMode;
  } | null>(null);
  const [loadingOptions, setLoadingOptions] = useState(true);
  const [creating, setCreating] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const loadOptions = async () => {
      setLoadingOptions(true);
      try {
        const [resumeList, knowledgeBaseList] = await Promise.all([
          historyApi.getResumes(),
          knowledgeBaseApi.getAllKnowledgeBases(undefined, 'COMPLETED'),
        ]);
        setResumes(resumeList);
        setKnowledgeBases(knowledgeBaseList);
      } catch (err) {
        setError(getErrorMessage(err));
      } finally {
        setLoadingOptions(false);
      }
    };

    void loadOptions();
  }, []);

  const refreshSessionMeta = async (sessionId: string) => {
    const [sessionData, memoryData, traceData] = await Promise.all([
      agentApi.getSession(sessionId),
      agentApi.getMemory(sessionId),
      agentApi.getTrace(sessionId),
    ]);
    setSession(sessionData);
    setMessages(sessionData.messages);
    setMemory(memoryData);
    setTraceSteps(traceData);
  };

  const handleCreateSession = async () => {
    if (!goal.trim()) {
      setError('请先输入本轮训练目标。');
      return;
    }
    setCreating(true);
    setError('');
    try {
      const created = await agentApi.createSession({
        goal: goal.trim(),
        resumeId: selectedResumeId,
        knowledgeBaseIds: selectedKnowledgeBaseIds,
      });
      setSession(created);
      setMessages(created.messages);
      setLastTurnMeta(null);
      await refreshSessionMeta(created.sessionId);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setCreating(false);
    }
  };

  const handleSendMessage = async () => {
    if (!session) {
      setError('请先创建一个 Agent 会话。');
      return;
    }
    if (!message.trim()) {
      setError('请输入本轮问题。');
      return;
    }
    setSending(true);
    setError('');
    try {
      const response = await agentApi.sendMessage(session.sessionId, {
        message: message.trim(),
      });
      setMessages((current) => [...current, ...response.messagesDelta]);
      setMemory(response.memory);
      setTraceSteps((current) => [...current, ...response.traceSteps]);
      setLastTurnMeta({
        turnId: response.turnId,
        turnStatus: response.turnStatus,
        completionMode: response.completionMode,
      });
      setMessage('');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSending(false);
    }
  };

  const toggleKnowledgeBase = (id: number) => {
    setSelectedKnowledgeBaseIds((current) =>
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id],
    );
  };

  const resetSession = () => {
    setSession(null);
    setMessages([]);
    setMemory(null);
    setTraceSteps([]);
    setLastTurnMeta(null);
    setError('');
  };

  return (
    <div className="space-y-8">
      <motion.div
        className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between"
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <div>
          <div className="inline-flex items-center gap-2 rounded-full bg-amber-100 px-3 py-1 text-xs font-semibold text-amber-700">
            Phase 1 MVP
          </div>
          <h1 className="mt-3 text-3xl font-bold text-slate-900 dark:text-white">Interview Coach Agent</h1>
          <p className="mt-2 max-w-2xl text-sm text-slate-500 dark:text-slate-400">
            先跑通单 Agent 闭环：记录目标，决定是否调用简历或知识库 Tool，并展示 Memory 与执行 Trace。
          </p>
        </div>
        {session && (
          <button
            className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 transition hover:border-slate-300 hover:text-slate-900 dark:border-slate-700 dark:text-slate-300 dark:hover:border-slate-500 dark:hover:text-white"
            onClick={resetSession}
          >
            新建会话
          </button>
        )}
      </motion.div>

      {error && (
        <div className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-600 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
          {error}
        </div>
      )}

      <div className="grid gap-6 xl:grid-cols-[1.35fr_0.65fr]">
        <div className="space-y-6">
          <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold text-slate-900 dark:text-white">会话配置</h2>
                <p className="text-sm text-slate-500 dark:text-slate-400">
                  选择可用上下文后，创建一个 Agent 会话。
                </p>
              </div>
              {loadingOptions && <span className="text-xs text-slate-400">加载资源中...</span>}
            </div>

            <div className="space-y-5">
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">训练目标</span>
                <textarea
                  value={goal}
                  onChange={(event) => setGoal(event.target.value)}
                  rows={4}
                  className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-amber-400 focus:bg-white dark:border-slate-700 dark:bg-slate-950 dark:text-white"
                  placeholder="例如：根据我的简历，帮我准备一轮 Java 后端一面模拟。"
                />
              </label>

              <div className="grid gap-4 md:grid-cols-2">
                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">关联简历</span>
                  <select
                    value={selectedResumeId ?? ''}
                    onChange={(event) =>
                      setSelectedResumeId(event.target.value ? Number(event.target.value) : undefined)
                    }
                    className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-amber-400 focus:bg-white dark:border-slate-700 dark:bg-slate-950 dark:text-white"
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
                  <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">知识库</span>
                  <div className="max-h-36 space-y-2 overflow-y-auto rounded-2xl border border-slate-200 bg-slate-50 p-3 dark:border-slate-700 dark:bg-slate-950">
                    {knowledgeBases.length === 0 ? (
                      <p className="text-sm text-slate-400">暂无可用知识库</p>
                    ) : (
                      knowledgeBases.map((knowledgeBase) => (
                        <label
                          key={knowledgeBase.id}
                          className="flex cursor-pointer items-start gap-3 rounded-xl px-2 py-2 transition hover:bg-white dark:hover:bg-slate-900"
                        >
                          <input
                            type="checkbox"
                            checked={selectedKnowledgeBaseIds.includes(knowledgeBase.id)}
                            onChange={() => toggleKnowledgeBase(knowledgeBase.id)}
                            className="mt-1 h-4 w-4 rounded border-slate-300 text-amber-500 focus:ring-amber-400"
                          />
                          <div>
                            <p className="text-sm font-medium text-slate-700 dark:text-slate-200">
                              {knowledgeBase.name}
                            </p>
                            <p className="text-xs text-slate-400">{knowledgeBase.category || '未分类'}</p>
                          </div>
                        </label>
                      ))
                    )}
                  </div>
                </div>
              </div>

              <button
                onClick={handleCreateSession}
                disabled={creating}
                className="inline-flex items-center rounded-2xl bg-amber-500 px-5 py-3 text-sm font-semibold text-white shadow-lg shadow-amber-500/20 transition hover:bg-amber-600 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {creating ? '创建中...' : session ? '重新创建会话' : '创建 Agent 会话'}
              </button>
            </div>
          </section>

          <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold text-slate-900 dark:text-white">对话区</h2>
                <p className="text-sm text-slate-500 dark:text-slate-400">
                  先创建会话，再发送本轮训练问题。
                </p>
              </div>
              {session && (
                <div className="flex flex-wrap items-center gap-2">
                  <div className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-500 dark:bg-slate-800 dark:text-slate-300">
                    {session.title}
                  </div>
                  {lastTurnMeta && (
                    <div className="rounded-full bg-amber-100 px-3 py-1 text-xs font-semibold text-amber-700 dark:bg-amber-500/10 dark:text-amber-300">
                      {lastTurnMeta.turnStatus} / {lastTurnMeta.completionMode} / {lastTurnMeta.turnId.slice(0, 8)}
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="space-y-4">
              <div className="max-h-[28rem] space-y-3 overflow-y-auto rounded-2xl bg-slate-50 p-4 dark:bg-slate-950">
                {messages.length === 0 ? (
                  <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-400 dark:border-slate-800">
                    创建会话后，消息会出现在这里。
                  </div>
                ) : (
                  messages.map((item) => (
                    <div
                      key={`${item.role}-${item.messageOrder}`}
                      className={`flex ${item.role === 'user' ? 'justify-end' : 'justify-start'}`}
                    >
                      <div
                        className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-6 ${
                          item.role === 'user'
                            ? 'bg-slate-900 text-white dark:bg-amber-500'
                            : 'bg-white text-slate-700 shadow-sm dark:bg-slate-900 dark:text-slate-200'
                        }`}
                      >
                        {item.content}
                      </div>
                    </div>
                  ))
                )}
              </div>

              <div className="space-y-3">
                <textarea
                  value={message}
                  onChange={(event) => setMessage(event.target.value)}
                  rows={3}
                  disabled={!session || sending}
                  className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-amber-400 focus:bg-white disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700 dark:bg-slate-950 dark:text-white"
                  placeholder="例如：结合我的简历，先给我一个 Redis 方向的主问题。"
                />
                <button
                  onClick={handleSendMessage}
                  disabled={!session || sending}
                  className="inline-flex items-center rounded-2xl bg-slate-900 px-5 py-3 text-sm font-semibold text-white transition hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-60 dark:bg-white dark:text-slate-900 dark:hover:bg-slate-200"
                >
                  {sending ? '分析中...' : '发送给 Agent'}
                </button>
              </div>
            </div>
          </section>
        </div>

        <div className="space-y-6">
          <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <h2 className="text-lg font-semibold text-slate-900 dark:text-white">Memory</h2>
            {memory ? (
              <div className="mt-4 space-y-4 text-sm text-slate-600 dark:text-slate-300">
                <div>
                  <p className="text-xs uppercase tracking-wide text-slate-400">Current Phase</p>
                  <p className="mt-1 font-medium">{memory.currentPhase || '暂无'}</p>
                </div>
                <div>
                  <p className="text-xs uppercase tracking-wide text-slate-400">Next Focus</p>
                  <p className="mt-1">{memory.nextFocus || '暂无'}</p>
                </div>
                <div>
                  <p className="text-xs uppercase tracking-wide text-slate-400">Confirmed Facts</p>
                  <div className="mt-2 space-y-2">
                    {memory.confirmedFacts.length === 0 ? (
                      <p className="text-slate-400">暂无</p>
                    ) : (
                      memory.confirmedFacts.map((fact) => (
                        <div key={fact} className="rounded-xl bg-slate-50 px-3 py-2 dark:bg-slate-950">
                          {fact}
                        </div>
                      ))
                    )}
                  </div>
                </div>
                <div>
                  <p className="text-xs uppercase tracking-wide text-slate-400">Used Tools</p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {memory.usedTools.length === 0 ? (
                      <p className="text-slate-400">暂无</p>
                    ) : (
                      memory.usedTools.map((tool) => (
                        <span
                          key={tool}
                          className="rounded-full bg-amber-100 px-3 py-1 text-xs font-semibold text-amber-700 dark:bg-amber-500/10 dark:text-amber-300"
                        >
                          {tool}
                        </span>
                      ))
                    )}
                  </div>
                </div>
              </div>
            ) : (
              <p className="mt-4 text-sm text-slate-400">创建会话后展示。</p>
            )}
          </section>

          <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <h2 className="text-lg font-semibold text-slate-900 dark:text-white">Trace</h2>
            <div className="mt-4 space-y-4">
              {traceSteps.length === 0 ? (
                <p className="text-sm text-slate-400">暂无执行轨迹。</p>
              ) : (
                traceSteps.map((step) => (
                  <div key={step.stepIndex} className="rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
                    <div className="mb-2 flex items-center justify-between">
                      <span className="text-sm font-semibold text-slate-900 dark:text-white">Step {step.stepIndex}</span>
                      <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-500 dark:bg-slate-800 dark:text-slate-300">
                        {step.status}
                      </span>
                    </div>
                    <div className="space-y-2 text-xs leading-6 text-slate-500 dark:text-slate-400">
                      <p><span className="font-semibold text-slate-700 dark:text-slate-200">Decision:</span> {step.decisionSummary || '暂无'}</p>
                      <p><span className="font-semibold text-slate-700 dark:text-slate-200">Tool:</span> {step.selectedTool || '无'}</p>
                      <div>
                        <p className="font-semibold text-slate-700 dark:text-slate-200">Input</p>
                        <pre className="mt-1 overflow-x-auto rounded-xl bg-slate-50 p-3 dark:bg-slate-950">{prettyJson(step.toolInputJson)}</pre>
                      </div>
                      <div>
                        <p className="font-semibold text-slate-700 dark:text-slate-200">Output</p>
                        <pre className="mt-1 overflow-x-auto rounded-xl bg-slate-50 p-3 dark:bg-slate-950">{prettyJson(step.toolOutputJson)}</pre>
                      </div>
                      {step.errorMessage && (
                        <p className="text-rose-500 dark:text-rose-300">
                          <span className="font-semibold">Error:</span> {step.errorMessage}
                        </p>
                      )}
                    </div>
                  </div>
                ))
              )}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
