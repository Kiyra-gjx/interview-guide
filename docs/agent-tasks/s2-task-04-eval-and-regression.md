# S2-04：Eval and Regression

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 2 的收口任务，已落地第一版最小可用 Eval / Regression 基线
- 前置依赖：S2-03 已完成

## 1. 任务目标

建立最小可用的离线评测与回归验证机制，让系统改动不再只靠主观感觉判断。

## 2. 要解决的问题

- 当前缺少固定样例集，无法稳定比较改动前后的行为变化
- 缺少统一的回归入口，评测经常依赖手工触发和临时判断
- 成功率、降级率、错误率、延迟等指标没有被系统性留档
- Guardrails 与 Approval 落地后，缺少办法验证它们是否真的提升了系统质量

## 3. 本任务范围

- 离线样例集
- 回归脚本或测试入口
- 指标对比
- 评测结果留档

## 4. 主要改动点

- `app/src/test/java/interview/guide/modules/agent/...`
- 可能新增 `scripts/` 评测脚本
- 评测样例数据与文档
- 评测结果输出格式

## 5. 风险与边界

- Eval 不能替代功能测试或集成测试，它关注的是行为质量与趋势比较
- 评测样例需要稳定、可重复，不能强依赖高波动的外部环境
- 指标输出要可读，但不要把评测体系做成过重的平台工程

## 6. 完成标准

- 至少有一套固定样例可重复执行
- 改动前后能看见关键指标与行为结果变化
- 评测入口可被团队复用，而不是只存在于一次性命令里
- 评测结果能被留档，便于后续回看和比较

## 7. 验证要求

- 一条清晰的测试或脚本入口可以跑完整套最小评测
- 输出至少包含成功率、降级率、错误率与延迟等核心指标
- 文档中说明如何新增样例、如何比较结果、如何解释异常波动

## 8. 当前落地结果

- 固定样例集：`app/src/test/java/interview/guide/modules/agent/eval/AgentStage2RegressionEvalTest.java`
- Gradle 入口：`./gradlew.bat :app:agentStage2Eval`
- 脚本入口：`powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage2-eval.ps1`
- 报告目录：`app/build/reports/agent-eval/`
- 使用说明：[Stage 2 Eval / Regression 使用说明](../agent-evals/stage-2-regression.md)

当前第一版固定样例覆盖成功、降级、待审批、审批拒绝和过期 turn 错误，报告包含成功率、降级率、等待审批率、错误率、延迟、guardrail 命中样例数、approval 状态分布与 case 级 diff。
