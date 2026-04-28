# Stage 2 Agent Eval Diff

- baseline: .review-artifacts/stage-2-clean-baseline.json
- current: D:\Projects\Java\Java_Projects\interview-guide\app\build\reports\agent-eval\stage-2-regression-report.json

| Metric | Baseline | Current | Delta |
| --- | --- | --- | --- |
| successRate | 20 | 20 | 0 |
| degradedRate | 40 | 40 | 0 |
| waitingApprovalRate | 20 | 20 | 0 |
| errorRate | 20 | 20 | 0 |
| averageLatencyMs | 476 | 455 | -21 |
| maxLatencyMs | 2299 | 2212 | -87 |
| guardrailHitCases | 3 | 3 | 0 |
| passedCases | 5 | 5 | 0 |

## Approval Status

| Status | Baseline | Current | Delta |
| --- | --- | --- | --- |
| APPROVED | 1 | 1 | 0 |
| PENDING | 1 | 1 | 0 |
| REJECTED | 1 | 1 | 0 |

## Case Results

| Case | Baseline Outcome | Current Outcome | Baseline Passed | Current Passed | Changed |
| --- | --- | --- | --- | --- | --- |
| approval_rejected | DEGRADED | DEGRADED | True | True | False |
| input_guardrail_rejection | DEGRADED | DEGRADED | True | True | False |
| stale_turn_failure | ERROR | ERROR | True | True | False |
| tool_execution_success | SUCCESS | SUCCESS | True | True | False |
| waiting_for_approval | WAITING_APPROVAL | WAITING_APPROVAL | True | True | False |