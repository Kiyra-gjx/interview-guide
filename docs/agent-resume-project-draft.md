# Agent Runtime 项目简历初稿

## 1. 推荐项目名称

Agent Runtime Engineering Platform

备选中文名：

- 面试辅助 Agent 运行时治理平台
- Agent Harness 与可恢复运行时工程化实践
- 面向面试场景的 Agent Runtime 与证据评测系统

## 2. 推荐项目定位

这是一个偏 `Agent Runtime / Agent Harness / Agent Engineering` 的项目，不建议包装成普通 Java 后端 CRUD 项目。

更准确的定位是：

- 设计并实现面向面试辅助场景的 Agent 运行时链路
- 围绕上下文治理、结构化记忆、工具安全、审批恢复和多步边界建立可审计执行契约
- 用固定样本集、baseline / diff 和 case 级报告沉淀可复核量化证据

## 3. 简历项目描述初稿

面向面试辅助场景，设计并实现一套 Agent Runtime 工程化链路，覆盖上下文装配、结构化记忆、工具调用、审批挂起、trace 收尾、恢复阻断与受控多步执行。项目重点不是单纯接入大模型，而是把 Agent 主链路拆成可审计、可恢复、可回归的执行语义，并通过固定样本集持续验证运行边界。

## 4. 可直接使用的简历 bullet

- 设计 Agent Runtime 主链路，围绕 `DIRECT_REPLY / TOOL_CALL / WAITING_APPROVAL / DEGRADED / FAILED / EXHAUSTED / SUCCESS` 等关键终态建立执行契约，并通过 Stage 2 / Stage 3 / Stage 5 固定样本集沉淀 JSON、Markdown、baseline 与 diff 报告。
- 实现上下文装配与 budget 治理，在 `10` 组固定上下文配置中将平均上下文长度从 `347.3` 压到 `256.1`，平均压缩率 `16.09%`，最高压缩率 `63.53%`，关键 section 保留率 `100%`，未出现 `requestBroken`。
- 建立结构化 memory 写回与 follow-up 复用机制，围绕 phase 推进、facts 归一化、委派写回和多轮复用构建 `9` 组固定样本，将重复工具读取从 `3` 次降到 `0`，重复事实确认从 `2` 次降到 `0`。
- 设计审批恢复、trace 收尾和副作用重放阻断机制，在 `9` 组固定恢复场景中恢复正确率 `100%`，`wrongStateContinued=0`，`replayedSideEffect=0`，覆盖 `DEGRADED / FAILED / EXHAUSTED / SUCCESS` 四类恢复收口结果。
- 建立工具安全与运行治理样本集，在 `10` 组固定安全场景中验证高风险动作审批命中率 `100%`、审批拒绝后降级收口率 `100%`，未出现绕过审批直接执行，并覆盖 replay blocked 副作用保护。

## 5. 如果简历篇幅只能放 3 条

优先保留下面 3 条：

1. Agent Runtime 主链路与可审计执行契约
2. 结构化 memory 带来的重复调用下降
3. 审批恢复与副作用边界

上下文治理可以放到项目描述里，工具安全可以在面试追问时展开。

## 6. 面试追问时的解释口径

如果被问“这些 100% 是什么意思”，回答要明确边界：

- 这是固定离线样本集上的回归结果，不是线上成功率
- 每组结果都有 sample-set、raw-results、summary、report、baseline 和 diff
- 这些数字证明的是运行时契约、恢复语义和治理边界可复核，不证明模型泛化能力

如果被问“为什么不写多步完成率”，回答：

- 当前 Stage 5 benchmark 只有 `4` 组，适合证明 handoff、预算耗尽和 replay blocked 等工程边界
- 还没有定义完整任务级 completion rate，所以不会写成“多步任务完成率 100%”

如果被问“Memory 的下降是怎么来的”，回答：

- `Before` 是固定 no-reuse 对照路径，表示 follow-up 阶段重复读同一资源或重复确认同一事实
- `After` 是当前 memory 命中路径，验证已经写回的 facts / nextFocus 能支撑后续直接回答
- 当前 `9` 组样本中 `repeatedToolCalls 3 -> 0`，`repeatedFactChecks 2 -> 0`

## 7. 当前不要写进简历的句子

- 不要写“线上恢复成功率 100%”
- 不要写“Agent 整体成功率 100%”
- 不要写“多步任务完成率 100%”
- 不要写“平均延迟 xx ms”
- 不要写“所有多轮对话都不会重复调用工具”
- 不要把 `guardrailHitCases=6` 写成“安全性提升 60%”

## 8. 证据回链

- Stage 6 总结：`docs/agent-stages/stage-6-evidence-benchmark-and-resume-quantification.md`
- 量化手册：`docs/agent-resume-quantification.md`
- 证据总目录：`docs/evidence/agent-quantification/README.md`
- Context 证据：`docs/evidence/agent-quantification/stage-3-context-set/summary.md`
- Memory 证据：`docs/evidence/agent-quantification/stage-3-memory-set/summary.md`
- Recovery 证据：`docs/evidence/agent-quantification/stage-5-recovery-set/summary.md`
- Safety 证据：`docs/evidence/agent-quantification/stage-2-safety-set/summary.md`
- Stage 5 benchmark 证据：`docs/evidence/agent-quantification/stage-5-benchmark/summary.md`
