# S6-02：Capability Quantification Sets

## 0. 任务状态

- 状态：规划中
- 当前定位：Stage 6 的能力块量化任务
- 前置依赖：S6-01 已定义统一记录和报告方式

## 0.1 当前进展

- Context、Memory、Recovery、Safety 四类能力已经在代码和单测里有可消费信号
- 当前缺的是固定 case 集、统一字段和 case 级原始记录，而不是缺一套新机制

## 1. 任务目标

围绕上下文治理、结构化记忆、任务恢复和工具安全四块能力，建立固定样本集和统一判定口径，让 Stage 3 / Stage 5 的收益可以被比较和复核。

## 2. 要解决的问题

- 当前很多信号只存在于单测断言里，还没有被组织成稳定的量化任务集
- 没有 case 级原始记录时，平均值没有说服力
- 没有统一判定方式时，面试中很容易被追问后失守

## 3. 本任务范围

- `Context Set`
- `Memory Set`
- `Recovery Set`
- `Safety Set`
- caseId、指标字段、通过标准和边界条件

## 4. 主要改动点

- 样本清单
- 原始记录表
- 汇总结果表
- 对应测试 / eval 入口

## 5. 风险与边界

- 不要先写“压缩率 30%”这类结果，再反推样本
- 不要把 guardrail 命中分布、终态分布误写成业务成功率
- 重复调用、重复确认这类指标必须先定义判定规则

## 6. 完成标准

- 四类能力至少各有一份固定样本定义
- 每类能力至少能输出 case 级原始记录字段
- 每类能力都能明确说出哪些数据已经足够进简历，哪些还只是内部基线

## 7. 验证要求

- Context 至少能输出 budget、assembled chars、section status、requestBroken
- Recovery 至少能覆盖 approval reject、approval replay blocked、resume failure、budget exhausted 等关键终态
- Safety 至少能覆盖输入拦截、待审批、拒绝收口、绕过执行阻断
