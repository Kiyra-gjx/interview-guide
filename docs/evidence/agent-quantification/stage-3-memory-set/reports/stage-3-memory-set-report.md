# Stage 3 Memory Set Report

- suite: stage-3-memory-set
- generatedAt: 2026-04-28T21:28:54.409943400
- totalCases: 9
- passedCases: 9
- averageToolCallCount: 1.33
- repeatedToolCallsBefore: 3
- repeatedToolCallsAfter: 0
- repeatedFactChecksBefore: 2
- repeatedFactChecksAfter: 0
- extraCallsAfterMemoryReady: 0

| Case | Scenario | TurnCount | ToolCalls | RepeatedTools | RepeatedFacts | ExtraAfterReady | Passed | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MEM-01 | phase_mapping | 1 | 4 | 0 -> 0 | 0 -> 0 | 0 | true | 验证 interview tools 到 memory phase 的稳定映射 |
| MEM-02 | fact_dedup_and_cap | 1 | 1 | 0 -> 0 | 0 -> 0 | 0 | true | 验证 facts 去重、顺序稳定和总量上限 |
| MEM-03 | summary_and_fact_normalization | 1 | 1 | 0 -> 0 | 0 -> 0 | 0 | true | 验证 summary 和 facts 写回前会被统一归一化 |
| MEM-04 | explicit_next_focus | 1 | 1 | 0 -> 0 | 0 -> 0 | 0 | true | 验证结构化 nextFocus 高于默认 summary |
| MEM-05 | legacy_fact_normalization | 1 | 1 | 0 -> 0 | 0 -> 0 | 0 | true | 验证 legacy facts 在合并时会被统一清洗 |
| MEM-06 | preserve_short_facts | 1 | 1 | 0 -> 0 | 0 -> 0 | 0 | true | 验证已有短 facts 不会被新结果错误挤掉 |
| MEM-07 | follow_up_reuse_known_fact | 2 | 1 | 1 -> 0 | 1 -> 0 | 0 | true | follow-up 直接复用已确认事实，避免再次读取同一简历事实 |
| MEM-08 | follow_up_reuse_tool_result | 2 | 1 | 1 -> 0 | 1 -> 0 | 0 | true | follow-up 直接复用上一步工具结论，避免重复读取知识库 |
| MEM-09 | delegated_memory_writeback | 2 | 1 | 1 -> 0 | 0 -> 0 | 0 | true | 委派产出的 summary / facts / nextFocus 写回后，follow-up 不再额外读工具 |
