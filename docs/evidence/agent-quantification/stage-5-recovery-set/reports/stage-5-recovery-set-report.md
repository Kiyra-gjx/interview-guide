# Stage 5 Recovery Set Report

- suite: stage-5-recovery-set
- generatedAt: 2026-04-28T21:11:58.194767500
- totalCases: 9
- passedCases: 9
- 恢复正确率: 100.0%
- wrongStateContinued 数量: 0
- replayedSideEffect 数量: 0
- recoveryType 覆盖数: 9

| Case | RecoveryType | Expected Terminal | Actual Terminal | Stop Reason | wrongStateContinued | replayedSideEffect | Passed | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| RCV-01 | reject_pending_approval | DEGRADED | DEGRADED | APPROVAL_REJECTED | false | false | true | 审批拒绝后直接降级收口 |
| RCV-02 | expire_stale_pending_approval | DEGRADED | DEGRADED | APPROVAL_EXPIRED | false | false | true | 过期审批先收尾，再启动新 turn |
| RCV-03 | replay_block_after_started_execution | DEGRADED | DEGRADED | APPROVAL_REPLAY_BLOCKED | false | false | true | 审批通过后执行状态不明确时阻断重放 |
| RCV-04 | recover_from_trace_terminal_reply | DEGRADED | DEGRADED | APPROVAL_REJECTED | false | false | true | 快照场景优先使用 trace 终态 reply |
| RCV-05 | approval_resume_failure | DEGRADED | DEGRADED | APPROVAL_RESUME_FAILED | false | false | true | 审批恢复前置准备失败 |
| RCV-06 | stale_turn_explicit_failure | FAILED | FAILED | TURN_EXPIRED | false | false | true | 过期 turn 显式失败 |
| RCV-07 | budget_exhausted_terminal_trace | EXHAUSTED | EXHAUSTED | STEP_BUDGET_EXHAUSTED | false | false | true | 预算耗尽后收尾并写入 dedicated trace |
| RCV-08 | reject_handoff_on_single_step | DEGRADED | DEGRADED | HANDOFF_NOT_ALLOWED | false | false | true | 单步路径下委派被显式拒绝 |
| RCV-09 | recover_handoff_success_without_degraded_terminal | SUCCESS | SUCCESS | DIRECT_REPLY | false | false | true | 成功 handoff 不误写 degraded terminal |
