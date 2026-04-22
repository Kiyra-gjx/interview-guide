# Stage 1: Execution Foundation

## 0. 阶段状态
- 阶段状态：已完成
- 已完成任务：S1-01、S1-02、S1-03
- 当前任务：无
- 后续建议：进入 Stage 2

## 1. 阶段目标

把 `modules.agent` 从“会话级执行”收敛为“单 turn 执行单元”，先把最基础、最关键的执行边界稳定下来，为后续可观测性、工具扩展和更高阶能力提供可靠底座。

## 2. 本阶段必须解决的问题

- 一次 chat 缺少独立的 turn 执行单元，执行边界不清晰
- `session.status` 混入单轮执行语义，状态职责耦合
- 响应读取 session 全量快照，容易被并发或后续状态污染
- Tool 结果没有做回答层与调试层分离
- Session 创建阶段缺少资源校验与基础防护

## 3. 本阶段交付物

- `agent_turns` 级别的 turn 状态模型
- `turn.status` 与 `completionMode` 语义
- 以 turn 为边界的 `AgentChatResponse`
- `answerPayload` 与 `debugPayload` 分层
- Session 创建时的资源校验机制
- 并发冲突与过期回收的基础语义

## 4. 任务拆分

- [S1-01：Turn 模型与状态语义](../agent-tasks/s1-task-01-turn-model-and-state.md)
- [S1-02：响应契约与 API 收口](../agent-tasks/s1-task-02-response-contract-and-api.md)
- [S1-03：Tool 契约与 Session 资源校验](../agent-tasks/s1-task-03-tool-payload-and-session-validation.md)

## 5. 进入条件 / 依赖关系

- 这是基础阶段，无前置 stage 依赖
- 阶段内执行顺序保持为：S1-01 -> S1-02 -> S1-03
- Stage 2 及之后的所有阶段都依赖本阶段完成

## 6. 不在本阶段范围内

- 可观测性、Guardrails、Approval、Eval
- interview domain 的专用 tooling 与上下文组装
- workbench、demo surface、演示型前端
- bounded loop、handoff、subagent 等高级执行形态
- 多 Agent 或 planner-worker 体系

## 7. 阶段完成标准

- 一次 chat 明确对应一个独立 turn
- `session` 不再承担单轮执行状态表达
- 响应 payload 与 session 全量快照彻底分离
- Tool 调试信息不再污染回答主链路
- Session 创建阶段具备基础资源校验能力
