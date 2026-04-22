# Stage 4: Agent Workbench and Demo Surface

## 0. 阶段状态
- 阶段状态：未开始
- 已完成任务：无
- 当前任务：待 Stage 3 完成后启动
- 前置条件：Stage 3 完成

## 1. 阶段目标

把前面阶段已经具备的单 Agent 运行时与 interview domain 能力收口成可演示、可调试、可观察的产品表面。这一阶段属于完整单 Agent（Level A）的收口阶段，目标是把“能运行的单 Agent”变成“能被展示、调试、验证的完整单 Agent 体验”。

## 2. 本阶段必须解决的问题

- 当前系统缺少面向用户和开发者的统一 workbench 入口
- Trace、Memory、状态和失败原因缺少清晰的前端观察面
- 领域能力已经存在时，仍缺少可用于演示和验证的 demo surface
- 单 Agent 能力尚未形成完整产品闭环

## 3. 本阶段交付物

- Agent Workbench 页面或工作台形态
- 面向演示和调试的 demo surface
- 用户视角与调试视角分层展示结构
- 对 turn、trace、memory、状态、失败原因的前端可视化
- Level A 完整单 Agent 的可演示收口面

## 4. 任务拆分

- [S4-01：Agent Workbench UI](../agent-tasks/s4-task-01-agent-workbench-ui.md)
- [S4-02：Debuggable Demo Flow](../agent-tasks/s4-task-02-debuggable-demo-flow.md)

## 5. 进入条件 / 依赖关系

- 必须先完成 Stage 3，确保 domain tooling 与 context assembly 已可用
- 本阶段不负责新增高级多步执行能力，只消费 Stage 1-3 的成果做单 Agent 收口
- Stage 4 完成即达到 Level A 的完整单 Agent 形态
- Stage 5 是后续增强阶段，不是本阶段的进入前提

## 6. 不在本阶段范围内

- bounded multi-step loop
- step budget、time/token/tool 预算控制
- handoff、subagent、多 Agent 编排
- 任何把系统从单 Agent 推向更高阶自治的机制

## 7. 阶段完成标准

- 项目具备清晰可用的 Agent Workbench 与 demo surface
- 单 Agent 的执行、观测、调试、领域能力在一个产品表面内完成收口
- Stage 4 完成即可视为 Level A 完整单 Agent 达成
- 不依赖 Stage 5 的高级能力，也能完成最低可交付目标

## 8. 建议留存的证据

- 一条或多条固定 demo 路径，能稳定展示成功、降级或失败中的主要状态
- workbench 观察面完整性检查，例如是否能直接看到 turn、trace、memory、tool、approval、失败原因
- 用户视角与调试视角是否分层清楚，而不是把所有信息堆在同一界面
- 需要明确区分：Workbench 与 Demo Surface 是观察和展示载体，不是评测工具本身；真正的验证仍依赖 Eval / Regression / Benchmark
- 具体记录方式可参考 [Agent Evidence Playbook](../agent-evidence-playbook.md)
