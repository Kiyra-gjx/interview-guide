# Stage 5 Agent Benchmark Diff

- baseline: app/build/reports/agent-eval/stage-5-benchmark-report.json (snapshot before rerun)
- current: D:\Projects\Java\Java_Projects\interview-guide\app\build\reports\agent-eval\stage-5-benchmark-report.json

| Metric | Baseline | Current | Delta |
| --- | --- | --- | --- |
| passedCases | 4 | 4 | 0 |
| multiStepCases | 2 | 2 | 0 |
| averageExecutedSteps | 1 | 1 | 0 |
| averageLatencyMs | 862 | 675 | -187 |
| maxLatencyMs | 3340 | 2622 | -718 |
| exhaustedCases | 1 | 1 | 0 |
| handoffAcceptedCases | 1 | 1 | 0 |
| handoffRejectedCases | 1 | 1 | 0 |
| replayBlockedCases | 1 | 1 | 0 |

## Stop Reasons

| Stop Reason | Baseline | Current | Delta |
| --- | --- | --- | --- |
| APPROVAL_REPLAY_BLOCKED | 1 | 1 | 0 |
| DIRECT_REPLY | 1 | 1 | 0 |
| HANDOFF_NOT_ALLOWED | 1 | 1 | 0 |
| STEP_BUDGET_EXHAUSTED | 1 | 1 | 0 |

## Terminal States

| Terminal State | Baseline | Current | Delta |
| --- | --- | --- | --- |
| DEGRADED | 2 | 2 | 0 |
| EXHAUSTED | 1 | 1 | 0 |
| SUCCESS | 1 | 1 | 0 |

## Case Results

| Case | Baseline Stop Reason | Current Stop Reason | Baseline Terminal | Current Terminal | Baseline Passed | Current Passed | Changed |
| --- | --- | --- | --- | --- | --- | --- | --- |
| approval_replay_blocked | APPROVAL_REPLAY_BLOCKED | APPROVAL_REPLAY_BLOCKED | DEGRADED | DEGRADED | True | True | False |
| bounded_handoff_success | DIRECT_REPLY | DIRECT_REPLY | SUCCESS | SUCCESS | True | True | False |
| handoff_rejected_single_step | HANDOFF_NOT_ALLOWED | HANDOFF_NOT_ALLOWED | DEGRADED | DEGRADED | True | True | False |
| step_budget_exhausted | STEP_BUDGET_EXHAUSTED | STEP_BUDGET_EXHAUSTED | EXHAUSTED | EXHAUSTED | True | True | False |