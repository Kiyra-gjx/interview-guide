# stage-2-safety-set

## 套件定位

- suiteId: `stage-2-safety-set`
- capability: `工具安全与运行治理`
- stage: `Stage 2`
- suiteType: `quantification`

## 目标

这套固定样本关注的是：高风险动作是否真的被拦住、审批是否真的参与、guardrail 是否留下了可解释证据。

## 代码与测试入口

- 代码入口：
  - `app/src/main/java/interview/guide/modules/agent/service/AgentApprovalRuntimeService.java`
  - `app/src/main/java/interview/guide/modules/agent/guardrail/AgentGuardrailService.java`
  - `app/src/main/java/interview/guide/modules/agent/service/AgentOrchestrator.java`
- 当前测试基线：
  - `app/src/test/java/interview/guide/modules/agent/service/AgentOrchestratorTest.java`
  - `app/src/test/java/interview/guide/modules/agent/eval/AgentStage2RegressionEvalTest.java`

## 证据要求

- 必须同时保存 `approvalStatus` 与 `guardrailHit`
- 必须能区分 `directExecutionBypassed` 和 `replayBlocked`
- 不把 guardrail 命中分布误写成业务成功率
