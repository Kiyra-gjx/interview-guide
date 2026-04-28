# Stage 5: Bounded Multi-Step Agent

## 0. 阶段状态
- 阶段状态：主体实现已完成
- 已完成任务：S5-01、S5-02、S5-03
- 当前任务：无新的正式实现任务；固定样例、handoff 正反例与 benchmark 证据收口转入 Stage 6
- 前置条件：Stage 4 完成

## 1. 阶段目标

在已经完成 Level A 单 Agent 收口之后，再引入有明确边界的多步执行能力，包括 bounded loop、handoff、subagent 等。这是更高级的增强阶段，用来提升复杂任务处理能力，不是系统的最低完成线。

## 2. 本阶段必须解决的问题

- 单步或单 turn Agent 对更复杂任务的分解能力有限
- 多步执行如果没有预算、停止条件和边界定义，容易带来失控风险
- handoff 与 subagent 如果缺少约束，复杂度会快速上升且收益不稳定

## 3. 本阶段交付物

- 受 step budget 约束的 bounded multi-step loop
- time / token / tool 调用预算机制
- 明确的 stop condition
- handoff / subagent 的引入边界与适用场景
- 受控的高级 Agent 增强能力层

## 4. 任务拆分

- [S5-01：Bounded Loop and Budget Control](../agent-tasks/s5-task-01-bounded-loop-and-budget-control.md)
- [S5-02：Stop Conditions and Failure Semantics](../agent-tasks/s5-task-02-stop-conditions-and-failure-semantics.md)
- [S5-03：Handoff and Subagent Boundary](../agent-tasks/s5-task-03-handoff-and-subagent-boundary.md)

## 5. 进入条件 / 依赖关系

- 必须先完成 Stage 4，因为 Stage 5 不是最低完成线，而是 Level A 之后的增强
- 依赖 Stage 1-4 已经提供稳定执行边界、安全运行时、领域能力和 demo/workbench 收口
- 只有在复杂任务收益明确时，才应进入本阶段

## 5.1 当前已落地范围

- 已支持通过 `runtimeConfig.multiStepEnabled=true` 显式开启 bounded multi-step loop，默认仍保持单步执行
- 已支持 `maxSteps`、`maxDurationMillis`、`maxEstimatedModelTokens` 三类预算
- 已支持预算耗尽后的降级收口、`execution` 执行摘要，以及 `bounded_loop` trace
- 已支持统一的 `terminalState / stopReason / recoverable / recoveryHint` 终态契约，并对齐审批恢复、trace、metrics 与工作台叙事
- 已支持首版 handoff / subagent 边界治理：只读委派决策字段、单 turn 单次委派限制、委派结果写回 memory/trace、委派拒绝与失败的独立 stop reason，以及前端对 internal handoff marker 的独立展示
- 这些能力当前已覆盖 S5-01、S5-02、S5-03 的主体实现；Stage 5 是否真正收口还取决于固定样例与 benchmark 证据是否充分

## 6. 不在本阶段范围内

- Stage 4 之前的单 Agent 最低可交付能力定义
- 用高级自治能力替代基础产品打磨
- 无边界的自主循环或不受控的多 Agent 扩展

## 7. 阶段完成标准

- 多步执行具备明确预算、停止条件和失败边界
- handoff / subagent 的引入有清晰收益与约束，不是为了“看起来更高级”
- Stage 5 提供的是在 Level A 之上的增强能力，而非最低交付要求

## 8. 建议留存的证据

- 多步任务固定样例，覆盖自然完成、预算耗尽、失败终止、人工拒绝等主要终态
- 预算内完成率、平均步数、停止原因分布、失败原因分布
- handoff / subagent 的正反例样本，证明哪些任务值得委派、哪些不值得
- 恢复与边界正确性证据，例如中断恢复后是否误用旧状态、是否越过预算继续执行
- 具体记录方式可参考 [Agent Evidence Playbook](../agent-evidence-playbook.md)
