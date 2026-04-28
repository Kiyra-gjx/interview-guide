# Stage 3 Context Set Report

- suite: stage-3-context-set
- generatedAt: 2026-04-28T20:37:52.156911
- totalCases: 10
- passedCases: 10
- 平均原始上下文长度: 347.3
- 平均装配后长度: 256.1
- 平均压缩率: 16.09%
- 最高压缩率: 63.53%
- 关键 section 保留率: 100.0%
- requestBroken 数量: 0

| Case | Budget | RawChars | AssembledChars | CompressionRate | Omitted | Truncated | requestBroken | Passed | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CTX-01 | 520 | 226 | 226 | 0.0% | - | - | false | true | 验证稳定优先级、去重和 goal 覆盖 |
| CTX-02 | 220 | 486 | 220 | 54.73% | used_tools | confirmed_facts, resource_bindings | false | true | 验证 budget 下必留分段保留、低优先级裁剪 |
| CTX-03 | 960 | 720 | 720 | 0.0% | - | - | false | true | 验证当前请求和目标完整保留 |
| CTX-04 | 320 | 122 | 122 | 0.0% | confirmed_facts, used_tools | - | false | true | 验证 fallback goal 与 explainable bindings |
| CTX-05 | 420 | 225 | 225 | 0.0% | - | - | false | true | 验证 summary 隐藏字段不影响 budget 结算 |
| CTX-06 | 360 | 193 | 193 | 0.0% | - | - | false | true | 验证 resume-only 资源绑定 |
| CTX-07 | 360 | 198 | 198 | 0.0% | - | - | false | true | 验证 knowledge-base-only 资源绑定 |
| CTX-08 | 260 | 453 | 260 | 42.6% | used_tools | confirmed_facts, resource_bindings | false | true | 验证 follow-up 长 facts 截断 |
| CTX-09 | 260 | 713 | 260 | 63.53% | confirmed_facts, used_tools | memory_state, resource_bindings | false | true | 验证超预算场景仍保留关键 section |
| CTX-10 | 320 | 137 | 137 | 0.0% | confirmed_facts, used_tools | - | false | true | 验证缺少绑定资源时仍可解释 |
