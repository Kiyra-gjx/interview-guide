import { Compass, FlaskConical, ListChecks } from 'lucide-react';
import type { AgentDemoScenario } from './agentDemoFlow';
import { AGENT_DEMO_SCENARIOS, resolveScenarioRequirements } from './agentDemoFlow';
import AgentStatusBadge from './AgentStatusBadge';

interface AgentDemoScenarioPanelProps {
  selectedResumeId: number | undefined;
  selectedKnowledgeBaseIds: number[];
  hasSession: boolean;
  onApplyScenario: (scenario: AgentDemoScenario) => void;
}

/**
 * Stage 4 的 demo 场景入口面板。
 * 负责把“怎么演示”先显式讲出来，再把目标和问题填回工作台输入区。
 */
export default function AgentDemoScenarioPanel({
  selectedResumeId,
  selectedKnowledgeBaseIds,
  hasSession,
  onApplyScenario,
}: AgentDemoScenarioPanelProps) {
  return (
    <section className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-semibold text-slate-900 dark:text-white">
            <Compass className="h-5 w-5 text-primary-500" />
            Demo Flow 场景
          </h2>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            先选一条真实可跑的路径，再用工作台观察成功或降级是怎样收口的。
          </p>
        </div>
        <span className="inline-flex items-center rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-300">
          {AGENT_DEMO_SCENARIOS.length} 条推荐路径
        </span>
      </div>

      <div className="mt-5 grid gap-4 xl:grid-cols-3">
        {AGENT_DEMO_SCENARIOS.map((scenario) => {
          const requirements = resolveScenarioRequirements(
            scenario,
            selectedResumeId,
            selectedKnowledgeBaseIds,
          );
          const ready = requirements.every((requirement) => requirement.satisfied);

          return (
            <article
              key={scenario.id}
              className="flex h-full flex-col rounded-3xl border border-slate-200 bg-slate-50/80 p-5 dark:border-slate-700 dark:bg-slate-950/60"
            >
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h3 className="text-base font-semibold text-slate-900 dark:text-white">{scenario.title}</h3>
                  <p className="mt-2 text-sm leading-6 text-slate-600 dark:text-slate-300">{scenario.description}</p>
                </div>
                <AgentStatusBadge kind="completion" value={scenario.expectedCompletionMode} />
              </div>

              <div className="mt-4 flex flex-wrap gap-2">
                {requirements.length > 0 ? (
                  requirements.map((requirement) => (
                    <span
                      key={requirement.key}
                      className={`inline-flex items-center rounded-full px-3 py-1 text-xs font-semibold ${
                        requirement.satisfied
                          ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300'
                          : 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300'
                      }`}
                    >
                      {requirement.label}
                      {requirement.satisfied ? ' · 已满足' : ' · 待补齐'}
                    </span>
                  ))
                ) : (
                  <span className="inline-flex items-center rounded-full bg-slate-200 px-3 py-1 text-xs font-semibold text-slate-700 dark:bg-slate-700 dark:text-slate-200">
                    无额外资源前置条件
                  </span>
                )}
              </div>

              <div className="mt-4 flex-1 space-y-3">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">推荐观察点</p>
                  <div className="mt-2 space-y-2">
                    {scenario.observations.map((observation) => (
                      <p
                        key={observation}
                        className="rounded-2xl border border-slate-200 bg-white px-3 py-3 text-sm leading-6 text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300"
                      >
                        {observation}
                      </p>
                    ))}
                  </div>
                </div>
              </div>

              <div className="mt-5 flex items-center justify-between gap-3">
                <span className={`inline-flex items-center gap-2 text-xs font-semibold ${ready ? 'text-emerald-600 dark:text-emerald-300' : 'text-amber-600 dark:text-amber-300'}`}>
                  <FlaskConical className="h-3.5 w-3.5" />
                  {ready ? '当前资源已可直接演示' : '可先填入输入区，再补齐资源'}
                </span>
                <button
                  type="button"
                  onClick={() => onApplyScenario(scenario)}
                  className="inline-flex items-center gap-2 rounded-2xl bg-primary-500 px-4 py-2 text-sm font-semibold text-white transition hover:bg-primary-600"
                >
                  <ListChecks className="h-4 w-4" />
                  填入输入区
                </button>
              </div>
            </article>
          );
        })}
      </div>

      <div className="mt-5 rounded-2xl border border-dashed border-slate-300/80 px-4 py-4 text-sm leading-6 text-slate-500 dark:border-slate-700 dark:text-slate-400">
        {hasSession
          ? '当前已有会话。修改训练目标后，记得点击“重建会话”，否则新 goal 不会进入已创建的 session。'
          : '推荐顺序：先填入一个场景，再按资源提示绑定简历或知识库，最后创建会话并发起新 turn。'}
      </div>
    </section>
  );
}
