# Stage3MemorySet

## 样本信息

- suiteId: `stage-3-memory-set`
- capability: `结构化记忆`
- suiteType: `quantification`

## 固定样本清单

| caseId | scenarioType | intent | setup | verifier | notes |
| --- | --- | --- | --- | --- | --- |
| MEM-01 | phase_mapping | 验证 interview tool 能映射到稳定 memory phase | 连续执行 resume / history / gap / follow-up 工具 | `currentPhase` 与工具阶段一致 | 对应 phase mapping 测试 |
| MEM-02 | fact_dedup_and_cap | 验证 confirmed facts 去重并限制条数 | 新旧 facts 有重复且长度超限 | facts 顺序稳定、重复被去掉、总量受限 | 对应 deduplicate 测试 |
| MEM-03 | summary_and_fact_normalization | 验证 summary 与 facts 会统一归一化 | 超长 summary / facts | 写回 memory 前已裁剪 | 对应 normalize summary/facts 测试 |
| MEM-04 | explicit_next_focus | 验证 structured tool output 的 `nextFocus` 优先级高于默认 summary | 工具输出同时提供 summary 与 nextFocus | `nextFocus` 来自结构化字段 | 对应 explicit nextFocus 测试 |
| MEM-05 | legacy_fact_normalization | 验证旧格式 memory facts 合并时被统一清洗 | 历史 facts 里有 legacy 文本 | 合并后 facts 规范化 | 对应 legacy normalization 测试 |
| MEM-06 | preserve_short_facts | 验证已有短 facts 不会被新工具结果错误挤掉 | 已存在多条短事实 | 旧 facts 尽量保留 | 对应 keep existing short facts 测试 |
| MEM-07 | follow_up_reuse_known_fact | 验证 follow-up 场景不再重复确认已知事实 | 第 1 轮已拿到事实，第 2 轮继续追问 | `repeatedFactChecks` 降低 | 量化收益型 case |
| MEM-08 | follow_up_reuse_tool_result | 验证 follow-up 场景复用上一步工具结果 | 上一步工具已返回摘要 | `extraCallsAfterMemoryReady=0` | 量化收益型 case |
| MEM-09 | delegated_memory_writeback | 验证 handoff 返回的 summary / confirmedFacts / nextFocus 能回写 memory | 只读委派成功后继续主链路 | 新 memory 反映 delegated context | 对应 handoff 正例语义 |

## 控制变量

- model: 优先使用固定 tool output 或 mock result
- runtimeConfig: 单步 follow-up 为主；委派 case 需显式开启 multi-step
- approvalMode: `not involved`
- baselineReference: `docs/evidence/agent-quantification/stage-3-memory-set/baselines/`
