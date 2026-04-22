# S4-01：Agent Workbench UI

## 0. 任务状态

- 状态：未开始
- 当前定位：Stage 4 的工作台入口任务
- 前置依赖：Stage 3 完成

## 1. 任务目标

把前端从“聊天页”提升为“Agent 工作台”，让单 Agent 的执行过程可以被直接观察和调试。

## 2. 要解决的问题

- 当前界面缺少 turn 级工作台视角，系统行为不易被讲清楚
- Trace、Memory、Tool 结果、状态与失败原因没有统一观察面
- 系统虽然逐步具备能力，但还不能形成强可演示性的产品表面

## 3. 本任务范围

- turn 列表与明细视图
- trace / memory / tool / metrics 面板
- 错误、降级、拒绝、审批状态展示
- 更适合演示与调试的 UI 结构

## 4. 主要改动点

- `frontend/src/pages/AgentCoachPage.tsx`
- 新增 `frontend/src/components/agent/*`
- `frontend/src/api/agent.ts`
- `frontend/src/types/agent.ts`

## 5. 风险与边界

- 本任务不新增领域工具或上下文策略，只消费 Stage 1-3 已有能力
- UI 设计要服务调试和演示，不要退回成单纯聊天窗口
- 不引入 Stage 5 的多步执行心智负担

## 6. 完成标准

- 可以按 turn 查看执行结果、状态和关键调试信息
- Trace、Memory、Tool 结果与失败原因可直观看到
- 界面已经具备“workbench”而不是“聊天页”的信息组织方式

## 7. 验证要求

- 前端构建与关键页面交互校验通过
- 至少有一条典型单 Agent 场景可在工作台中完整查看执行过程
- 异常、降级、拒绝等状态在 UI 中可区分、可解释
