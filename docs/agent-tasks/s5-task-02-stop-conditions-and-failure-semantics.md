# S5-02：Stop Conditions and Failure Semantics

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 5 的终态语义任务
- 前置依赖：S5-01 已完成并提供稳定的 `execution` / budget 基线

## 0.1 当前进展

- 已引入 `AgentTerminalState` 与 `AgentTerminalSemantics`，把 `completionMode + stopReason` 映射成统一的 `terminalState / recoverable / recoveryHint`
- 已把 `execution` 摘要、trace DTO、审批恢复读取结果与前端类型统一扩展为终态语义契约
- 已细化 `APPROVAL_REJECTED / APPROVAL_EXPIRED / APPROVAL_REPLAY_BLOCKED / APPROVAL_RESUME_FAILED / TOOL_POST_PROCESSING_FAILED / UNHANDLED_ERROR` 等 stop reason
- 已把预算耗尽、审批拒绝、审批过期、恢复阻断与未处理异常区分为受控终止、降级收口或失败终态，而不是混成单一失败路径
- 已让 metrics、workbench trace explorer 与 demo narrative 统一消费同一套终态字段

## 1. 任务目标

为多步执行建立清晰的停止条件与失败语义，让系统知道何时继续、何时结束、何时回收。

## 2. 要解决的问题

- 多步执行如果没有明确停止条件，很难区分“该结束”还是“还没做完”
- 失败、降级、超时、预算耗尽等终态容易混在一起
- 没有统一终态语义时，前端、trace、metrics 和恢复逻辑会互相冲突

## 3. 本任务范围

- stop condition 定义
- success / degraded / failed / exhausted 等终态语义
- 重试、放弃、回收与终止规则
- 与 trace、metrics、UI 对齐的状态表达

## 4. 主要改动点

- 多步执行状态机或状态模型
- `interview.guide.modules.agent.service.AgentOrchestrator`
- trace / metrics / response 状态字段
- 失败回收与恢复相关逻辑

## 5. 风险与边界

- 本任务不讨论 handoff / subagent 的边界，那属于 S5-03
- 终态定义必须能覆盖主流路径，但不应为了完整性制造过多罕见状态
- 恢复语义要可验证，不能把“失败后怎么办”留给人工猜测

## 6. 完成标准

- 多步执行的停止条件有统一定义
- 成功、降级、失败、预算耗尽等终态彼此可区分
- trace、metrics、前端与恢复逻辑对终态语义保持一致

## 7. 验证要求

- 至少覆盖自然完成、预算耗尽、异常失败三类终态测试
- 相同终态在不同观察面上有一致表达
- 异常终态能解释为何停止、是否可恢复、如何回收
