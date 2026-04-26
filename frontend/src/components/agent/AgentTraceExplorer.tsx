import { useEffect, useState } from 'react';
import { AlertTriangle, MessageSquare, ShieldAlert, Wrench } from 'lucide-react';
import type { AgentToolOutputNormalization, AgentTraceStep, AgentTurnDetail } from '../../types/agent';
import CodeBlock from '../CodeBlock';
import AgentStatusBadge from './AgentStatusBadge';
import {
  getTraceToolPresentation,
  type AgentTraceToolPresentationKind,
} from './agentTraceToolPresentation';

interface AgentTraceExplorerProps {
  detail: AgentTurnDetail | null;
  loading?: boolean;
}

/**
 * 浏览单个 turn 的 trace、toolOutput、guardrail 和 memory 快照。
 * 这是工作台的调试面，不承担发起执行，只负责把已有运行时证据讲清楚。
 */
export default function AgentTraceExplorer({ detail, loading = false }: AgentTraceExplorerProps) {
  const [selectedStepIndex, setSelectedStepIndex] = useState<number | null>(null);

  useEffect(() => {
    // 选中的 turn 改变后，默认落到第一条 step，避免停留在旧 turn 的 stepIndex 上。
    setSelectedStepIndex(detail?.traceSteps[0]?.stepIndex ?? null);
  }, [detail?.turn.turnId, detail?.traceSteps]);

  const selectedStep = resolveSelectedStep(detail?.traceSteps ?? [], selectedStepIndex);
  const selectedStepPresentation = getTraceToolPresentation(selectedStep?.selectedTool);
  const normalizationFlags = selectedStep?.toolOutput ? collectNormalizationFlags(selectedStep.toolOutput.normalization) : [];

  if (loading) {
    return (
      <div className="rounded-[28px] border border-slate-200 bg-white px-6 py-10 text-center text-sm text-slate-400 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-500">
        正在加载 turn 明细...
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="rounded-[28px] border border-dashed border-slate-300/80 bg-white px-6 py-10 text-center text-sm text-slate-400 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-500">
        选择一个 turn 后，这里会展示对应的 trace、tool output、guardrail 和 memory 快照。
      </div>
    );
  }

  return (
    <div className="rounded-[28px] border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
      {/* 顶部先总结本轮 trace 覆盖情况，帮助用户快速判断是否进入明细。 */}
      <div className="border-b border-slate-200 px-6 py-5 dark:border-slate-800">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold text-slate-900 dark:text-white">调试视角</h2>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              当前 turn 共 {detail.traceSteps.length} 个 step，审批 {detail.approvals.length} 条，guardrail 命中 {detail.guardrailResults.length} 次。
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <AgentStatusBadge kind="turn" value={detail.turn.status} />
            <AgentStatusBadge kind="completion" value={detail.turn.completionMode} />
          </div>
        </div>
      </div>

      {/* 左侧展示 step 时间线，右侧展示当前 step 的完整调试信息。 */}
      <div className="grid gap-0 xl:grid-cols-[280px_minmax(0,1fr)]">
        <div className="border-b border-slate-200 p-4 xl:border-b-0 xl:border-r dark:border-slate-800">
          {detail.traceSteps.length === 0 ? (
            <p className="rounded-2xl border border-dashed border-slate-300/80 px-4 py-6 text-center text-sm text-slate-400 dark:border-slate-700 dark:text-slate-500">
              当前 turn 还没有 trace step。
            </p>
          ) : (
            <div className="space-y-3">
              {detail.traceSteps.map((step) => {
                const selected = step.stepIndex === selectedStep?.stepIndex;
                const stepToolPresentation = getTraceToolPresentation(step.selectedTool);
                const StepToolIcon = resolveTraceToolIcon(stepToolPresentation.kind);

                return (
                  <button
                    key={step.stepIndex}
                    type="button"
                    onClick={() => setSelectedStepIndex(step.stepIndex)}
                    className={`w-full rounded-2xl border px-4 py-3 text-left transition ${
                      selected
                        ? 'border-primary-500 bg-primary-50 dark:border-primary-400 dark:bg-primary-900/20'
                        : 'border-slate-200 bg-slate-50 hover:border-slate-300 dark:border-slate-700 dark:bg-slate-950 dark:hover:border-slate-500'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <p className="text-sm font-semibold text-slate-900 dark:text-white">Step {step.stepIndex}</p>
                        <p className="mt-1 flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                          <StepToolIcon className="h-3.5 w-3.5" />
                          {stepToolPresentation.label}
                        </p>
                      </div>
                      <AgentStatusBadge kind="execution" value={step.status} />
                    </div>
                    <p className="mt-3 line-clamp-2 text-sm leading-6 text-slate-600 dark:text-slate-300">
                      {step.observationSummary || step.decisionSummary || '暂无 step 摘要'}
                    </p>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div className="p-6">
          {selectedStep ? (
            <div className="space-y-6">
              {/* step 摘要区负责讲清楚决策、工具和错误状态。 */}
              <section className="grid gap-4 lg:grid-cols-2">
                <div className="rounded-3xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-700 dark:bg-slate-950/60">
                  <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">执行摘要</p>
                  <div className="mt-3 space-y-3 text-sm text-slate-600 dark:text-slate-300">
                    <p>
                      <span className="font-semibold text-slate-900 dark:text-white">决策：</span>
                      {selectedStep.decisionSummary || '暂无'}
                    </p>
                    <p>
                      <span className="font-semibold text-slate-900 dark:text-white">
                        {selectedStepPresentation.fieldLabel}：
                      </span>
                      {selectedStepPresentation.label}
                    </p>
                    <p>
                      <span className="font-semibold text-slate-900 dark:text-white">观察：</span>
                      {selectedStep.observationSummary || '暂无'}
                    </p>
                  </div>
                </div>

                <div className="rounded-3xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-700 dark:bg-slate-950/60">
                  <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">状态信息</p>
                  <div className="mt-3 flex flex-wrap gap-2">
                    <AgentStatusBadge kind="execution" value={selectedStep.status} />
                    {selectedStep.toolOutput?.kind ? (
                      <span className="inline-flex items-center rounded-full bg-slate-200 px-2.5 py-1 text-xs font-semibold text-slate-700 dark:bg-slate-700 dark:text-slate-200">
                        {selectedStep.toolOutput.kind}
                      </span>
                    ) : null}
                  </div>
                  {selectedStep.errorMessage && (
                    <div className="mt-4 flex items-start gap-2 rounded-2xl border border-rose-200 bg-rose-50 px-3 py-3 text-sm text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200">
                      <AlertTriangle className="mt-0.5 h-4 w-4 flex-none" />
                      <span>{selectedStep.errorMessage}</span>
                    </div>
                  )}
                </div>
              </section>

              {/* guardrail 命中单独展示，避免被淹没在原始 JSON 里。 */}
              {selectedStep.guardrailResults.length > 0 && (
                <section className="rounded-3xl border border-amber-200 bg-amber-50 px-4 py-4 dark:border-amber-500/30 dark:bg-amber-500/10">
                  <p className="flex items-center gap-2 text-sm font-semibold text-amber-800 dark:text-amber-200">
                    <ShieldAlert className="h-4 w-4" />
                    Guardrail 命中
                  </p>
                  <div className="mt-3 space-y-2">
                    {selectedStep.guardrailResults.map((guardrail, index) => (
                      <div
                        key={`${guardrail.code ?? 'unknown'}-${index}`}
                        className="rounded-2xl bg-white/70 px-3 py-3 text-sm text-amber-900 dark:bg-slate-900/40 dark:text-amber-100"
                      >
                        <p className="font-semibold">
                          {guardrail.code || '未命名规则'}
                          {guardrail.stage ? ` · ${guardrail.stage}` : ''}
                        </p>
                        <p className="mt-1">{guardrail.reason || '未提供原因。'}</p>
                      </div>
                    ))}
                  </div>
                </section>
              )}

              {/* toolOutput 使用结构化视图优先展示，再把原始 JSON 放到下方。 */}
              {selectedStep.toolOutput && (
                <section className="space-y-4">
                  <div className="rounded-3xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-700 dark:bg-slate-950/60">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">Tool Output</p>
                        <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">
                          {selectedStep.toolOutput.summary || '暂无 summary。'}
                        </p>
                      </div>
                      {normalizationFlags.length > 0 && (
                        <div className="flex flex-wrap gap-2">
                          {normalizationFlags.map((flag) => (
                            <span
                              key={flag}
                              className="inline-flex items-center rounded-full bg-orange-100 px-2.5 py-1 text-xs font-semibold text-orange-700 dark:bg-orange-500/15 dark:text-orange-300"
                            >
                              {flag}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                    {selectedStep.toolOutput.reply && (
                      <div className="mt-4 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm leading-7 text-slate-700 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200">
                        {selectedStep.toolOutput.reply}
                      </div>
                    )}
                    {selectedStep.toolOutput.facts.length > 0 && (
                      <div className="mt-4">
                        <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">确认事实</p>
                        <div className="mt-2 flex flex-wrap gap-2">
                          {selectedStep.toolOutput.facts.map((fact) => (
                            <span
                              key={fact}
                              className="inline-flex items-center rounded-full bg-slate-200 px-3 py-1 text-xs text-slate-700 dark:bg-slate-700 dark:text-slate-200"
                            >
                              {fact}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>

                  <div className="grid gap-4 xl:grid-cols-2">
                    <div>
                      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">结构化 Answer</p>
                      <CodeBlock language="json">{prettyJson(selectedStep.toolOutput.answer)}</CodeBlock>
                    </div>
                    <div>
                      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">结构化 Debug</p>
                      <CodeBlock language="json">{prettyJson(selectedStep.toolOutput.debug)}</CodeBlock>
                    </div>
                  </div>
                </section>
              )}

              {/* 原始输入输出和 memory 快照保留给排障场景使用。 */}
              <section className="grid gap-4 xl:grid-cols-2">
                <div>
                  <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">工具输入</p>
                  <CodeBlock language="json">{prettyJson(selectedStep.toolInputJson)}</CodeBlock>
                </div>
                <div>
                  <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">原始输出</p>
                  <CodeBlock language="json">{prettyJson(selectedStep.toolOutputJson)}</CodeBlock>
                </div>
                <div>
                  <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">Memory Before</p>
                  <CodeBlock language="json">{prettyJson(selectedStep.memoryBefore)}</CodeBlock>
                </div>
                <div>
                  <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">Memory After</p>
                  <CodeBlock language="json">{prettyJson(selectedStep.memoryAfter)}</CodeBlock>
                </div>
              </section>
            </div>
          ) : (
            <div className="rounded-3xl border border-dashed border-slate-300/80 px-4 py-10 text-center text-sm text-slate-400 dark:border-slate-700 dark:text-slate-500">
              当前 turn 没有可展示的 step 明细。
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * 在 trace step 列表中定位当前选中的 step；如果没有命中，就回退到第一条。
 */
function resolveSelectedStep(steps: AgentTraceStep[], selectedStepIndex: number | null): AgentTraceStep | null {
  if (steps.length === 0) {
    return null;
  }
  return steps.find((step) => step.stepIndex === selectedStepIndex) || steps[0];
}

/**
 * 统一把原始 JSON 字符串或对象格式化成可读文本。
 */
function prettyJson(value: unknown): string {
  if (value == null) {
    return '暂无';
  }
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

/**
 * 把 tool output 的归一化标记转成前端可展示的标签。
 */
function collectNormalizationFlags(normalization: AgentToolOutputNormalization | undefined): string[] {
  if (!normalization) {
    return [];
  }

  // 只暴露真实发生过的截断标记，避免噪声信息。
  const flags: string[] = [];
  if (normalization.summaryTruncated) {
    flags.push('summary 已截断');
  }
  if (normalization.answerTruncated) {
    flags.push('answer 已截断');
  }
  if (normalization.debugTruncated) {
    flags.push('debug 已截断');
  }
  if (normalization.factsTruncated) {
    flags.push('facts 已截断');
  }
  return flags;
}

/**
 * Trace Browser 里的图标只负责强化“工具路径”和“非工具路径”的区别，
 * 不参与业务判断，真正的规则统一由 presentation helper 维护。
 */
function resolveTraceToolIcon(kind: AgentTraceToolPresentationKind) {
  switch (kind) {
    case 'direct_reply':
      return MessageSquare;
    case 'guardrail':
      return ShieldAlert;
    default:
      return Wrench;
  }
}
