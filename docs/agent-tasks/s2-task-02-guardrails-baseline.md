# S2-02：Guardrails Baseline

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 2 已完成任务，作为 Guardrails Baseline 已落地
- 前置依赖：S2-01 已完成

## 1. 任务目标

给单 Agent 运行时补上最小可用的 Guardrails 基线，让模型输出不再直接等于系统行为。

## 2. 要解决的问题

- 输入阶段缺少统一拦截点，异常请求容易直接进入执行主链路
- Tool 调用缺少最小风险约束，副作用动作与普通读取动作边界不清
- 输出阶段缺少兜底，空回答、异常结构或敏感字段泄漏无法稳定降级
- Guardrail 命中结果还不能稳定进入 trace 与调试视角

## 3. 本任务范围

- Input Guardrails
- Tool Guardrails
- Output Guardrails
- Guardrail 命中后的拒绝、降级与 trace 收口

## 4. 主要改动点

- Guardrail 抽象与匹配结果模型
- `interview.guide.modules.agent.service.AgentOrchestrator`
- Tool 调用入口与参数校验层
- trace / response 中的 guardrail 结果表达

## 5. 风险与边界

- 本任务不负责审批状态机与人工批准流程，那属于 S2-03
- Guardrail 要先做“最小可用”，不要一开始就把策略体系做成复杂规则引擎
- 不能为了安全把正常请求大面积误伤，阻断规则必须能被解释

## 6. 完成标准

- 输入、工具、输出至少各有一层可落地的基础 guardrail
- 命中 guardrail 后，系统会明确拒绝、降级或改走安全路径，而不是静默失败
- trace 能说明 guardrail 命中点、命中原因与最终结果
- 高风险行为在没有进入审批前不能自动直通执行

## 7. 验证要求

- 至少覆盖输入拦截、工具阻断、输出兜底三类测试场景
- guardrail 命中结果可从 trace 或调试面中直接读到
- 正常低风险请求不会被新增 guardrail 大面积误拦截
