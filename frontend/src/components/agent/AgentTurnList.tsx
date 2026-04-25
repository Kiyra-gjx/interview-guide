import { Clock3, MessageSquareText } from 'lucide-react';
import type { AgentTurnSummary } from '../../types/agent';
import { formatDateTime } from '../../utils/date';
import AgentStatusBadge from './AgentStatusBadge';

interface AgentTurnListProps {
  turns: AgentTurnSummary[];
  selectedTurnId: string | null;
  onSelectTurn: (turnId: string) => void;
  loading?: boolean;
}

/**
 * 渲染工作台左侧的 turn 时间线。
 * 列表只展示摘要信息，真正的 trace 和审批细节交给右侧明细面板加载。
 */
export default function AgentTurnList({
  turns,
  selectedTurnId,
  onSelectTurn,
  loading = false,
}: AgentTurnListProps) {
  // turn 列表为空时给出明确占位，避免用户误以为加载失败。
  if (!loading && turns.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-slate-300/80 px-4 py-8 text-center text-sm text-slate-400 dark:border-slate-700 dark:text-slate-500">
        当前会话还没有 turn。发送第一条消息后，这里会出现执行时间线。
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {turns.map((turn) => {
        const selected = turn.turnId === selectedTurnId;

        return (
          <button
            key={turn.turnId}
            type="button"
            onClick={() => onSelectTurn(turn.turnId)}
            className={`w-full rounded-2xl border px-4 py-4 text-left transition ${
              selected
                ? 'border-primary-500 bg-primary-50 shadow-sm dark:border-primary-400 dark:bg-primary-900/20'
                : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:hover:border-slate-500 dark:hover:bg-slate-800'
            }`}
          >
            {/* 顶部优先展示 turn 状态和收口模式，方便快速扫读时间线。 */}
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm font-semibold text-slate-900 dark:text-white">
                  Turn {turn.turnId.slice(0, 8)}
                </p>
                <p className="mt-1 flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                  <Clock3 className="h-3.5 w-3.5" />
                  {resolveTurnTime(turn)}
                </p>
              </div>
              <div className="flex flex-col items-end gap-2">
                <AgentStatusBadge kind="turn" value={turn.status} />
                <AgentStatusBadge kind="completion" value={turn.completionMode} />
              </div>
            </div>

            {/* 中间用用户消息和助手回复预览表达本轮执行意图和结果。 */}
            <div className="mt-4 space-y-3">
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-400">用户问题</p>
                <p className="mt-1 text-sm leading-6 text-slate-700 dark:text-slate-200">
                  {turn.userMessagePreview || '暂无用户消息'}
                </p>
              </div>
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-400">助手结果</p>
                <p className="mt-1 flex items-start gap-2 text-sm leading-6 text-slate-600 dark:text-slate-300">
                  <MessageSquareText className="mt-1 h-4 w-4 flex-none text-slate-400" />
                  <span>{turn.assistantReplyPreview || turn.errorMessage || '本轮尚未产出回复'}</span>
                </p>
              </div>
            </div>
          </button>
        );
      })}
    </div>
  );
}

/**
 * 统一挑选 turn 列表中最有意义的时间点。
 */
function resolveTurnTime(turn: AgentTurnSummary): string {
  return formatDateTime(turn.finishedAt || turn.startedAt || turn.createdAt);
}
