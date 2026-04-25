import { Clock3, ShieldAlert } from 'lucide-react';
import type { AgentApproval } from '../../types/agent';
import { formatDateTime } from '../../utils/date';
import AgentStatusBadge from './AgentStatusBadge';

interface AgentApprovalQueueProps {
  approvals: AgentApproval[];
  activeTurnId: string | null;
  actingApprovalId: string | null;
  onApprove: (approvalId: string) => void;
  onReject: (approvalId: string) => void;
}

/**
 * 渲染会话级审批队列。
 * 这里把待决审批和历史审批统一展示，方便工作台同时完成“当前操作”和“结果复盘”。
 */
export default function AgentApprovalQueue({
  approvals,
  activeTurnId,
  actingApprovalId,
  onApprove,
  onReject,
}: AgentApprovalQueueProps) {
  // 队列为空时返回稳定占位，避免右侧面板断层。
  if (approvals.length === 0) {
    return <p className="text-sm text-slate-400 dark:text-slate-500">当前会话没有审批记录。</p>;
  }

  return (
    <div className="space-y-3">
      {approvals.map((approval) => {
        const pending = approval.status === 'PENDING';
        const acting = approval.approvalId === actingApprovalId;
        const highlighted = approval.turnId === activeTurnId;

        return (
          <div
            key={approval.approvalId}
            className={`rounded-2xl border px-4 py-4 ${
              highlighted
                ? 'border-primary-500/50 bg-primary-50/70 dark:border-primary-400/60 dark:bg-primary-900/15'
                : 'border-slate-200 bg-slate-50/80 dark:border-slate-700 dark:bg-slate-950/60'
            }`}
          >
            {/* 顶部先展示工具名、风险和审批状态，方便用户判断是否需要处理。 */}
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="flex items-center gap-2 text-sm font-semibold text-slate-900 dark:text-white">
                  <ShieldAlert className="h-4 w-4 text-fuchsia-500" />
                  {approval.selectedTool}
                </p>
                <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                  Turn {approval.turnId.slice(0, 8)}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <AgentStatusBadge kind="risk" value={approval.riskLevel} />
                <AgentStatusBadge kind="approval" value={approval.status} />
              </div>
            </div>

            {/* 原因和时间信息用于解释为什么进入审批以及当前是否还可处理。 */}
            <div className="mt-3 space-y-2 text-sm text-slate-600 dark:text-slate-300">
              <p>{approval.reason || '未提供审批原因。'}</p>
              <p className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                <Clock3 className="h-3.5 w-3.5" />
                发起于 {formatDateTime(approval.createdAt)}
                {approval.expiresAt ? ` · 截止 ${formatDateTime(approval.expiresAt)}` : ''}
              </p>
            </div>

            {/* 只有待决审批允许直接操作，其他状态只展示历史结果。 */}
            {pending && (
              <div className="mt-4 flex gap-2">
                <button
                  type="button"
                  onClick={() => onApprove(approval.approvalId)}
                  disabled={acting}
                  className="inline-flex items-center rounded-xl bg-emerald-500 px-3.5 py-2 text-sm font-semibold text-white transition hover:bg-emerald-600 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {acting ? '处理中...' : '批准执行'}
                </button>
                <button
                  type="button"
                  onClick={() => onReject(approval.approvalId)}
                  disabled={acting}
                  className="inline-flex items-center rounded-xl border border-rose-200 px-3.5 py-2 text-sm font-semibold text-rose-600 transition hover:border-rose-300 hover:bg-rose-50 disabled:cursor-not-allowed disabled:opacity-60 dark:border-rose-500/40 dark:text-rose-300 dark:hover:bg-rose-500/10"
                >
                  拒绝执行
                </button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
