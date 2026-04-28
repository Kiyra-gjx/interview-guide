# stage-3-context-set Raw Results

## 运行信息

- executedAt: `2026-04-28T20:37:02.417260500`
- runner: `./gradlew.bat :app:agentStage3ContextEval --rerun-tasks`
- reportPath: `reports/stage-3-context-set-report.json`
- diffPath: `reports/stage-3-context-set-diff.md`
- baselinePath: `baselines/stage-3-context-set-baseline-2026-04-28.json`

## case 级结果

| caseId | budget | rawContextChars | assembledChars | compressionRate | omittedSections | truncatedSections | requestBroken | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CTX-01 | 520 | 226 | 226 | 0.0% | - | - | false | 验证稳定优先级、去重和 goal 覆盖 |
| CTX-02 | 220 | 486 | 220 | 54.73% | used_tools | confirmed_facts, resource_bindings | false | 验证 budget 下必留分段保留、低优先级裁剪 |
| CTX-03 | 960 | 720 | 720 | 0.0% | - | - | false | 验证当前请求和目标完整保留 |
| CTX-04 | 320 | 122 | 122 | 0.0% | confirmed_facts, used_tools | - | false | 验证 fallback goal 与 explainable bindings |
| CTX-05 | 420 | 225 | 225 | 0.0% | - | - | false | 验证 summary 隐藏字段不影响 budget 结算 |
| CTX-06 | 360 | 193 | 193 | 0.0% | - | - | false | 验证 resume-only 资源绑定 |
| CTX-07 | 360 | 198 | 198 | 0.0% | - | - | false | 验证 knowledge-base-only 资源绑定 |
| CTX-08 | 260 | 453 | 260 | 42.6% | used_tools | confirmed_facts, resource_bindings | false | 验证 follow-up 长 facts 截断 |
| CTX-09 | 260 | 713 | 260 | 63.53% | confirmed_facts, used_tools | memory_state, resource_bindings | false | 验证超预算场景仍保留关键 section |
| CTX-10 | 320 | 137 | 137 | 0.0% | confirmed_facts, used_tools | - | false | 验证缺少绑定资源时仍可解释 |

## 说明

- `requestBroken=true` 只在当前请求理解被裁坏时使用
- `omittedSections / truncatedSections` 需要保留原始 section 名称
- 不允许只记录平均值，必须保留逐 case 明细
