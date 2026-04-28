# S6-01：Eval Suite and Reporting Foundation

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 6 的统一证据入口任务
- 前置依赖：Stage 2 已有最小回归基线

## 0.1 当前进展

- `agentStage2Eval` 已经证明仓库具备离线固定样例、结构化报告和 baseline / diff 的基本模式
- 已新增 `agentStage5Benchmark`、`scripts/run-agent-stage5-benchmark.ps1` 和 `docs/agent-evals/stage-5-benchmark.md`，把 Stage 5 也接入了与 Stage 2 同风格的报告入口
- 已新增 `scripts/init-agent-evidence-suite.ps1` 与 `docs/evidence/agent-quantification/README.md`，统一了证据目录、suite 命名、baseline / diff 和报告文件命名约定

## 1. 任务目标

先把“怎么存、怎么跑、怎么对比、怎么留证据”统一起来，再去补更多量化样本。否则后面的数字会分散在测试类、临时报告和聊天记录里，无法稳定复用。

## 2. 要解决的问题

- Stage 2 有报告格式，Stage 3 / Stage 5 还没有统一沿用
- 样本集、原始结果、汇总结果和 trace 证据的目录约定还不稳定
- 后续做 before / after 对比时，缺少统一命名和基线保存方式

## 3. 本任务范围

- 固定样本集命名规则
- 报告产物命名规则
- baseline / diff 约定
- 原始记录与汇总结果字段约定
- 证据目录结构约定

## 4. 主要改动点

- Stage 6 文档体系
- `docs/evidence/agent-quantification/` 目录约定
- 后续 Stage 3 / Stage 5 eval 脚本与报告命名规范

## 5. 风险与边界

- 本任务不负责新增运行时能力
- 不要把“报告格式统一”误写成“已经有可信结果”
- 不要为了统一框架牺牲 Stage 2 现有可运行入口

## 6. 完成标准

- 后续新增量化任务时，知道结果应该保存到哪里
- baseline / diff 的保存方式可以跨阶段复用
- 任意一组数据都能说清：样本集、控制变量、原始记录、汇总结果、证据位置
- 当前状态：以上三项已满足，`S6-01` 可以视为完成

## 7. 验证要求

- 至少给 Stage 3 或 Stage 5 设计一套沿用 Stage 2 风格的报告入口
- 能从目录结构直接区分：样本清单、原始表、汇总表、diff、trace
- 不依赖聊天记录也能复盘数据来源
- 当前状态：Stage 5 benchmark 入口已接入，证据目录结构已固定
