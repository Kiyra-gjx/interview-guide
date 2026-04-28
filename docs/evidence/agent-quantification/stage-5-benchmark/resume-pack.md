# Stage 5 Resume Pack

## 可直接复用的项目叙事

### 1. 多步执行边界

面向有明确边界的多步 Agent 运行时，建立固定 benchmark 与终态契约，覆盖只读委派成功、单步拒绝委派、步数预算耗尽和审批恢复阻断重放等关键路径。

### 2. 可安全写入简历的量化句子

1. 围绕受控多步执行构建 `4` 组固定 benchmark，覆盖只读委派成功、单步拒绝委派、步数预算耗尽和审批恢复阻断重放四类关键终态，基线回归 `100%` 通过。
2. 为多步 runtime 补齐 `stopReason / budgetStopReason / terminalState` 终态契约，在固定 benchmark 中稳定区分 `SUCCESS / DEGRADED / EXHAUSTED` 三类收口语义。
3. 将 handoff 限制为受控只读委派，并用正反例固定样本验证“可委派”和“不可委派”边界，未出现单步路径下越界扩散执行。

## 当前不建议写的内容

- “平均延迟 xxx ms”
- “预算内完成率 100%”
- “verifier 通过率 100%”
- “多步任务成功率 100%”

这些说法当前要么样本太小，要么口径还没正式定义，会在面试里很容易被追问穿。

## 证据回链

- 固定样本：`sample-set.md`
- 原始结果：`raw-results.md`
- 汇总结果：`summary.md`
- JSON 报告：`reports/stage-5-benchmark-report.json`
- Markdown 报告：`reports/stage-5-benchmark-report.md`
- diff 报告：`reports/stage-5-benchmark-diff.md`
