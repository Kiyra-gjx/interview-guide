# Stage 5 Agent Benchmark Report

- suite: stage-5-benchmark
- generatedAt: 2026-04-28T19:48:21.247502700
- totalCases: 4
- passedCases: 4
- multiStep 场景数: 2
- 平均执行步数: 1.0
- 平均延迟: 862 ms
- 最大延迟: 3340 ms
- 预算耗尽样例数: 1
- 委派成功样例数: 1
- 委派拒绝样例数: 1
- replay blocked 样例数: 1
- stopReason 分布: {APPROVAL_REPLAY_BLOCKED=1, DIRECT_REPLY=1, HANDOFF_NOT_ALLOWED=1, STEP_BUDGET_EXHAUSTED=1}
- terminalState 分布: {DEGRADED=2, EXHAUSTED=1, SUCCESS=1}

| Case | MultiStep | Steps | Stop Reason | Budget Stop | Terminal | Recoverable | Passed | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| bounded_handoff_success | true | 2 | DIRECT_REPLY | NONE | SUCCESS | false | true | 多步只读委派后继续收口 |
| handoff_rejected_single_step | false | 1 | HANDOFF_NOT_ALLOWED | NONE | DEGRADED | false | true | 单步路径下委派被显式拒绝 |
| step_budget_exhausted | true | 1 | STEP_BUDGET_EXHAUSTED | STEP_BUDGET_EXHAUSTED | EXHAUSTED | false | true | 多步预算耗尽后停止 |
| approval_replay_blocked | false | 0 | APPROVAL_REPLAY_BLOCKED | NONE | DEGRADED | false | true | 审批恢复场景下阻断重复副作用执行 |
