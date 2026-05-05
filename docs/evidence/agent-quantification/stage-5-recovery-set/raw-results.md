# stage-5-recovery-set Raw Results

## 运行信息

- executedAt: `2026-04-28T21:11:13.529538500`
- runner: `./gradlew.bat :app:agentStage5RecoveryEval --rerun-tasks`
- reportPath: `reports/stage-5-recovery-set-report.json`
- diffPath: `reports/stage-5-recovery-set-diff.md`
- baselinePath: `baselines/stage-5-recovery-set-baseline-2026-04-28.json`

## case 级结果

| caseId | recoveryType | expectedTerminalState | actualTerminalState | wrongStateContinued | replayedSideEffect | passed | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RCV-01 | reject_pending_approval | DEGRADED | DEGRADED | false | false | true | 审批拒绝后直接降级收口 |
| RCV-02 | expire_stale_pending_approval | DEGRADED | DEGRADED | false | false | true | 过期审批先收尾，再启动新 turn |
| RCV-03 | replay_block_after_started_execution | DEGRADED | DEGRADED | false | false | true | 审批通过后执行状态不明确时阻断重放 |
| RCV-04 | recover_from_trace_terminal_reply | DEGRADED | DEGRADED | false | false | true | 快照场景优先使用 trace 终态 reply |
| RCV-05 | approval_resume_failure | DEGRADED | DEGRADED | false | false | true | 审批恢复前置准备失败 |
| RCV-06 | stale_turn_explicit_failure | FAILED | FAILED | false | false | true | 过期 turn 显式失败 |
| RCV-07 | budget_exhausted_terminal_trace | EXHAUSTED | EXHAUSTED | false | false | true | 预算耗尽后收尾并写入 dedicated trace |
| RCV-08 | reject_handoff_on_single_step | DEGRADED | DEGRADED | false | false | true | 单步路径下委派被显式拒绝 |
| RCV-09 | recover_handoff_success_without_degraded_terminal | SUCCESS | SUCCESS | false | false | true | 成功 handoff 不误写 degraded terminal |

## 说明

- `wrongStateContinued=true` 表示本该失败/收尾却继续跑
- `replayedSideEffect=true` 表示本该阻断重放却再次执行副作用
- `passed` 以终态语义、恢复边界和副作用控制共同判定
