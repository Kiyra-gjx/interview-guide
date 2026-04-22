# S1-02：响应契约与 API 收口

## 0. 任务状态

- 状态：已完成
- 当前重点：已完成 turn 边界 chat 回包、错误语义收口与前端契约同步

## 0.1 已完成项

- `AgentChatResponse` 已新增 `turnId`、`turnStatus`、`completionMode`
- `/chat` 已改为返回 `messagesDelta`
- `/chat` 的 `traceSteps` 与 `messagesDelta` 已按 `turnId` 读取本轮增量，不再读取整段 session 快照
- 已补专项测试覆盖 turn 边界回包，并补齐“回包组装失败但 turn 已完成时不再补 failTurn”保护
- `AGENT_TURN_CONFLICT` 已映射为真实 HTTP `409`
- Agent session / turn not-found 已统一为明确业务错误语义，`/trace` 对不存在 session 不再静默返回空列表
- 已新增 `AGENT_TURN_NOT_FOUND`
- 已补异常处理器与 trace not-found 专项测试
- 前端类型已同步 `turnId / turnStatus / completionMode / messagesDelta`
- AgentCoach 页面已改为追加消费 turn 增量，不再在发送后依赖 `/chat` 返回整段 session 快照
- 已通过 Agent 后端测试与前端构建校验
- 已修正前端 `request.ts` 中过时的响应契约注释
- `getTurnMessages()` 与 `getTurnTrace()` 已在读取前显式校验 turn 存在性，避免未来单独复用时退化为空列表
- 已补 controller / MockMvc 级回归测试，覆盖 `/chat -> 409` 与 `/trace -> 404`

## 0.2 本轮复核结论

- S1-02 中“turn 边界 chat 回包”这一子目标已完成
- S1-02 中“`409` 冲突语义”和“统一 not-found 语义”这两项后端目标已完成
- S1-02 中“前端契约同步”已完成
- 当前未发现新的 S1-02 范围内正确性回归

## 1. 任务目标

把 Agent API 从“整段 session 快照回包”改成“本轮执行结果回包”，并统一错误语义。

## 2. 要解决的问题

- `AgentChatResponse` 仍然是 session 级快照
- `/session`、`/memory`、`/trace` 契约不一致
- 并发冲突缺少明确 API 语义

## 3. 本任务范围

- 改 `AgentChatResponse`
- 增加 `turnId`、`turnStatus`、`completionMode`
- `messages` 改为本轮 delta 或明确命名
- `/chat` 冲突返回 `409`
- 统一不存在资源的错误语义

## 4. 主要改动点

- `interview.guide.modules.agent.model.AgentChatResponse`
- `interview.guide.modules.agent.AgentController`
- `interview.guide.modules.agent.service.AgentOrchestrator`
- `interview.guide.modules.agent.service.AgentSessionService`
- `interview.guide.modules.agent.service.AgentTraceService`
- `frontend/src/api/agent.ts`
- `frontend/src/types/agent.ts`
- `frontend/src/pages/AgentCoachPage.tsx`

## 5. 交付物

- turn 边界的 chat 回包
- 统一错误码与错误语义
- 冲突语义文档化
- 前端契约同步

## 6. 建议回包字段

- `sessionId`
- `turnId`
- `turnStatus`
- `completionMode`
- `reply`
- `memory`
- `traceSteps`
- `messagesDelta`
- 可选：`sessionMessages`

## 7. 风险点

- 前后端契约不同步
- 历史接口兼容性
- 错误语义调整后前端展示异常

## 8. 完成标准

- `/chat` 回包明确是“本轮结果”
- 并发冲突能稳定返回明确错误
- 不存在 session / turn 的接口行为一致
