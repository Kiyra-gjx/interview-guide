# stage-3-memory-set Raw Results

## 运行信息

- executedAt: `2026-04-28T21:28:54.409943400`
- runner: `powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage3-memory-eval.ps1 -BaselineReport app/build/reports/agent-eval/baselines/stage-3-memory-set-before-change.json`
- reportPath: `reports/stage-3-memory-set-report.json`
- diffPath: `reports/stage-3-memory-set-diff.md`
- baselinePath: `baselines/stage-3-memory-set-baseline-2026-04-28.json`

## case 级结果

| caseId | turnCount | toolCallCount | repeatedToolCallsBefore | repeatedToolCallsAfter | repeatedFactChecksBefore | repeatedFactChecksAfter | extraCallsAfterMemoryReady | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MEM-01 | 1 | 4 | 0 | 0 | 0 | 0 | 0 | 验证 interview tools 到 memory phase 的稳定映射 |
| MEM-02 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 验证 facts 去重、顺序稳定和总量上限 |
| MEM-03 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 验证 summary 和 facts 写回前会被统一归一化 |
| MEM-04 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 验证结构化 nextFocus 高于默认 summary |
| MEM-05 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 验证 legacy facts 在合并时会被统一清洗 |
| MEM-06 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 验证已有短 facts 不会被新结果错误挤掉 |
| MEM-07 | 2 | 1 | 1 | 0 | 1 | 0 | 0 | follow-up 直接复用已确认事实，避免再次读取同一简历事实 |
| MEM-08 | 2 | 1 | 1 | 0 | 1 | 0 | 0 | follow-up 直接复用上一步工具结论，避免重复读取知识库 |
| MEM-09 | 2 | 1 | 1 | 0 | 0 | 0 | 0 | 委派产出的 summary / facts / nextFocus 写回后，follow-up 不再额外读工具 |

## 说明

- `repeatedToolCallsBefore / After` 使用固定 no-reuse 对照和当前实际命中路径做比较，只统计 follow-up 阶段对同一资源的重复读取
- `repeatedFactChecksBefore / After` 只统计已经拿到结论后仍重复确认的行为
- `extraCallsAfterMemoryReady` 用于描述“memory 已足够但仍多做调用”的浪费；本轮固定样本为 `0`
