# S2-01：Trace、Memory 与 Metrics 收敛

## 0. 任务状态

- 状态：已完成
- 当前重点：已完成 turn 级 trace 收敛、memory before / after 固化与核心 metrics 埋点

## 0.1 已完成项

- `AgentTraceDTO` 已补充 `memoryBefore / memoryAfter`，trace 可稳定回放本步上下文变化
- `AgentStepTraceEntity` 已新增 `memory_before_json / memory_after_json`，memory before / after 已固化入库
- `AgentTraceService` 已统一 direct reply / rejected decision / tool success / tool failure 的 debug 输出结构
- `AgentMemorySnapshot` 已做不可变收口，跨轮 memory 快照更稳定
- 已新增 `AgentMetricsService`，落地 `turn.total / turn.outcome / turn.reclaimed / tool.execution / turn.latency`
- Agent 后端已接入 actuator metrics 暴露，核心指标可通过 metrics 端点观测，且默认仅允许本机访问
- Agent 前端 trace 视图已展示 `Memory Before / Memory After`
- 已将 `turn.latency` 调整为覆盖 `startTurn()` 到 `buildChatResponse()` 的端到端采样边界
- 已拆分 tool 执行失败与 tool 后处理失败，`agent.tool.execution` 不再被后处理异常污染
- 已为 trace / memory 序列化失败补显式日志与 unavailable snapshot，观测数据损坏不再静默显示为“暂无”
- 已通过 Agent 后端测试与前端构建校验

## 0.2 本轮复核结论

- S2-01 范围内“每轮执行都有稳定 trace”目标已完成
- S2-01 范围内“memory before / after 可读可比”目标已完成
- S2-01 范围内“至少一组核心指标可观测”目标已完成
- 当前未发现新的 S2-01 范围内正确性回归

## 1. 任务目标

让 turn 执行结果可解释、可追踪、可度量。

## 2. 要解决的问题

- 成功、降级、失败等不同执行分支的 trace 结构不统一
- Memory 快照缺少 before / after 视角，难以解释上下文如何变化
- 缺少能反映 turn 结果与 tool 执行质量的稳定指标
- 调试信息虽然存在，但还不够稳定地服务排障与回放

## 3. 本任务范围

- turn 级 trace 收敛
- memory before / after 固化
- 指标埋点
- debug 展示结构统一

## 4. 主要改动点

- `interview.guide.modules.agent.service.AgentTraceService`
- `interview.guide.modules.agent.service.AgentMemoryService`
- `interview.guide.modules.agent.service.AgentOrchestrator`
- trace / memory DTO
- 指标采集组件

## 5. 风险与边界

- 本任务不引入 Guardrails、Approval 或 Eval 策略
- 指标命名与 trace 字段一旦暴露，应尽量保持稳定，避免后续观测口径反复变动
- 序列化或快照异常应显式降级，而不是静默吞掉

## 6. 建议指标

- turn 总数
- 成功率
- 降级率
- 失败率
- `RUNNING` 超时回收数
- Tool 调用成功率
- P95 延迟

## 7. 完成标准

- 每轮执行都有稳定 trace
- memory before / after 可读可比
- 至少有一组核心指标可观测

## 8. 验证要求

- Agent 后端专项测试覆盖 trace、session、orchestrator 主链路
- trace 视图能正确展示 `memoryBefore / memoryAfter`
- tool 执行失败与后处理失败能够在 trace 与 metrics 中被区分
