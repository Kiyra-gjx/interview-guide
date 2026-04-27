# Stage 2: Safe and Observable Runtime

## 0. 阶段状态
- 阶段状态：已完成
- 已完成任务：S2-01、S2-02、S2-03、S2-04
- 后续承接任务：Stage 3 与 Stage 4 已完成，S5-01、S5-02 也已完成，下一步建议推进 [S5-03：Handoff and Subagent Boundary](../agent-tasks/s5-task-03-handoff-and-subagent-boundary.md)
- 前置条件：Stage 1 已完成

## 1. 阶段目标

在 Stage 1 已经稳定 turn 执行边界的前提下，把系统补齐为“可观测、可解释、可约束、可回归验证”的安全运行时，为后续 domain tooling 和产品化展示提供受控底座。

## 2. 本阶段必须解决的问题

- Trace 还不够面向排查和解释
- Memory 还不够稳定地服务跨 turn 上下文
- 缺少明确的 Guardrails 与 Approval 边界
- 缺少可重复的离线 Eval 与回归比较机制

## 3. 本阶段交付物

- 更稳定的 turn 级 Trace / Memory / Metrics
- Input / Tool / Output 三层 Guardrails
- 高风险动作审批模型与策略
- 离线 Eval 与回归样例
- 支撑后续阶段的安全与可观测运行时基线

## 4. 任务拆分

- [S2-01：Trace、Memory 与 Metrics 收敛](../agent-tasks/s2-task-01-trace-memory-metrics.md)
- [S2-02：Guardrails Baseline](../agent-tasks/s2-task-02-guardrails-baseline.md)
- [S2-03：Runtime Approval and Policy](../agent-tasks/s2-task-03-runtime-approval-and-policy.md)
- [S2-04：Eval and Regression](../agent-tasks/s2-task-04-eval-and-regression.md)

## 5. 进入条件 / 依赖关系

- 必须先完成 Stage 1
- 当前真实进度：S2-01、S2-02、S2-03、S2-04 已完成，Stage 3 与 Stage 4 也已完成，当前主线正在推进 S5-01 的受控多步执行骨架
- Stage 3、Stage 4、Stage 5 都依赖本阶段提供的安全与可观测能力

## 6. 不在本阶段范围内

- interview domain 的专用工具扩展与 context assembly
- Agent Workbench 与 demo surface
- bounded loop、handoff、subagent 等多步高级能力
- 以产品演示为目标的前端收口工作

## 7. 阶段完成标准

- 能解释一轮执行为何成功、降级或失败
- 高风险动作不能无条件直通执行
- 可以用离线样例比较改动前后的指标变化
- 后续阶段可以直接复用本阶段提供的安全与观测基线

## 8. 建议留存的证据

- 一组固定回归样例，覆盖成功、降级、被拒绝、失败等主要运行结果
- guardrail 命中分布、approval 状态分布、主要失败原因分布
- 改动前后对比结果，例如回归通过率、拒绝准确性、降级稳定性、trace 完整度
- 至少一组可回看的运行工件，例如 trace、report、关键日志或快照
- 具体记录方式可参考 [Agent Evidence Playbook](../agent-evidence-playbook.md)
