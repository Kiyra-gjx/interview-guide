# Agent Turn Refactor 专项方案

## 0. 当前状态

- 项目当前阶段：Stage 2 进行中
- 已完成任务：S1-01（含终态锁顺序 review 修复）、S1-02、S1-03、S2-01
- 当前任务：建议进入 S2-02
- 未开始任务：S2-02、S2-03
- 文档定位：当前优化 / 修 bug 的持续维护文档，每次改完 bug、实现完功能都要同步更新

## 0.1 当前要继续解决的问题

- 缺少 Input Guardrails，非法参数、缺上下文、越权资源还没有统一前置拦截
- 缺少 Tool Guardrails，高风险工具还不能做到白名单、参数校验、审批后执行
- 缺少 Output Guardrails，空回答、泄漏字段、格式异常还没有统一兜底
- trace 还没有完整记录 guardrail / approval 的判定结果，执行链路仍缺少一层风险解释
- 离线 eval 与回归验证还没有建立，改完后还不能稳定比较前后效果

## 0.2 最近已完成的收口

- Stage 1：已补 turn 终态保护，避免 `completeTurn()` 成功后再被 `failTurn()` 反写终态
- Stage 1：已将 stale turn 回收失败从静默 no-op 收口为显式业务错误，避免 API 结果与持久化状态不一致
- Stage 1：已补 Flyway 显式迁移，并拆为 `V1__agent_base_schema.sql` 与 `V2__agent_turn_foundation.sql`
- Stage 1：已统一 `startTurn()` 与 `completeTurn() / failTurn()` 的锁顺序为 `session -> turn`，并补专项测试覆盖锁序保护
- Stage 1：已完成 `/chat` 的 turn 边界回包，`messagesDelta` / `traceSteps` 只读取本轮增量
- Stage 1：已统一 `/trace`、`getTurnMessages()`、`getTurnTrace()` 的 not-found 语义，不再对不存在资源静默返回空结果
- Stage 1：已补 `409 AGENT_TURN_CONFLICT` 冲突语义，避免并发执行时 API 表意模糊
- Stage 1：已将 `AgentToolResult` 拆为 `summary / answerPayload / debugPayload / confirmedFacts`
- Stage 1：已将 `ResumeProfileTool` 与 `KnowledgeBaseSearchTool` 收口为业务结果与调试结果分层输出
- Stage 1：已在 `createSession()` 落库前完成 `resumeId` 与 `knowledgeBaseIds` 资源校验
- Stage 1：已为 `ToolRegistry` 增加 duplicate fail-fast，重名 Tool 启动即失败
- Stage 1：已将前端契约同步到 `messagesDelta` 与 `traceSteps` 的 turn 增量语义
- Stage 2 / S2-01：`AgentTraceDTO` 已补充 `memoryBefore / memoryAfter`，trace 可稳定回放本步上下文变化
- Stage 2 / S2-01：`AgentStepTraceEntity` 已新增 `memory_before_json / memory_after_json`，memory before / after 已固化入库
- Stage 2 / S2-01：`AgentTraceService` 已统一 direct reply / rejected decision / tool success / tool failure 的 debug 输出结构
- Stage 2 / S2-01：`AgentMemorySnapshot` 已做不可变收口，跨轮 memory 快照更稳定
- Stage 2 / S2-01：已新增 `AgentMetricsService`，落地 `turn.total / turn.outcome / turn.reclaimed / tool.execution / turn.latency`
- Stage 2 / S2-01：后端已接入 actuator metrics 暴露，核心指标可通过 metrics 端点观测，且默认仅允许本机访问
- Stage 2 / S2-01：前端 trace 视图已展示 `Memory Before / Memory After`
- Stage 2 / S2-01：已将 `turn.latency` 调整为覆盖 `startTurn()` 到 `buildChatResponse()` 的端到端采样边界
- Stage 2 / S2-01：已拆分 tool 执行失败与 tool 后处理失败，`agent.tool.execution` 不再被后处理异常污染
- Stage 2 / S2-01：已为 trace / memory 序列化失败补显式日志与 unavailable snapshot，观测数据损坏不再静默显示为“暂无”

## 1. 文档角色

这份文档就是“当前要解决什么问题、最近修到了哪里、下一步做什么”的专项主文档，不是归档文档。

它主要维护 4 类信息：

- 当前阶段和当前任务
- 当前仍待解决的问题
- 最近已经完成的功能 / bugfix 收口
- 下一步推荐推进顺序

详细设计、改动点和完成标准仍分别维护在阶段文档和任务文档里；但每次改完 bug、实现完功能、收完 review，都要把结果同步回本文，避免这里还是旧状态。

## 2. 本次优化主线

`turn-refactor` 不只是“把 turn 表加出来”。

它代表的是一条持续收口的主线：

- 先把一次 chat 建模成独立 turn，修正执行边界
- 再把回包、trace、memory、tool 输出全部收口到 turn 语义
- 再在这个边界上补可观测性、guardrails、approval、eval

所以当前虽然已经进入 Stage 2，但 `turn-refactor` 这份文档仍然要继续维护，因为后续能力都是沿着这条执行边界继续补齐的。

## 3. 当前范围

当前这份专项文档覆盖的内容包括：

- Stage 1：Turn 基础设施、响应契约、Tool 契约、session 资源校验
- Stage 2：Trace / Memory / Metrics 收敛、Guardrails / Approval、Eval / 回归验证

当前明确不在范围内：

- 多 Agent 编排
- planner-worker 大框架迁移
- 自动执行高风险副作用工具
- 复杂 handoff / subagent
- 通用 autonomous agent platform 包装

## 4. 对应阶段与任务

- [S1-01：Turn 模型与状态语义](./agent-tasks/s1-task-01-turn-model-and-state.md) - 已完成
- [S1-02：响应契约与 API 收口](./agent-tasks/s1-task-02-response-contract-and-api.md) - 已完成
- [S1-03：Tool 契约与 Session 资源校验](./agent-tasks/s1-task-03-tool-payload-and-session-validation.md) - 已完成
- [S2-01：Trace、Memory 与 Metrics 收敛](./agent-tasks/s2-task-01-trace-memory-metrics.md) - 已完成
- [S2-02：Guardrails 与 Approval](./agent-tasks/s2-task-02-guardrails-approval.md) - 当前推荐下一步
- [S2-03：Eval 与回归验证](./agent-tasks/s2-task-03-evals-regression.md) - 待开始

阶段文档：

- [Stage 1：Turn 基础设施与执行边界](./agent-stages/stage-1-turn-foundation.md)
- [Stage 2：可观测性、Guardrails 与 Eval](./agent-stages/stage-2-observability-guardrails.md)

## 5. 当前建议交付顺序

1. 先完成 S2-02，把 Input / Tool / Output Guardrails 和高风险工具 Approval 落地
2. 再完成 S2-03，补齐离线 eval 与回归验证样例
3. 每完成一个任务或一轮 review 修复，都同步更新本文的“当前状态 / 当前问题 / 最近收口”

## 6. 文档更新规则

- 每次改完 bug，要更新“当前状态”和“最近已完成的收口”
- 每次实现完功能，要把对应能力从“当前要继续解决的问题”移到“最近已完成的收口”
- 每次 review 收口后，要把新增修复点同步到本文，而不是只留在对话里
- 本文保持“当前视角”，不要把已经过时的阶段判断、优先级和问题描述继续留在这里
