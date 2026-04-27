import type {
  AgentApprovalStatus,
  AgentCompletionMode,
  AgentExecutionState,
  AgentTerminalState,
  AgentToolRiskLevel,
  AgentTurnStatus,
} from '../../types/agent';

type AgentBadgeKind = 'turn' | 'execution' | 'completion' | 'terminal' | 'approval' | 'risk';

interface AgentStatusBadgeProps {
  kind: AgentBadgeKind;
  value: AgentTurnStatus | AgentExecutionState | AgentCompletionMode | AgentTerminalState | AgentApprovalStatus | AgentToolRiskLevel | null | undefined;
  className?: string;
}

interface BadgeMeta {
  label: string;
  className: string;
}

/**
 * 统一渲染 Agent 工作台中的状态徽标。
 * 把颜色和文案集中在这里，避免页面和各个面板各自维护一套状态映射。
 */
export default function AgentStatusBadge({ kind, value, className = '' }: AgentStatusBadgeProps) {
  // 统一在渲染前解析颜色和中文文案，保证各面板读到的是同一套状态语义。
  const meta = resolveBadgeMeta(kind, value);

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold ${meta.className} ${className}`}
    >
      {meta.label}
    </span>
  );
}

/**
 * 根据状态类型和值返回对应的中文标签和颜色。
 */
function resolveBadgeMeta(kind: AgentBadgeKind, value: AgentStatusBadgeProps['value']): BadgeMeta {
  switch (kind) {
    case 'turn':
      return turnStatusMeta(value as AgentTurnStatus | null | undefined);
    case 'execution':
      return executionStateMeta(value as AgentExecutionState | null | undefined);
    case 'completion':
      return completionModeMeta(value as AgentCompletionMode | null | undefined);
    case 'terminal':
      return terminalStateMeta(value as AgentTerminalState | null | undefined);
    case 'approval':
      return approvalStatusMeta(value as AgentApprovalStatus | null | undefined);
    case 'risk':
      return riskLevelMeta(value as AgentToolRiskLevel | null | undefined);
    default:
      return unknownMeta();
  }
}

/**
 * 解析 turn 状态徽标。
 */
function turnStatusMeta(value: AgentTurnStatus | null | undefined): BadgeMeta {
  switch (value) {
    case 'CREATED':
      return badge('已创建', 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300');
    case 'RUNNING':
      return badge('执行中', 'bg-sky-100 text-sky-700 dark:bg-sky-500/15 dark:text-sky-300');
    case 'WAITING_APPROVAL':
      return badge('待审批', 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300');
    case 'COMPLETED':
      return badge('已完成', 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300');
    case 'FAILED':
      return badge('失败', 'bg-rose-100 text-rose-700 dark:bg-rose-500/15 dark:text-rose-300');
    case 'ABORTED':
      return badge('已回收', 'bg-zinc-200 text-zinc-700 dark:bg-zinc-700 dark:text-zinc-200');
    default:
      return unknownMeta();
  }
}

/**
 * 解析 step 执行状态徽标。
 */
function executionStateMeta(value: AgentExecutionState | null | undefined): BadgeMeta {
  switch (value) {
    case 'CREATED':
      return badge('待开始', 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300');
    case 'RUNNING':
      return badge('运行中', 'bg-sky-100 text-sky-700 dark:bg-sky-500/15 dark:text-sky-300');
    case 'WAITING_APPROVAL':
      return badge('审批中', 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300');
    case 'TERMINATED':
      return badge('已终止', 'bg-zinc-200 text-zinc-700 dark:bg-zinc-700 dark:text-zinc-200');
    case 'COMPLETED':
      return badge('已完成', 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300');
    case 'FAILED':
      return badge('失败', 'bg-rose-100 text-rose-700 dark:bg-rose-500/15 dark:text-rose-300');
    default:
      return unknownMeta();
  }
}

/**
 * 解析 turn completionMode 徽标。
 */
function completionModeMeta(value: AgentCompletionMode | null | undefined): BadgeMeta {
  switch (value) {
    case 'SUCCESS':
      return badge('成功收口', 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300');
    case 'DEGRADED':
      return badge('降级收口', 'bg-orange-100 text-orange-700 dark:bg-orange-500/15 dark:text-orange-300');
    case 'WAITING_APPROVAL':
      return badge('等待审批', 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300');
    default:
      return badge('未收口', 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300');
  }
}

/**
 * 解析统一终态徽标。
 */
function terminalStateMeta(value: AgentTerminalState | null | undefined): BadgeMeta {
  switch (value) {
    case 'SUCCESS':
      return badge('终态: 成功', 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300');
    case 'DEGRADED':
      return badge('终态: 降级', 'bg-orange-100 text-orange-700 dark:bg-orange-500/15 dark:text-orange-300');
    case 'EXHAUSTED':
      return badge('终态: 耗尽', 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300');
    case 'WAITING_APPROVAL':
      return badge('终态: 待审批', 'bg-sky-100 text-sky-700 dark:bg-sky-500/15 dark:text-sky-300');
    case 'FAILED':
      return badge('终态: 失败', 'bg-rose-100 text-rose-700 dark:bg-rose-500/15 dark:text-rose-300');
    default:
      return badge('终态: 未知', 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300');
  }
}

/**
 * 解析审批状态徽标。
 */
function approvalStatusMeta(value: AgentApprovalStatus | null | undefined): BadgeMeta {
  switch (value) {
    case 'PENDING':
      return badge('待决定', 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300');
    case 'APPROVED':
      return badge('已批准', 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300');
    case 'REJECTED':
      return badge('已拒绝', 'bg-rose-100 text-rose-700 dark:bg-rose-500/15 dark:text-rose-300');
    case 'EXPIRED':
      return badge('已过期', 'bg-zinc-200 text-zinc-700 dark:bg-zinc-700 dark:text-zinc-200');
    default:
      return unknownMeta();
  }
}

/**
 * 解析工具风险等级徽标。
 */
function riskLevelMeta(value: AgentToolRiskLevel | null | undefined): BadgeMeta {
  switch (value) {
    case 'READ_ONLY':
      return badge('只读工具', 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300');
    case 'REQUIRES_APPROVAL':
      return badge('需审批', 'bg-fuchsia-100 text-fuchsia-700 dark:bg-fuchsia-500/15 dark:text-fuchsia-300');
    default:
      return unknownMeta();
  }
}

/**
 * 统一生成徽标元数据。
 */
function badge(label: string, className: string): BadgeMeta {
  return { label, className };
}

/**
 * 为未知状态提供稳定兜底，避免页面直接显示空白。
 */
function unknownMeta(): BadgeMeta {
  return badge('未知状态', 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300');
}
