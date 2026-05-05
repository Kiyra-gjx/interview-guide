# stage-5-recovery-set Summary

## 核心指标

- totalCases: `9`
- passedCases: `9`
- recoveryCorrectnessRate: `100.0%`
- wrongStateContinuedCount: `0`
- replayedSideEffectCount: `0`
- recoverableCaseCoverage: `9`

## 简历准入判断

- fixedSampleSet: `yes`
- unifiedMetricDefinition: `yes`
- rawRecords: `yes`
- reviewableEvidence: `yes`
- resumeSafe: `yes`

## 当前可安全写进简历的句子

- 围绕审批恢复、trace 收尾和多步边界构建 `9` 组固定恢复场景，恢复正确率 `100%`，`wrongStateContinued=0`，`replayedSideEffect=0`。
- 为 approval / trace / budget / handoff 恢复链路补齐终态语义，在固定样本中稳定覆盖 `DEGRADED / FAILED / EXHAUSTED / SUCCESS` 四类恢复收口结果。

## 当前不要写进简历的句子

- 不要把 `9/9` case 通过写成“线上恢复成功率 100%”
- 不要把这组数据包装成“多步任务完成率 100%”

## 备注

- risks:
- followUp:
