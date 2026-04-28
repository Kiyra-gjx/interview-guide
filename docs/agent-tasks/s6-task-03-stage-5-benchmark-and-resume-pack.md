# S6-03：Stage 5 Benchmark and Resume Pack

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 6 的多步能力证据收口任务
- 前置依赖：S6-01、S6-02 已完成基础规范

## 0.1 当前进展

- Stage 5 已有 bounded loop、budget、terminal semantics、handoff 正反例的实现与单测
- 已补齐 `stage-5-benchmark` 的固定样本清单、原始结果、summary、baseline / diff 与 `resume-pack.md`
- 已明确当前可写与不可写的 Stage 5 简历表述边界

## 1. 任务目标

把 Stage 5 从“多步能力已经实现”推进到“多步能力的收益和边界已有固定 benchmark 证明”，并最终沉淀成可安全写入简历的量化结果。

## 2. 要解决的问题

- 多步预算、stop reason、handoff 正反例目前主要停留在单测层
- 没有固定 benchmark 时，很难证明 handoff / subagent 不是“看起来更高级”的装饰
- 没有简历准入规则时，容易把内部基线写成对外结果

## 3. 本任务范围

- Stage 5 固定任务集
- handoff / subagent 正反例
- 预算内完成率、平均步数、stop reason 分布、terminal state 分布
- resume-safe 指标白名单

## 4. 主要改动点

- Stage 5 eval / benchmark 入口
- handoff 正反例样本
- 汇总报告
- 简历准入清单

## 5. 风险与边界

- 不能把离线 mock latency 当成正式性能指标
- 不能把固定场景分布写成“Agent 整体成功率”
- 不能用缺少原始记录的 100% 指标包装项目

## 6. 完成标准

- 至少有一套固定 Stage 5 benchmark
- 至少有一组 handoff 正例和反例，能解释为什么允许或拒绝委派
- 至少形成 2 到 3 条可追溯到样本集和报告的简历句子
- 当前状态：以上三项已满足，`S6-03` 可以视为完成

## 7. 验证要求

- benchmark 结果必须能回到原始报告和 case 级结果
- 每条准备写进简历的数据都要满足：固定样本集、统一口径、原始记录、可复核证据
- 如果某项数据达不到这四条，宁可留在内部文档，也不要提前写进简历
- 当前状态：Stage 5 benchmark 的当前基线已经具备上述四项
