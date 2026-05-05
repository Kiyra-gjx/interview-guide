# Stage 5 Recovery Set Diff

- baseline: app/build/reports/agent-eval/stage-5-recovery-set-report.json (snapshot before rerun)
- current: D:\Projects\Java\Java_Projects\interview-guide\app\build\reports\agent-eval\stage-5-recovery-set-report.json

| Metric | Baseline | Current | Delta |
| --- | --- | --- | --- |
| passedCases | 9 | 9 | 0 |
| recoveryCorrectnessRate | 100 | 100 | 0 |
| wrongStateContinuedCount | 0 | 0 | 0 |
| replayedSideEffectCount | 0 | 0 | 0 |
| coveredRecoveryTypes | 9 | 9 | 0 |

## Case Results

| Case | Baseline Terminal | Current Terminal | Baseline Stop Reason | Current Stop Reason | Baseline Passed | Current Passed | Changed |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RCV-01 | DEGRADED | DEGRADED | APPROVAL_REJECTED | APPROVAL_REJECTED | True | True | False |
| RCV-02 | DEGRADED | DEGRADED | APPROVAL_EXPIRED | APPROVAL_EXPIRED | True | True | False |
| RCV-03 | DEGRADED | DEGRADED | APPROVAL_REPLAY_BLOCKED | APPROVAL_REPLAY_BLOCKED | True | True | False |
| RCV-04 | DEGRADED | DEGRADED | APPROVAL_REJECTED | APPROVAL_REJECTED | True | True | False |
| RCV-05 | DEGRADED | DEGRADED | APPROVAL_RESUME_FAILED | APPROVAL_RESUME_FAILED | True | True | False |
| RCV-06 | FAILED | FAILED | TURN_EXPIRED | TURN_EXPIRED | True | True | False |
| RCV-07 | EXHAUSTED | EXHAUSTED | STEP_BUDGET_EXHAUSTED | STEP_BUDGET_EXHAUSTED | True | True | False |
| RCV-08 | DEGRADED | DEGRADED | HANDOFF_NOT_ALLOWED | HANDOFF_NOT_ALLOWED | True | True | False |
| RCV-09 | SUCCESS | SUCCESS | DIRECT_REPLY | DIRECT_REPLY | True | True | False |