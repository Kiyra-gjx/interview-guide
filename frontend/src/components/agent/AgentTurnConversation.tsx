import type { KeyboardEvent } from 'react';
import { AlertTriangle, Bot, SendHorizontal, ShieldAlert, User } from 'lucide-react';
import type { AgentTurnDetail } from '../../types/agent';
import AgentStatusBadge from './AgentStatusBadge';

interface AgentTurnConversationProps {
  hasSession: boolean;
  detail: AgentTurnDetail | null;
  draft: string;
  sending: boolean;
  onDraftChange: (value: string) => void;
  onSend: () => void;
}

/**
 * 展示当前选中 turn 的对话内容，并承接新的用户输入。
 * 这里保留“对话感”，但按 turn 聚焦，不再退回成只有消息流的聊天页。
 */
export default function AgentTurnConversation({
  hasSession,
  detail,
  draft,
  sending,
  onDraftChange,
  onSend,
}: AgentTurnConversationProps) {
  /**
   * 在输入框内支持快捷发送，减少工作台里的重复鼠标操作。
   */
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // 只在 Ctrl/Cmd + Enter 时发送，普通回车保留换行能力。
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
      event.preventDefault();
      onSend();
    }
  }

  const disabled = !hasSession || sending;

  return (
    <div className="flex h-full flex-col rounded-[28px] border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
      {/* 顶部摘要明确告诉用户“当前看的就是哪一轮 turn”。 */}
      <div className="border-b border-slate-200 px-6 py-5 dark:border-slate-800">
        {detail ? (
          <div className="space-y-3">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="text-lg font-semibold text-slate-900 dark:text-white">
                  当前 Turn {detail.turn.turnId.slice(0, 8)}
                </h2>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                  用用户视角回看本轮输入与输出，调试信息放在下方 trace 区。
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <AgentStatusBadge kind="turn" value={detail.turn.status} />
                <AgentStatusBadge kind="completion" value={detail.turn.completionMode} />
              </div>
            </div>
            {detail.guardrailResults.length > 0 && (
              <div className="flex items-start gap-2 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
                <ShieldAlert className="mt-0.5 h-4 w-4 flex-none" />
                <span>本轮命中 {detail.guardrailResults.length} 条 guardrail，详细原因见下方 trace 浏览器。</span>
              </div>
            )}
            {detail.turn.errorMessage && (
              <div className="flex items-start gap-2 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200">
                <AlertTriangle className="mt-0.5 h-4 w-4 flex-none" />
                <span>{detail.turn.errorMessage}</span>
              </div>
            )}
          </div>
        ) : (
          <div>
            <h2 className="text-lg font-semibold text-slate-900 dark:text-white">用户视角</h2>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              {hasSession ? '发送第一条消息后，这里会按 turn 展示输入与输出。' : '先创建会话，再开始一轮 Agent 执行。'}
            </p>
          </div>
        )}
      </div>

      {/* 中部只展示当前 turn 的消息增量，避免不同 turn 的消息混在一起。 */}
      <div className="flex-1 overflow-y-auto bg-slate-50/80 px-6 py-5 dark:bg-slate-950/60">
        {detail && detail.messages.length > 0 ? (
          <div className="space-y-4">
            {detail.messages.map((message) => (
              <div
                key={`${message.role}-${message.messageOrder}`}
                className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}
              >
                <div
                  className={`max-w-[88%] rounded-3xl px-4 py-3 shadow-sm ${
                    message.role === 'user'
                      ? 'bg-slate-900 text-white dark:bg-primary-500'
                      : 'border border-slate-200 bg-white text-slate-700 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200'
                  }`}
                >
                  <div className="mb-2 flex items-center gap-2 text-xs font-semibold opacity-80">
                    {message.role === 'user' ? (
                      <>
                        <User className="h-3.5 w-3.5" />
                        <span>用户输入</span>
                      </>
                    ) : (
                      <>
                        <Bot className="h-3.5 w-3.5" />
                        <span>Agent 回复</span>
                      </>
                    )}
                  </div>
                  <p className="whitespace-pre-wrap text-sm leading-7">{message.content}</p>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="flex h-full items-center justify-center rounded-3xl border border-dashed border-slate-300/80 bg-white/80 px-6 text-center text-sm text-slate-400 dark:border-slate-700 dark:bg-slate-900/70 dark:text-slate-500">
            {hasSession ? '当前还没有可展示的 turn 明细。' : '创建会话后，这里会显示当前 turn 的对话内容。'}
          </div>
        )}
      </div>

      {/* 底部保留新的输入入口，确保工作台不仅能观察，也能直接驱动新一轮 turn。 */}
      <div className="border-t border-slate-200 px-6 py-5 dark:border-slate-800">
        <div className="space-y-3">
          <textarea
            value={draft}
            onChange={(event) => onDraftChange(event.target.value)}
            onKeyDown={handleKeyDown}
            rows={4}
            disabled={disabled}
            className="w-full resize-none rounded-3xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-primary-400 focus:bg-white disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700 dark:bg-slate-950 dark:text-white"
            placeholder="例如：结合我的简历，先给我一个 Redis 场景题，并说明为什么选它。"
          />
          <div className="flex items-center justify-between gap-3">
            <p className="text-xs text-slate-400">快捷发送：Ctrl/Cmd + Enter</p>
            <button
              type="button"
              onClick={onSend}
              disabled={disabled}
              className="inline-flex items-center gap-2 rounded-2xl bg-primary-500 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <SendHorizontal className="h-4 w-4" />
              {sending ? '执行中...' : '发起新 Turn'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
