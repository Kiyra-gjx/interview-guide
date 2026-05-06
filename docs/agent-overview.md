# Agent Overview

## 它是什么

Interview Agent 的目标，不是做一个“接了大模型的聊天页”，而是把当前项目收口成一个可解释、可约束、可演示、可继续扩展的工程化 Agent 系统。

这个系统的最低要求不是“能聊”，而是：

- 一次请求对应一个清晰的 turn 执行单元
- Tool、Prompt、Memory、Trace 各自职责清楚
- 风险动作不会被模型输出直接直通执行
- 改动后能用观测和评测判断是否变好或变坏
- 能通过工作台和演示流把系统行为讲清楚

## 它不是什么

当前路线不追求以下形态：

- 不是先做一个概念上很完整、但工程边界模糊的多 Agent 系统
- 不是为了“更像 Agent”而提前引入 uncontrolled loop、subagent swarm 或复杂 planner
- 不是把所有能力都堆进单个聊天页面里

## 完成边界

### Level A：完整单 Agent

Level A 是当前项目的最低完成线。

达到 Level A 时，系统应具备：

- 稳定的单 turn 执行模型
- 清晰的工具输入输出与上下文组装边界
- 可解释的 Trace、Memory 与 Metrics
- 可落地的 Guardrails 与 Approval 策略
- 可复用的 Eval / Regression 入口
- 可演示、可调试的 Agent Workbench 与 Demo Surface

顶层定义上，**Stage 4 完成即达到完整单 Agent（Level A）**。

### Level B：受控多步 Agent

Level B 不是最低完成线，而是在 Level A 之上的增强层。

达到 Level B 时，系统才开始具备：

- bounded multi-step loop
- 明确的预算、停止条件与失败语义
- 受控的 handoff / subagent 边界

顶层定义上，**Stage 5 对应 Level B**。

## 当前整体状态

当前真实进度如下：

- Stage 1 已完成
- Stage 2 已完成
- Stage 3 已完成
- Stage 4 已完成
- Stage 5 主体实现已完成
- Stage 6 进行中
- S2-01 已完成
- S2-02 已完成
- S2-03 已完成
- S2-04 已完成
- S3-01 已完成
- S3-02 已完成
- S3-03 已完成
- S4-01 已完成
- S4-02 已完成
- S5-01 已完成
- S5-02 已完成
- S5-03 已完成
- 当前 Stage 6 的首要任务是补齐固定样例、报告与可复核证据，把已有 runtime 能力沉淀成 benchmark 与简历量化结果

这意味着项目已经建立了单 Agent 的执行基础、安全可观测运行时基线、首批 interview domain 工具能力、统一 context assembly 基线、统一的 tool output 消费视图、turn 级 Agent Workbench，以及可复现的 demo flow 收口，已经达到完整单 Agent（Level A）。Stage 5 的多步预算骨架、终态语义收口，以及首版 handoff / subagent 边界治理也已经落地：系统已支持受控只读委派，但仍保持显式开启、默认单步、不扩散子执行体的保守策略。当前进入 Stage 6，重点不再是新增运行时主逻辑，而是把 Stage 2 / Stage 3 / Stage 5 的已有信号收口成固定样本、benchmark、baseline / diff 和简历可用的量化证据。

## 它还差什么

从顶层看，当前最低完成线已经达到；Stage 5 的正式任务也已经实现完毕，但仍有两类后续工作需要继续补强：

- 多步固定样例与 benchmark 证据，用来验证 Stage 5 机制收益
- handoff / subagent 的正反例沉淀，用来证明受控委派的适用场景与边界收益

除此之外，还需要一层明确的证据工程收口：

- 把上下文、记忆、恢复、安全、多步 benchmark 统一成固定样本集和标准报告
- 把“可以内部观察”的信号转成“可以对外复核”的量化结果

## 文档分工

- 本文档回答“它是什么”和“完成线在哪里”
- [Agent Capability Map](./agent-capability-map.md) 回答“它还差什么能力”
- [Agent Roadmap](./agent-roadmap.md) 回答“它接下来做什么”
- `agent-stages/` 按阶段回答“这一段要解决什么”
- `agent-tasks/` 按任务回答“这一项具体怎么落”
- `agent-history/` 保存历史方案与旧版规划，供复盘使用

## 历史资料

以下文档不再作为当前主线执行入口，仅用于追溯历史设计：

- [Agent Turn Refactor Plan](./agent-history/agent-turn-refactor-plan.md)
- [Agent MVP File Plan](./agent-history/agent-mvp-file-plan.md)
- [Agent MVP Review Checklist](./agent-history/agent-mvp-review-checklist.md)
