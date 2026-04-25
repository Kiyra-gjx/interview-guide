# S1-03：Tool 契约与 Session 资源校验

## 0. 任务状态

- 状态：已完成
- 当前重点：已完成 Tool payload 分层、session 资源校验与 ToolRegistry 重名保护；当前版本在此基础上进一步统一了 Tool 结果的消费者视图

## 0.1 已完成项

- `AgentToolResult` 已拆为 `summary / answerPayload / debugPayload / confirmedFacts`
- Stage 1 已先完成回答层与调试层分离；当前版本的回答 Prompt 在此基础上改为消费统一回答视图 `promptPayload()`，仍不让 Tool debug 字段进入回答模型
- `ResumeProfileTool` 与 `KnowledgeBaseSearchTool` 已按业务结果与调试结果分层输出
- Tool trace 已保留完整分层快照；当前版本又在此基础上暴露统一 `toolOutput` 结构，回答链路与排障链路职责分开
- `createSession()` 已在落库前校验 `resumeId` 存在性，并对 `knowledgeBaseIds` 做去重与存在性校验
- `ToolRegistry` 已新增 duplicate fail-fast，重名 Tool 会在启动阶段直接失败
- 已补 Agent 后端专项测试，覆盖 Prompt 隔离、trace 分层、session 资源校验与 ToolRegistry 重名保护

## 0.2 本轮复核结论

- S1-03 范围内“debug 不再污染回答 Prompt”目标已完成
- S1-03 范围内“session 非法资源无法落库”目标已完成
- S1-03 范围内“ToolRegistry 重名启动失败”目标已完成
- 当前未发现新的 S1-03 范围内正确性回归

## 1. 任务目标

收紧 Tool 输入输出边界，并把 session 创建阶段的资源合法性前置校验做掉。

## 2. 要解决的问题

- `AgentToolResult.output` 混入 debug 载荷
- 回答 Prompt 会再次消费这些 debug 字段
- session 创建允许非法 `resumeId` / `knowledgeBaseIds`
- ToolRegistry 重名会静默覆盖

## 3. 本任务范围

- `AgentToolResult` 拆层
- Prompt 不消费 `debugPayload`，当前通过统一回答视图消费 Tool 结果
- Tool 输出字段收紧
- session 创建资源校验
- ToolRegistry duplicate fail-fast

## 4. 主要改动点

- `interview.guide.modules.agent.support.AgentToolResult`
- `interview.guide.modules.agent.service.AgentPromptService`
- `interview.guide.modules.agent.service.AgentSessionService`
- `interview.guide.modules.agent.service.AgentOrchestrator`
- `interview.guide.modules.agent.tool.ResumeProfileTool`
- `interview.guide.modules.agent.tool.KnowledgeBaseSearchTool`
- `interview.guide.modules.agent.tool.ToolRegistry`

## 5. 建议输出分层

- `summary`
- `answerPayload`
- `debugPayload`
- `confirmedFacts`

## 6. 交付物

- 新版 `AgentToolResult`
- Tool payload 重构
- session 资源校验
- Tool 重名保护

## 7. 风险点

- Tool 输出字段改动导致前端或 Prompt 不兼容
- 资源校验依赖其他模块查询能力

## 8. 完成标准

- 回答 Prompt 不再消费 debug 字段
- session 无法落入非法资源
- ToolRegistry 重名会启动失败
