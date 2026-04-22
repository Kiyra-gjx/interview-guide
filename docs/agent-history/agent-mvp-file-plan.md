# Agent 文件改动映射

## 1. 文档角色

这份文档的目标不是讲路线，而是回答：

- 某个阶段 / 某个任务落地时，大概率会改哪些文件
- 这些文件分别承担什么职责
- 适合如何按任务切给 AI 或人类开发者执行

如果要看目标和阶段，请回到：

- [总体目标与执行计划](./agent-roadmap.md)

## 2. 顶层模块映射

### 2.1 后端

- `interview.guide.modules.agent.AgentController`
- `interview.guide.modules.agent.service.AgentOrchestrator`
- `interview.guide.modules.agent.service.AgentSessionService`
- `interview.guide.modules.agent.service.AgentTraceService`
- `interview.guide.modules.agent.service.AgentMemoryService`
- `interview.guide.modules.agent.service.AgentPromptService`
- `interview.guide.modules.agent.tool.*`
- `interview.guide.modules.agent.repository.*`
- `interview.guide.modules.agent.model.*`

### 2.2 Prompt

- `app/src/main/resources/prompts/agent-system.st`
- `app/src/main/resources/prompts/agent-user.st`
- `app/src/main/resources/prompts/agent-answer-user.st`

### 2.3 前端

- `frontend/src/api/agent.ts`
- `frontend/src/types/agent.ts`
- `frontend/src/pages/AgentCoachPage.tsx`

## 3. Stage 1 文件改动映射

### 3.1 S1-01：Turn 模型与状态语义

文档：

- [S1-01：Turn 模型与状态语义](./agent-tasks/s1-task-01-turn-model-and-state.md)

大概率改动：

- `interview.guide.modules.agent.model.AgentSessionEntity`
- 新增 `interview.guide.modules.agent.model.AgentTurnEntity`
- 新增 `interview.guide.modules.agent.model.AgentTurnStatus`
- 新增 `interview.guide.modules.agent.model.AgentCompletionMode`
- `interview.guide.modules.agent.model.AgentMessageEntity`
- `interview.guide.modules.agent.model.AgentStepTraceEntity`
- `interview.guide.modules.agent.repository.AgentSessionRepository`
- 新增 `interview.guide.modules.agent.repository.AgentTurnRepository`
- `interview.guide.modules.agent.service.AgentSessionService`
- `interview.guide.modules.agent.service.AgentTraceService`
- 迁移 SQL / schema 变更

### 3.2 S1-02：响应契约与 API 收口

文档：

- [S1-02：响应契约与 API 收口](./agent-tasks/s1-task-02-response-contract-and-api.md)

大概率改动：

- `interview.guide.modules.agent.model.AgentChatResponse`
- 新增 `interview.guide.modules.agent.model.AgentTurnDTO`
- `interview.guide.modules.agent.AgentController`
- `interview.guide.modules.agent.service.AgentOrchestrator`
- `interview.guide.modules.agent.service.AgentSessionService`
- `interview.guide.modules.agent.service.AgentTraceService`
- `frontend/src/api/agent.ts`
- `frontend/src/types/agent.ts`
- `frontend/src/pages/AgentCoachPage.tsx`

### 3.3 S1-03：Tool 契约与 Session 资源校验

文档：

- [S1-03：Tool 契约与 Session 资源校验](./agent-tasks/s1-task-03-tool-payload-and-session-validation.md)

大概率改动：

- `interview.guide.modules.agent.support.AgentToolResult`
- `interview.guide.modules.agent.service.AgentPromptService`
- `interview.guide.modules.agent.service.AgentSessionService`
- `interview.guide.modules.agent.service.AgentOrchestrator`
- `interview.guide.modules.agent.tool.ResumeProfileTool`
- `interview.guide.modules.agent.tool.KnowledgeBaseSearchTool`
- `interview.guide.modules.agent.tool.ToolRegistry`
- 可能新增资源校验用 Service / Query

## 4. Stage 2 文件改动映射

### 4.1 S2-01：Trace、Memory 与 Metrics 收敛

- `interview.guide.modules.agent.service.AgentTraceService`
- `interview.guide.modules.agent.service.AgentMemoryService`
- `interview.guide.modules.agent.service.AgentOrchestrator`
- `interview.guide.modules.agent.model.AgentTraceDTO`
- 新增指标采集相关组件
- 前端 trace / memory 展示文件

### 4.2 S2-02：Guardrails 与 Approval

- 新增 `interview.guide.modules.agent.guardrail.*`
- 可能新增 `ApprovalService`、`ApprovalEntity`
- `interview.guide.modules.agent.service.AgentOrchestrator`
- Tool 抽象层
- Prompt 层

### 4.3 S2-03：Eval 与回归验证

- `app/src/test/java/interview/guide/modules/agent/...`
- 可能新增 `scripts/`、`docs/` 下 eval 数据说明
- 可能新增离线评测入口

## 5. Stage 3 文件改动映射

### 5.1 S3-01：新 Tool 与上下文组装

- 新增 `interview.guide.modules.agent.tool.InterviewHistoryTool`
- 新增面试报告 / 复盘相关 Tool
- `interview.guide.modules.agent.service.AgentOrchestrator`
- `interview.guide.modules.agent.support.AgentToolContext`
- Prompt 组装相关文件

### 5.2 S3-02：Agent Workbench 前端

- `frontend/src/pages/AgentCoachPage.tsx`
- 新增 `frontend/src/components/agent/*`
- `frontend/src/api/agent.ts`
- `frontend/src/types/agent.ts`

## 6. Stage 4 文件改动映射

### 6.1 S4-01：受控多步 Loop 与预算管理

- `interview.guide.modules.agent.service.AgentOrchestrator`
- 新增 step budget / run config 相关模型
- Trace / Turn / Metrics 相关文件
- Prompt 组装相关文件

### 6.2 S4-02：Handoff 与 Subagent 边界

- 可能新增 handoff 抽象
- 可能新增 agent profile / policy 配置
- 不建议在 Stage 4 之前提前引入

## 7. 如何把任务切给 AI

建议按“单任务、单边界、单一主要写集”切分：

- 数据模型与迁移：优先单独一个任务
- API 契约与 DTO：单独一个任务
- Tool 契约与 Prompt：单独一个任务
- 前端消费契约：单独一个任务
- 测试补齐：单独一个任务

不要把“改 schema + 改 orchestrator + 改前端 + 改 prompt + 补测试”一次全塞给一个执行任务。
