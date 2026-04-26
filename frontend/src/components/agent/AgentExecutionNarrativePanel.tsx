import { CheckCircle2, Compass, ShieldAlert, TriangleAlert } from 'lucide-react';
import type { ReactNode } from 'react';
import type {
  AgentApproval,
  AgentSession,
  AgentTurnDetail,
} from '../../types/agent';
import { buildExecutionNarrative } from './agentDemoFlow';

interface AgentExecutionNarrativePanelProps {
  session: AgentSession | null;
  detail: AgentTurnDetail | null;
  approvals: AgentApproval[];
  loading: boolean;
  turnCount: number;
}

/**
 * Stage 4 的执行解释面板。
 * 负责把“为什么成功 / 为什么降级 / 下一步看哪里”收口成可直接讲述的叙事。
 */
export default function AgentExecutionNarrativePanel({
  session,
  detail,
  approvals,
  loading,
  turnCount,
}: AgentExecutionNarrativePanelProps) {
  // 加载态必须优先于 session 判定。
  // 原因是首次创建会话或刷新 workbench 时，session/title 可能还没回填，但当前 turn 的明细已经在拉取。
  if (loading && !detail) {
    return (
      <NarrativePlaceholderCard
        icon={<Compass className="mt-0.5 h-5 w-5 text-primary-500" />}
        title="正在加载当前 turn 的执行解释..."
        summary="工作台正在拉取当前 turn 的明细，并准备生成这轮执行的收口叙事。"
        body="请稍等片刻；明细返回后，当前面板会自动切换到这轮 turn 的执行解释。"
      />
    );
  }

  if (!session) {
    return (
      <section className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div className="flex items-start gap-3">
          <Compass className="mt-0.5 h-5 w-5 text-primary-500" />
          <div>
            <h2 className="text-lg font-semibold text-slate-900 dark:text-white">Demo Flow 导航</h2>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              推荐先选一个场景，再绑定资源并创建会话。这样工作台才有明确的成功或降级叙事。
            </p>
          </div>
        </div>

        <div className="mt-5 space-y-3">
          {[
            '1. 在左侧 Demo Flow 场景里选一条推荐路径。',
            '2. 按提示绑定简历或知识库，再点击“创建 Agent 会话”。',
            '3. 在中间对话区发起首个 turn。',
            '4. 回到 Session Memory 和 Trace Browser 观察事实写回与调试证据。',
          ].map((step) => (
            <p
              key={step}
              className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-600 dark:border-slate-700 dark:bg-slate-950/60 dark:text-slate-300"
            >
              {step}
            </p>
          ))}
        </div>
      </section>
    );
  }

  if (!detail && turnCount === 0) {
    return (
      <NarrativePlaceholderCard
        icon={<Compass className="mt-0.5 h-5 w-5 text-primary-500" />}
        title="会话已就绪，下一步发起首个 Turn"
        summary="当前 session 已创建，但还没有可解释的 turn 明细。先发起一轮执行，再回来观察收口原因。"
        body={`当前会话：${session.title}。建议优先跑一条成功路径或降级路径，让时间线、Memory 和 Trace Browser 都有真实数据可看。`}
      />
    );
  }

  if (!detail) {
    return (
      <NarrativePlaceholderCard
        icon={<TriangleAlert className="mt-0.5 h-5 w-5 text-amber-500" />}
        title="当前 turn 明细暂时不可用"
        summary="当前会话已经存在 turn，但执行解释所需的 turn 明细没有成功就位。"
        body="可以重新选择目标 turn，或刷新工作台后再试；这样可以避免把“没有 turn”和“明细暂缺”讲成同一件事。"
      />
    );
  }

  const narrative = buildExecutionNarrative(detail, approvals);
  const toneClassName = resolveToneClassName(narrative.tone);
  const icon = resolveNarrativeIcon(narrative.tone);

  return (
    <section className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-start gap-3">
        {icon}
        <div>
          <h2 className="text-lg font-semibold text-slate-900 dark:text-white">{narrative.title}</h2>
          <p className="mt-1 text-sm leading-6 text-slate-500 dark:text-slate-400">{narrative.summary}</p>
        </div>
      </div>

      <div className={`mt-5 rounded-3xl border px-4 py-4 ${toneClassName}`}>
        <p className="text-xs font-semibold uppercase tracking-wide">下一步建议</p>
        <p className="mt-2 text-sm leading-6">{narrative.nextStep}</p>
      </div>

      <div className="mt-5">
        <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">当前证据面</p>
        <div className="mt-3 flex flex-wrap gap-2">
          {narrative.evidence.map((line) => (
            <span
              key={line}
              className="inline-flex items-center rounded-full bg-slate-200 px-3 py-1 text-xs font-semibold text-slate-700 dark:bg-slate-700 dark:text-slate-200"
            >
              {line}
            </span>
          ))}
        </div>
      </div>
    </section>
  );
}

interface NarrativePlaceholderCardProps {
  icon: ReactNode;
  title: string;
  summary: string;
  body: string;
}

/**
 * 统一承载执行解释面板的占位状态，避免加载中、无 turn 和明细缺失各自散落一套结构。
 */
function NarrativePlaceholderCard({
  icon,
  title,
  summary,
  body,
}: NarrativePlaceholderCardProps) {
  return (
    <section className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-start gap-3">
        {icon}
        <div>
          <h2 className="text-lg font-semibold text-slate-900 dark:text-white">{title}</h2>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{summary}</p>
        </div>
      </div>

      <div className="mt-5 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-4 text-sm leading-6 text-slate-600 dark:border-slate-700 dark:bg-slate-950/60 dark:text-slate-300">
        {body}
      </div>
    </section>
  );
}

/**
 * 根据叙事语气挑选对应的强调色。
 */
function resolveToneClassName(tone: 'neutral' | 'success' | 'warning' | 'danger') {
  switch (tone) {
    case 'success':
      return 'border-emerald-200 bg-emerald-50/80 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200';
    case 'warning':
      return 'border-amber-200 bg-amber-50/80 text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200';
    case 'danger':
      return 'border-rose-200 bg-rose-50/80 text-rose-800 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200';
    default:
      return 'border-slate-200 bg-slate-50/80 text-slate-700 dark:border-slate-700 dark:bg-slate-950/60 dark:text-slate-200';
  }
}

/**
 * 根据叙事语气返回最贴近的视觉图标。
 */
function resolveNarrativeIcon(tone: 'neutral' | 'success' | 'warning' | 'danger') {
  switch (tone) {
    case 'success':
      return <CheckCircle2 className="mt-0.5 h-5 w-5 text-emerald-500" />;
    case 'warning':
      return <ShieldAlert className="mt-0.5 h-5 w-5 text-amber-500" />;
    case 'danger':
      return <TriangleAlert className="mt-0.5 h-5 w-5 text-rose-500" />;
    default:
      return <Compass className="mt-0.5 h-5 w-5 text-primary-500" />;
  }
}
