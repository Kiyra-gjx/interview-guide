# Agent Capability Map

## 使用方式

本文档按能力轴回答三件事：

- 当前能力到哪一步
- 该能力完成时应达到什么标准
- 对应推进它的 stage/task 是什么

## Execution Model

- 当前状态：已建立 turn 级执行边界，Stage 1 已完成
- 完成标准：一次 chat 对应一个独立 turn，状态语义、响应契约与 session / turn 职责稳定
- 对应 stage/task：
  - [Stage 1：Execution Foundation](./agent-stages/stage-1-turn-foundation.md)
  - [S1-01](./agent-tasks/s1-task-01-turn-model-and-state.md)
  - [S1-02](./agent-tasks/s1-task-02-response-contract-and-api.md)
  - [S1-03](./agent-tasks/s1-task-03-tool-payload-and-session-validation.md)

## Tooling

- 当前状态：已有基础 Tool 契约，但 interview domain 的工具覆盖、上下文依赖与输出统一性仍不足
- 完成标准：工具输入、执行、输出边界清晰，可扩展且能稳定服务单 Agent 主链路
- 对应 stage/task：
  - [Stage 1：Execution Foundation](./agent-stages/stage-1-turn-foundation.md)
  - [Stage 3：Domain Tooling and Context Assembly](./agent-stages/stage-3-domain-tooling-context.md)
  - [S1-03](./agent-tasks/s1-task-03-tool-payload-and-session-validation.md)
  - [S3-01](./agent-tasks/s3-task-01-interview-context-tools.md)
  - [S3-03](./agent-tasks/s3-task-03-tool-output-normalization.md)

## Memory

- 当前状态：Stage 2 已完成 S2-01，memory before / after 与快照稳定性已有基础
- 完成标准：memory 能稳定表达跨轮上下文变化，并能被 trace、回放和工作台解释
- 对应 stage/task：
  - [Stage 2：Safe and Observable Runtime](./agent-stages/stage-2-observability-guardrails.md)
  - [S2-01](./agent-tasks/s2-task-01-trace-memory-metrics.md)
  - [S3-02](./agent-tasks/s3-task-02-context-assembly-policy.md)

## Observability

- 当前状态：Stage 2 已完成，S2-01、S2-02、S2-03、S2-04 已建立 trace、memory、metrics、guardrail、approval 与 eval 基线
- 完成标准：能清楚解释一轮执行为何成功、降级、被拒绝或失败，并具备稳定指标入口
- 对应 stage/task：
  - [Stage 2：Safe and Observable Runtime](./agent-stages/stage-2-observability-guardrails.md)
  - [Stage 4：Agent Workbench and Demo Surface](./agent-stages/stage-4-agent-workbench-demo-surface.md)
  - [S2-01](./agent-tasks/s2-task-01-trace-memory-metrics.md)
  - [S4-01](./agent-tasks/s4-task-01-agent-workbench-ui.md)
  - [S4-02](./agent-tasks/s4-task-02-debuggable-demo-flow.md)

## Guardrails

- 当前状态：S2-02、S2-03 已完成，输入 / Tool / 输出三层 guardrail 与运行时审批语义已落地
- 完成标准：输入、工具、输出至少具备基础 guardrail，高风险动作不能无条件直通，审批状态可解释
- 对应 stage/task：
  - [Stage 2：Safe and Observable Runtime](./agent-stages/stage-2-observability-guardrails.md)
  - [S2-02](./agent-tasks/s2-task-02-guardrails-baseline.md)
  - [S2-03](./agent-tasks/s2-task-03-runtime-approval-and-policy.md)

## Eval

- 当前状态：已完成第一版最小基线，具备固定离线样例、统一运行入口、报告留档与前后对比能力
- 完成标准：能用离线样例比较改动前后的关键指标与行为变化，结果可被留档与复查
- 对应 stage/task：
  - [Stage 2：Safe and Observable Runtime](./agent-stages/stage-2-observability-guardrails.md)
  - [S2-04](./agent-tasks/s2-task-04-eval-and-regression.md)

## Workbench

- 当前状态：已有局部展示能力，但还不是完整 Agent Workbench
- 完成标准：具备面向调试、观测、演示的统一工作台入口，并能支撑完整单 Agent demo
- 对应 stage/task：
  - [Stage 4：Agent Workbench and Demo Surface](./agent-stages/stage-4-agent-workbench-demo-surface.md)
  - [S4-01](./agent-tasks/s4-task-01-agent-workbench-ui.md)
  - [S4-02](./agent-tasks/s4-task-02-debuggable-demo-flow.md)

## Controlled Loop

- 当前状态：未开始，且不属于当前最低完成线
- 完成标准：多步执行具备预算、停止条件、失败语义与 handoff 边界，并仍处于可观测、可约束范围内
- 对应 stage/task：
  - [Stage 5：Bounded Multi-Step Agent](./agent-stages/stage-5-bounded-multi-step-agent.md)
  - [S5-01](./agent-tasks/s5-task-01-bounded-loop-and-budget-control.md)
  - [S5-02](./agent-tasks/s5-task-02-stop-conditions-and-failure-semantics.md)
  - [S5-03](./agent-tasks/s5-task-03-handoff-and-subagent-boundary.md)

## 总结

- “它是什么”：见 [Agent Overview](./agent-overview.md)
- “它还差什么”：当前主要缺口集中在 Domain Tooling、Workbench
- “它接下来做什么”：Stage 2 已完成，当前推荐进入 [S3-01：Interview Context Tools](./agent-tasks/s3-task-01-interview-context-tools.md)
