# stage-3-memory-set Summary

## 核心指标

- totalCases: `9`
- passedCases: `9`
- averageToolCallCount: `1.33`
- repeatedToolCallsBefore: `3`
- repeatedToolCallsAfter: `0`
- repeatedFactChecksBefore: `2`
- repeatedFactChecksAfter: `0`
- extraCallsAfterMemoryReady: `0`

## 简历准入判断

- fixedSampleSet: `yes`
- unifiedMetricDefinition: `yes`
- rawRecords: `yes`
- reviewableEvidence: `yes`
- resumeSafe: `yes`

## 当前可安全写进简历的句子

- 围绕 memory phase、facts 归一化、委派写回和 follow-up 复用构建 `9` 组固定样本，平均 `toolCallCount=1.33`，重复工具读取从 `3` 次降到 `0`。
- 为 follow-up 复用链路建立固定对照，在样本中把 `repeatedFactChecks` 从 `2` 次降到 `0`，且 `extraCallsAfterMemoryReady=0`。

## 当前不要写进简历的句子

- 不要把 `repeatedToolCallsBefore -> After` 写成“线上提效 100%”
- 不要把 `9/9` case 通过写成“所有多轮对话都不会重复调用工具”

## 备注

- risks:
- followUp: 可继续补一组更贴近真实业务链路的跨 turn trace 归档，增强面试追问时的可解释性
