# stage-5-benchmark Summary

## 核心指标

- totalCases: `4`
- passedCases: `4`
- multiStepCases: `2`
- averageExecutedSteps: `1.0`
- exhaustedCases: `1`
- handoffAcceptedCases: `1`
- handoffRejectedCases: `1`
- replayBlockedCases: `1`
- stopReasonCounts: `{APPROVAL_REPLAY_BLOCKED=1, DIRECT_REPLY=1, HANDOFF_NOT_ALLOWED=1, STEP_BUDGET_EXHAUSTED=1}`
- terminalStateCounts: `{DEGRADED=2, EXHAUSTED=1, SUCCESS=1}`

## 简历准入判断

- fixedSampleSet: `yes`
- unifiedMetricDefinition: `yes`
- rawRecords: `yes`
- reviewableEvidence: `yes`
- resumeSafe: `partial`

## 当前可安全写进简历的句子

- 围绕受控多步执行构建 `4` 组固定 benchmark，覆盖只读委派成功、单步拒绝委派、步数预算耗尽和审批恢复阻断重放四类关键终态，基线回归 `100%` 通过。
- 为多步 runtime 补齐 `stopReason / budgetStopReason / terminalState` 终态契约，在固定 benchmark 中稳定区分 `SUCCESS / DEGRADED / EXHAUSTED` 三类收口语义。
- 将 handoff 限制为受控只读委派，并用正反例固定样本验证“可委派”和“不可委派”边界，未出现单步路径下越界扩散执行。

## 当前不要写进简历的句子

- 不要写“Stage 5 平均延迟 862ms”或任何离线 mock latency
- 不要把 `4/4` case 通过写成“Agent 整体成功率 100%”
- 不要写“100% 预算内完成率”，因为当前 benchmark 还没有定义完整的多任务完成率口径

## 备注

- risks: 样本数仍然偏小，当前更适合作为工程化边界证据，而不是泛化能力证明
- followUp: 如果后续补多任务固定集，可以再引入 `completionRate`、`averageStepCount` 的更强简历表述
