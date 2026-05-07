# Stage 6: Evidence, Benchmark, and Resume Quantification

## 0. 阶段状态
- 阶段状态：已完成
- 已完成任务：S6-01、S6-02、S6-03
- 当前任务：无新的 Stage 6 正式任务；RAG 可信性、外部内容注入安全和工具路由评测转入 Stage 7
- 前置条件：Stage 5 主体实现已完成

## 0.1 当前进展

- Stage 2 已具备固定回归入口、JSON / Markdown 报告和 baseline / diff 机制，能够直接产出第一批可复核证据
- Stage 3 已暴露 context budget、section status、memory snapshot 等结构化信号，具备做上下文与记忆量化的基础
- Stage 5 已暴露 `multiStepEnabled`、`executedSteps`、`stopReason`、`budgetStopReason`、`terminalState`、`recoverable`、`recoveryHint`，并覆盖预算耗尽、approval 恢复、handoff 正反例等关键测试语义
- 当前主要成果不是新的运行时主逻辑，而是把这些离散信号收口成固定样本集、统一报告和可写进简历的量化证据
- `docs/agent-resume-quantification.md` 与 `docs/agent-quantification-record-template.md` 已吸收当前真实跑数结果，纳入 Stage 6 证据体系
- `S6-01` 已完成第一批基础设施落地：新增 Stage 5 benchmark 任务与脚本、统一证据目录 `docs/evidence/agent-quantification/`，并把 Stage 2 / Stage 5 的报告命名和 suite 结构固定下来
- `S6-02` 已完成四套固定样本定义：Context / Memory / Recovery / Safety 均已建立 suite 目录、样本清单、原始记录模板和 summary 模板
- `S6-03` 已完成 Stage 5 benchmark 当前基线版证据包：固定样本、原始结果、summary、baseline / diff 和 resume-safe 表述均已收口
- `stage-3-context-set` 已完成真实跑数：`10/10` case 通过，平均压缩率 `16.09%`，关键 section 保留率 `100%`
- `stage-3-memory-set` 已完成真实跑数：`9/9` case 通过，`repeatedToolCalls 3 -> 0`，`repeatedFactChecks 2 -> 0`
- `stage-5-recovery-set` 已完成真实跑数：`9/9` case 通过，恢复正确率 `100%`，`wrongStateContinued=0`，`replayedSideEffect=0`
- `stage-2-safety-set` 已完成真实跑数：`10/10` case 通过，高风险动作审批命中率 `100%`，审批拒绝后降级收口率 `100%`
- `stage-5-benchmark` 已完成当前基线证据包：`4/4` case 通过，覆盖 handoff 正反例、预算耗尽与 replay blocked 四类关键终态
- 后续补强不再继续塞进 Stage 6，统一进入 Stage 7：RAG Trust、Tool Routing、Injection Safety

## 1. 阶段目标

把前面阶段已经具备的 runtime 信号、trace 语义和测试入口，收口成一套稳定的证据工程体系。Stage 6 不追求“让 Agent 更强”，而追求“让已有能力可重复测、可留档、可比较、可安全写进简历”。

## 2. 本阶段必须解决的问题

- 当前仓库已经有一些真实信号，但缺少按能力块组织的固定样本集
- Stage 2 有回归报告，Stage 3 / Stage 5 还缺少同等级别的固定报告入口
- 项目已经能讲工程设计，但还不能稳定产出“样本数 + 指标 + 结果 + 边界”的简历级数据
- 一部分 GitHub issue 已被后续实现覆盖，但 issue 状态和仓库真实状态没有同步
- 如果没有专门的证据阶段，后续很容易回到“先写简历句子，再倒推数字”的失真路径

## 3. 本阶段交付物

- 统一的 Stage 6 评测与证据分工说明
- Context / Memory / Recovery / Safety / Stage 5 Benchmark 五类固定样本集定义
- 原始记录表、汇总结果表、baseline / diff 和代表性 trace 的归档规范
- 五组可重复执行的 Stage 2 / Stage 3 / Stage 5 量化入口
- “能写进简历”与“还不能写进简历”的准入标准

## 4. 任务拆分

- [S6-01：Eval Suite and Reporting Foundation](../agent-tasks/s6-task-01-eval-suite-and-reporting-foundation.md)
- [S6-02：Capability Quantification Sets](../agent-tasks/s6-task-02-capability-quantification-sets.md)
- [S6-03：Stage 5 Benchmark and Resume Pack](../agent-tasks/s6-task-03-stage-5-benchmark-and-resume-pack.md)

## 5. 进入条件 / 依赖关系

- 必须先有 Stage 2 的最小回归基线，避免 Stage 6 变成纯文档工程
- 必须先有 Stage 3 的统一 context assembly 与 memory contract，否则无法稳定定义上下文和记忆指标
- 必须先有 Stage 5 的 bounded loop / terminal semantics / handoff 边界，否则 benchmark 只能测空壳
- Stage 6 依赖前面阶段的“已实现能力”，不负责新增新的自治形态

## 6. 不在本阶段范围内

- 为了凑简历数据而重写 Agent 主链路
- 新增 planner、swarm、多 Agent 编排或无边界自治
- 把 Workbench / Demo Surface 当成正式 benchmark 平台
- 编造线上业务指标、吞吐量或用户增长类数据

## 7. 阶段完成标准

- 至少三块能力形成固定样本集、原始记录、汇总结果和可复核证据四件套
- Stage 5 至少形成一套固定 benchmark 或 handoff 正反例证据，而不是只停留在单测语义
- 能明确区分“当前可写进简历的数据”和“当前只能作为内部基线的数据”
- 至少沉淀出 2 到 3 条可安全写入简历的量化句子，并能解释其样本来源、判定口径和边界条件

## 7.1 当前结论

- 以上四项已满足，Stage 6 可以视为完成
- 当前已经具备可安全写进简历的 Context / Memory / Recovery / Safety 四类量化结果
- Stage 5 benchmark 当前可作为工程边界证据写入简历，但还不适合包装成“多步任务完成率”类结果

## 8. 建议留存的证据

- 一份固定样本清单，包含 caseId、目标、条件和验收规则
- 一份原始结果表，保留 case 级记录，而不是只保留平均值
- 一份汇总结果表，明确指标口径和时间点
- 一份 baseline / diff 报告，支持改动前后对比
- 一组代表性 trace / approval / terminal payload 截图或接口输出
- 具体记录方式可参考 [Agent Evidence Playbook](../agent-evidence-playbook.md) 与 [Agent 项目量化操作手册](../agent-resume-quantification.md)
- 当前简历初稿可参考 [Agent Runtime 项目简历初稿](../agent-resume-project-draft.md)
