# stage-5-benchmark Raw Results

## 运行信息

- executedAt: `2026-04-28T19:56:41.322558`
- runner: `./gradlew.bat :app:agentStage5Benchmark --rerun-tasks`
- reportPath: `reports/stage-5-benchmark-report.json`
- diffPath: `reports/stage-5-benchmark-diff.md`
- baselinePath: `baselines/stage-5-benchmark-baseline-2026-04-28.json`

## case 级结果

| caseId | actualMultiStepEnabled | actualExecutedSteps | actualStopReason | actualBudgetStopReason | actualTerminalState | actualRecoverable | passed | latencyMs | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| bounded_handoff_success | true | 2 | DIRECT_REPLY | NONE | SUCCESS | false | true | 2622 | 多步只读委派后继续收口 |
| handoff_rejected_single_step | false | 1 | HANDOFF_NOT_ALLOWED | NONE | DEGRADED | false | true | 32 | 单步路径下委派被显式拒绝 |
| step_budget_exhausted | true | 1 | STEP_BUDGET_EXHAUSTED | STEP_BUDGET_EXHAUSTED | EXHAUSTED | false | true | 45 | 多步预算耗尽后停止 |
| approval_replay_blocked | false | 0 | APPROVAL_REPLAY_BLOCKED | NONE | DEGRADED | false | true | 32 | 审批恢复场景下阻断重复副作用执行 |

## 说明

- 这里的 `latencyMs` 来自离线测试运行时间，只适合作为本地回归噪声参考，不适合写进简历
- `passed=true` 表示当前 case 的 expected / actual 行为完全匹配，不表示“业务任务完成率”
