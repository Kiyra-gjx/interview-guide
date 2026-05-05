# stage-5-recovery-set

## 套件定位

- suiteId: `stage-5-recovery-set`
- capability: `任务恢复`
- stage: `Stage 5`
- suiteType: `quantification`

## 目标

这套固定样本用来验证 recovery 语义是否真的区分了“可以恢复”“应该收尾”“必须阻断重放”。

## 代码与测试入口

- 代码入口：
  - `app/src/main/java/interview/guide/modules/agent/service/AgentOrchestrator.java`
  - `app/src/main/java/interview/guide/modules/agent/service/AgentApprovalRuntimeService.java`
  - `app/src/main/java/interview/guide/modules/agent/service/AgentSessionService.java`
  - `app/src/main/java/interview/guide/modules/agent/service/AgentTraceService.java`
- 当前测试基线：
  - `app/src/test/java/interview/guide/modules/agent/service/AgentOrchestratorTest.java`
  - `app/src/test/java/interview/guide/modules/agent/service/AgentTraceServiceTest.java`

## 证据要求

- 必须保留 `expectedTerminalState / actualTerminalState`
- 必须显式记录 `wrongStateContinued / replayedSideEffect`
- 没有 trace 终态证据时，不允许宣称恢复正确率

## 当前基线

- 固定样本数：`9`
- 已归档报告：
  - `reports/stage-5-recovery-set-report.json`
  - `reports/stage-5-recovery-set-report.md`
  - `reports/stage-5-recovery-set-diff.md`
- 当前 baseline：`baselines/stage-5-recovery-set-baseline-2026-04-28.json`
