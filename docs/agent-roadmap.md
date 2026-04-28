# Agent Roadmap

## 当前进度快照

- 当前整体状态：Stage 4 已完成，Stage 5 进行中
- 已完成阶段：Stage 1、Stage 2、Stage 3、Stage 4
- 当前已完成任务：S3-01、S3-02、S3-03、S4-01、S4-02、S5-01、S5-02、S5-03
- 当前建议推进任务：补齐 Stage 5 固定样例、handoff 正反例与 benchmark 证据
- 当前最低完成线定义：完成 Stage 4 即达到完整单 Agent（Level A）
- 更高一级目标：Stage 5 是受控多步 Agent（Level B），不是最低完成线

## 路线图职责

本文件只回答执行顺序问题：

- 现在走到哪一步
- 每个阶段何时可以进入
- 每个阶段何时可以退出
- 当前推荐先做什么

项目定义请看 [Agent Overview](./agent-overview.md)，能力缺口请看 [Agent Capability Map](./agent-capability-map.md)。

## 阶段顺序

### Stage 1：Execution Foundation

- 定位：建立单轮执行单元、状态语义和响应契约
- 进入条件：无，作为起点阶段
- 退出条件：
  - 一次 chat 对应一个独立 turn
  - `session` 不再承载单轮执行状态
  - 本轮响应与 session 全量快照分离
  - Tool debug 不再污染回答
  - session 创建阶段完成资源校验
- 当前状态：已完成
- 参考文档：[Stage 1：Execution Foundation](./agent-stages/stage-1-turn-foundation.md)

### Stage 2：Safe and Observable Runtime

- 定位：让单 Agent 具备可解释、可控制、可回归验证的基础运行时能力
- 进入条件：
  - Stage 1 已完成
  - Turn 边界与响应契约已经稳定
- 退出条件：
  - 能解释一轮执行为什么成功、降级、被拒绝或失败
  - 高风险动作不能无条件直通
  - 能用离线样例比较改动前后的指标变化
- 当前状态：已完成
- 当前完成情况：S2-01、S2-02、S2-03、S2-04 已完成，已具备安全可观测运行时与最小 Eval / Regression 基线
- 参考文档：[Stage 2：Safe and Observable Runtime](./agent-stages/stage-2-observability-guardrails.md)

### Stage 3：Domain Tooling and Context Assembly

- 定位：补齐 interview domain 相关工具能力与上下文组装策略
- 进入条件：
  - Stage 2 已完成
  - Trace、Guardrails、Eval 已形成稳定基线
- 退出条件：
  - 具备可复用的 interview domain 工具扩展模式
  - 上下文组装策略稳定、可解释、可控
  - Tool 输出结构足够统一，能稳定服务回答与调试链路
- 当前状态：已完成
- 当前完成情况：S3-01、S3-02、S3-03 已完成，已形成首批 interview context tools、统一 context assembly 策略，以及面向 Prompt / Memory / Trace / API 的统一 tool output 消费视图
- 参考文档：[Stage 3：Domain Tooling and Context Assembly](./agent-stages/stage-3-domain-tooling-context.md)

### Stage 4：Agent Workbench and Demo Surface

- 定位：把前面阶段能力收口成完整单 Agent 的工作台与演示流
- 进入条件：
  - Stage 3 已完成
  - 单轮执行、观测、风控、评测与领域工具链都已具备稳定基线
- 退出条件：
  - 项目已能作为完整单 Agent 工程对外说明
  - 单 Agent 执行边界、工具治理、memory、观测、风控、评测闭环完整
  - 达到 Level A
- 当前状态：已完成
- 当前完成情况：S4-01 已完成真正的 Agent Workbench 与 turn 级只读聚合；S4-02 已完成 demo flow、演示说明与 Level A 收口
- 参考文档：[Stage 4：Agent Workbench and Demo Surface](./agent-stages/stage-4-agent-workbench-demo-surface.md)

### Stage 5：Bounded Multi-Step Agent

- 定位：在 Level A 之上扩展受控多步执行能力
- 进入条件：
  - Stage 4 已完成
  - 单 Agent 基线已经稳定并可评测
- 退出条件：
  - 多步 loop 有明确预算、停止条件和失败回收语义
  - handoff / subagent 仍处于受控边界内
  - 达到 Level B
- 当前状态：进行中
- 当前完成情况：S5-01 已完成，已落地显式 `runtimeConfig`、`maxSteps / maxDurationMillis / maxEstimatedModelTokens` 预算、`execution` 执行摘要，以及预算耗尽时的 `bounded_loop` trace / metrics 收口
- 当前完成情况：S5-02 已完成，已引入 `terminalState / stopReason / recoverable / recoveryHint` 统一终态契约，并对齐审批恢复、trace、metrics、workbench 与 demo narrative
- 当前完成情况：S5-03 已完成首版 handoff / subagent 边界治理，已落地受控只读委派、单 turn 单次委派限制、委派结果回主链路的 memory/trace 收口，以及前端对 internal handoff marker 的独立展示语义
- 说明：Stage 5 是增强目标，不是最低完成线；当前不再缺正式任务实现，后续重点转为固定样例、benchmark 与收益证据补强
- 参考文档：[Stage 5：Bounded Multi-Step Agent](./agent-stages/stage-5-bounded-multi-step-agent.md)

## 当前推荐推进任务

当前建议推进 Stage 5 固定样例、handoff 正反例与 benchmark 证据补强。

推荐原因：

- S5-03 已经把 handoff / subagent 的首版受控边界落地，当前更需要证明它是否真正带来收益，而不是继续扩展自治形态
- Stage 5 是否值得继续增强，取决于多步固定样例、handoff 正反例与 benchmark 能否给出清晰证据
- 当前最容易失真的不是代码实现，而是“看起来更高级”但缺少证据支撑的能力叙事

## 评测与证据原则

- 开发时要边做边留证据，不要等所有 Stage 做完后再补数据
- `Workbench / Demo Surface` 负责观察、调试和展示，不等于测试工具
- `Eval / Regression / Benchmark` 负责验证机制收益与回归稳定性，才属于测试与评测主链路
- 每个 Stage 结束时都应至少留一组“改动前 / 改动后”的对照结果
- 比起单纯追求 QPS 或吞吐量，更重要的是机制是否带来了可验证收益，例如更少重复调用、更稳定恢复、更清晰失败语义
- 具体怎么留证据、怎么做对照、怎么记结果，见 [Agent Evidence Playbook](./agent-evidence-playbook.md)

## 历史资料

以下文档用于追溯旧方案，不再作为当前主线执行入口：

- [Agent Turn Refactor Plan](./agent-history/agent-turn-refactor-plan.md)
- [Agent MVP File Plan](./agent-history/agent-mvp-file-plan.md)
- [Agent MVP Review Checklist](./agent-history/agent-mvp-review-checklist.md)
