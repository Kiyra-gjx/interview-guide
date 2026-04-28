# Agent 项目量化记录模板

## 1. 实验基本信息

- 实验名称：
- 能力块：
- 日期：
- 负责人：
- 样本集名称：
- 样本数：

## 2. 控制变量

- 模型：
- 是否开启 multi-step：
- `maxSteps`：
- `maxDurationMillis`：
- `maxEstimatedModelTokens`：
- 是否涉及 approval：
- 其它约束：

## 3. 目标

- 这次要证明什么：
- 对照基线是什么：
- 成功标准是什么：

## 4. 原始记录表

按能力块选一个表，或者自己扩展。

### 4.1 上下文治理

| caseId | budget | rawContextChars | assembledChars | compressionRate | omittedSections | truncatedSections | requestBroken | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |  |  |

### 4.2 结构化记忆

| caseId | turnCount | toolCallCount | repeatedToolCalls | repeatedFactChecks | extraCallsAfterMemoryReady | notes |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |

### 4.3 任务恢复

| caseId | recoveryType | expectedTerminalState | actualTerminalState | wrongStateContinued | replayedSideEffect | passed | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |  |

### 4.4 工具安全与运行治理

| caseId | riskType | approvalRequired | approvalStatus | guardrailHit | directExecutionBypassed | replayBlocked | passed | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |  |  |

### 4.5 评测与审计闭环

| caseId | scenarioType | expectedOutcome | actualOutcome | latencyMs | passed | notes |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |

## 5. 汇总结果

- 核心指标 1：
- 核心指标 2：
- 核心指标 3：
- 是否达到成功标准：

## 6. 证据位置

- 原始结果表：
- JSON 报告：
- Markdown 报告：
- diff 报告：
- trace / 截图：

## 7. 最后可转成什么简历句子

只写公式，不要先写成稿：

- 围绕 `[能力块]` 设计 `[机制]`，在 `[样本数]` 组 `[任务/场景]` 中，将 `[指标]` 从 `[X]` 变为 `[Y]`，并保持 `[边界条件]`

## 8. 是否允许进入简历

- 是否有固定样本集：
- 是否有统一口径：
- 是否有原始记录：
- 是否有可复核证据：
- 结论：
