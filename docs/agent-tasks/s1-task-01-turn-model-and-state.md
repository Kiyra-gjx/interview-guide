# S1-01：Turn 模型与状态语义

## 0. 任务状态

- 状态：已完成
- 执行约束：仅实现本任务文档范围内内容，不提前进入 S1-02 / S1-03
- 当前重点：先落 turn 实体、状态语义、message / trace turn 归属与最小回收字段铺垫
- 暂不处理：响应契约收口、Tool payload 分层、Session 资源校验

## 0.1 迁移备注

- 新增 `agent_turns` 表承载 turn 级状态、完成质量与 lease 字段
- 为兼容历史 session 数据，`agent_messages.turn_id` 与 `agent_step_traces.turn_id` 本次保持可空
- 新写入的 message / trace 已绑定 turn，旧数据可在后续阶段按需补齐

## 0.2 review 修复说明

- 已修复 `completeTurn()` 成功后被 `failTurn()` 反写终态的问题
- 已为 `completeTurn() / failTurn()` 增加终态保护，避免重写 `COMPLETED / FAILED / ABORTED`
- 已新增 Flyway，并将本任务涉及的 schema 变更切到显式迁移
- 已将 stale turn 的 completion 从静默 no-op 改为显式业务错误，避免 API 返回与持久化状态不一致
- 已将 Agent 迁移拆为 `V1__agent_base_schema.sql` 与 `V2__agent_turn_foundation.sql`，新库无需手工预建 Agent 表
- 已统一 `startTurn()` 与 `completeTurn() / failTurn()` 的锁顺序为 `session -> turn`，避免 stale turn 回收与旧请求终态写入并发时潜在死锁
- 已补 turn 终态写路径专项测试，覆盖 `completeTurn() / failTurn()` 的锁序保护

## 0.3 本轮复核结论

- S1-01 范围内的 turn 实体、状态语义、message / trace turn 归属与最小回收字段已完整落地
- 本轮未发现新的 S1-01 范围内正确性回归
- `AgentChatResponse` 边界收口、Tool payload 分层、Session 资源校验仍按任务边界留在 S1-02 / S1-03

## 1. 任务目标

为 Agent 模块建立稳定的 turn 级执行模型，把单轮执行状态从 session 中剥离出来。

## 2. 要解决的问题

- `session.status` 混入单轮执行语义
- 一次 chat 没有独立执行实体
- 降级回复与真正失败没有清晰区分
- `RUNNING` turn 缺少回收语义

## 3. 本任务范围

- 新增 `agent_turns`
- 新增 turn 级状态模型
- 引入 `completionMode`
- 为过期执行预留 heartbeat / lease 字段
- 调整 message / trace 对 turn 的归属

## 4. 主要改动点

- 新增 `interview.guide.modules.agent.model.AgentTurnEntity`
- 新增 turn 状态枚举
- 新增完成质量枚举
- 修改 `interview.guide.modules.agent.model.AgentSessionEntity`
- 修改 `interview.guide.modules.agent.model.AgentMessageEntity`
- 修改 `interview.guide.modules.agent.model.AgentStepTraceEntity`
- 新增 `interview.guide.modules.agent.repository.AgentTurnRepository`
- 调整 `interview.guide.modules.agent.service.AgentSessionService`
- 调整 `interview.guide.modules.agent.service.AgentTraceService`

## 5. 建议状态语义

- `turn.status`
  - `CREATED`
  - `RUNNING`
  - `COMPLETED`
  - `FAILED`
  - `ABORTED`
- `turn.completionMode`
  - `SUCCESS`
  - `DEGRADED`

## 6. 交付物

- `agent_turns` schema
- JPA entity / repository
- turn 级状态读写路径
- message / trace 新字段
- 迁移说明

## 7. 风险点

- 历史数据兼容
- 双写阶段的一致性
- `session` 与 `turn` 语义边界再次混乱

## 8. 完成标准

- 一次 chat 已有唯一 turn
- `session` 不再承担单轮执行状态语义
- direct / degraded / failed / aborted 语义可稳定表达
