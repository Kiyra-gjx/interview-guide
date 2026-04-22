# Agent 交付检查清单

## 1. 文档角色

这份文档不写路线图，不写大段方案。

它只负责一件事：

**在实施过程中，快速判断每个阶段和任务是否真的做完。**

如果要看目标和阶段，请回到：

- [总体目标与执行计划](./agent-roadmap.md)

如果要看实现任务，请去：

- `agent-stages/`
- `agent-tasks/`

## 2. 全局检查项

### 2.1 建模边界

- `session`、`turn`、`message`、`trace`、`memory` 边界是否清晰
- 是否还存在“session 表达单轮执行状态”的遗留语义

### 2.2 Tool 契约

- Tool 输入是否校验
- Tool 输出是否拆成 `answerPayload` 与 `debugPayload`
- 是否仍然让 debug 字段进入回答 Prompt

### 2.3 API 契约

- 不存在资源时，错误语义是否统一
- 并发冲突时，是否有明确 `409` 语义
- 回包是否明确为“本轮结果”

### 2.4 风险控制

- 是否有最小可用的 Guardrails
- 高风险工具是否有 Approval / HITL
- 是否有 stale `RUNNING` turn 回收机制

### 2.5 可观测性

- 是否能看到 turn、trace、memory、tool 调用摘要
- 是否有关键指标和回归验证方式

## 3. Stage 1 检查项

对应文档：

- [Stage 1：Turn 基础设施与执行边界](./agent-stages/stage-1-turn-foundation.md)

### 3.1 Turn 建模

- 已有 `agent_turns`
- `turn.status` 与 `completionMode` 已分离
- `session` 不再承担单轮执行状态语义

### 3.2 响应与接口

- `AgentChatResponse` 已包含 `turnId`
- `traceSteps` / `messagesDelta` 明确是本轮数据
- 不存在 session / turn 时错误语义一致

### 3.3 Tool 与资源校验

- `AgentToolResult` 已拆层
- session 创建时已做资源校验
- ToolRegistry 重名会 fail fast

### 3.4 冲突与回收

- 同一 session 并发 chat 有明确处理
- stale `RUNNING` turn 有回收策略

## 4. Stage 2 检查项

对应文档：

- [Stage 2：可观测性、Guardrails 与 Eval](./agent-stages/stage-2-observability-guardrails.md)

### 4.1 Trace / Memory / Metrics

- 每轮都有稳定 trace
- Memory before / after 可追踪
- 关键指标可观测

### 4.2 Guardrails / Approval

- 输入、输出、工具调用至少有一层 guardrail
- 高风险工具不会无审批直接执行

### 4.3 Eval

- 至少有一套离线回归样例
- 能比较改动前后成功率 / 降级率 / 延迟

## 5. Stage 3 检查项

对应文档：

- [Stage 3：Tool 扩展与 Agent 工作台](./agent-stages/stage-3-tools-and-workbench.md)

### 5.1 Tool 价值

- Tool 集合更贴近面试 / 求职场景
- Tool 之间的上下文拼装有统一策略

### 5.2 前端工作台

- 前端不只是聊天页
- 能查看 turn、trace、memory、tool 结果、状态与错误原因

## 6. Stage 4 检查项

对应文档：

- [Stage 4：受控多步执行](./agent-stages/stage-4-controlled-agent-loop.md)

### 6.1 多步执行

- 有明确 step budget / time budget / token budget
- 没有无限 loop 风险

### 6.2 Handoff / Subagent

- 引入原因明确
- 边界清晰
- 不是为了“看起来高级”而堆复杂度

## 7. 发布前总检查

- 本次改动是否对应了明确的阶段和任务文档
- 是否更新了相关 checklist
- 是否补了最少必要测试
- 是否明确了不在范围内的内容
- 是否避免与其他文档职责冲突
