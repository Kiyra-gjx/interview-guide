# Stage5BenchmarkSet

## 样本信息

- suiteId: `stage-5-benchmark`
- capability: `Stage 5 多步能力基线`
- suiteType: `benchmark`

## 固定样本清单

| caseId | scenarioType | intent | setup | verifier | notes |
| --- | --- | --- | --- | --- | --- |
| BENCH-01 | bounded_handoff_success | 验证多步模式下只读委派后能继续主链路并成功收口 | `multiStepEnabled=true`，允许 3 步，首步 handoff，次步 direct reply | `multiStep=true`、`executedSteps=2`、`stopReason=DIRECT_REPLY`、`terminalState=SUCCESS` | 正例 |
| BENCH-02 | handoff_rejected_single_step | 验证单步路径下委派请求会被本地边界拒绝 | 默认单步，请求 handoff | `multiStep=false`、`executedSteps=1`、`stopReason=HANDOFF_NOT_ALLOWED`、`terminalState=DEGRADED` | 反例 |
| BENCH-03 | step_budget_exhausted | 验证 step budget 耗尽时进入 exhausted 终态 | `multiStepEnabled=true`，`maxSteps=1`，首步先执行工具 | `stopReason=STEP_BUDGET_EXHAUSTED`、`budgetStopReason=STEP_BUDGET_EXHAUSTED`、`terminalState=EXHAUSTED` | 预算边界 |
| BENCH-04 | approval_replay_blocked | 验证审批恢复状态不明确时阻断副作用重放 | approval 已通过，但 trace 显示执行已开始 | `executedSteps=0`、`stopReason=APPROVAL_REPLAY_BLOCKED`、`terminalState=DEGRADED` | 副作用保护 |

## 控制变量

- model: 不依赖真实模型，使用固定 decision / handoff result / tool result
- runtimeConfig:
  - BENCH-01: `multiStepEnabled=true,maxSteps=3,maxDurationMillis=15000,maxEstimatedModelTokens=4000`
  - BENCH-02: 默认单步
  - BENCH-03: `multiStepEnabled=true,maxSteps=1,maxDurationMillis=15000,maxEstimatedModelTokens=4000`
  - BENCH-04: approval recovery path，不走普通 chat 多步
- approvalMode:
  - BENCH-01 / BENCH-02 / BENCH-03: `not involved`
  - BENCH-04: `approved`
- baselineReference: `baselines/stage-5-benchmark-baseline-2026-04-28.json`
