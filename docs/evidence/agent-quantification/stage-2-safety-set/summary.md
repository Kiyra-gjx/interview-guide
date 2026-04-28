# stage-2-safety-set Summary

## 核心指标

- totalCases: `10`
- passedCases: `10`
- approvalRequiredHitRate: `100.0%`
- approvalRejectedDegradeRate: `100.0%`
- guardrailHitCases: `6`
- directExecutionBypassedCount: `0`
- replayBlockedCases: `1`
- approvalStatusCounts: `{APPROVED=2, NONE=6, PENDING=1, REJECTED=1}`

## 简历准入判断

- fixedSampleSet: `yes`
- unifiedMetricDefinition: `yes`
- rawRecords: `yes`
- reviewableEvidence: `yes`
- resumeSafe: `yes`

## 当前可安全写进简历的句子

- 围绕工具安全与运行治理构建 `10` 组固定安全场景，高风险动作审批命中率 `100%`，审批拒绝后降级收口率 `100%`，未出现绕过审批直接执行。
- 为审批恢复链路补齐 replay blocked 保护，在固定场景中识别并阻断重复副作用执行，`directExecutionBypassed=0`、`replayBlockedCases=1`。

## 当前不要写进简历的句子

- 不要把 `guardrailHitCases=6` 写成“系统更安全 60%”
- 不要把这 10 组固定 case 的通过写成“线上安全率 100%”

## 备注

- risks:
- followUp:
