# S5-01：Bounded Loop and Budget Control

## 0. 任务状态

- 状态：未开始
- 当前定位：Stage 5 的多步执行入口任务
- 前置依赖：Stage 4 完成

## 1. 任务目标

在 Level A 已稳定的前提下，引入有明确上限的多步执行能力，而不是让系统无限循环。

## 2. 要解决的问题

- 单步执行对复杂任务的分解能力有限
- 没有步数、时间或 token 预算时，多步执行容易失控
- 多步执行如果缺少 trace 和预算语义，很难排障和评估收益

## 3. 本任务范围

- bounded loop
- `maxSteps`
- time budget
- token budget
- 预算耗尽后的终态语义

## 4. 主要改动点

- `interview.guide.modules.agent.service.AgentOrchestrator`
- run config / budget 模型
- step 级 Trace / Metrics 扩展
- 多步执行状态表达

## 5. 风险与边界

- 本任务不引入 handoff / subagent，那属于 S5-03
- 不以“看起来更智能”为目标牺牲边界可控性
- 预算应先简单明确，避免一开始就做复杂优化器

## 6. 完成标准

- 多步执行不会无限 loop
- 步数、时间或 token 超限时有明确且可解释的终态
- trace 能完整说明每一步做了什么、为何停止

## 7. 验证要求

- 至少覆盖步数预算、时间预算或 token 预算耗尽场景
- 多步执行主链路和异常链路都能在 trace 中被解释
- 同类输入在相同预算下的行为基本稳定
