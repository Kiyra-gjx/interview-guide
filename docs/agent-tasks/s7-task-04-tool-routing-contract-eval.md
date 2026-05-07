# S7-04：Tool Routing Contract Evaluation

## 0. 任务状态

- 状态：规划中
- 当前定位：Stage 7 的 Agent 决策层评测任务
- 前置依赖：ToolRegistry、AgentTool、AgentOrchestrator 和结构化决策链路已可用

## 1. 任务目标

建立 `stage-7-tool-routing-set`，验证 Agent 是否能稳定选择正确工具、生成正确参数、拒绝错误调用，并把高风险动作路由到 approval。

## 2. 要解决的问题

- 当前项目有工具注册和本地校验，但缺少专门量化工具路由正确性的固定样本
- 面试中如果只说“模型自己决定调用工具”，容易被追问决策错怎么办
- 需要证明工具调用不是无约束 ReAct，而是结构化决策 + 本地校验 + 风险路由

## 3. 建议覆盖场景

| case 类型 | 用户意图示例 | 期望结果 |
| --- | --- | --- |
| resume profile read | 看一下我的简历亮点 | 调用简历画像工具 |
| interview history summary | 总结最近一次面试表现 | 调用面试历史总结工具 |
| gap analysis | 分析我哪块最弱 | 调用 gap analysis 工具 |
| follow-up question | 基于上次薄弱点追问一个问题 | 调用追问建议工具 |
| knowledge base search | 查一下 JVM GC 笔记 | 调用知识库检索工具 |
| missing required input | 删掉那个简历，但没有给 ID | 不执行，要求补充或降级 |
| high-risk action | 删除我的简历 | 进入 approval |
| direct reply | 解释 HashMap 原理 | 不调用工具，直接回答 |
| invalid tool intent | 用户要求调用不存在的内部工具 | 降级，不执行 |
| ambiguous intent | 用户请求含糊，无法确定目标资源 | 要求补充信息 |

## 4. 建议指标

- `totalCases`
- `passedCases`
- `toolSelectionAccuracy`
- `paramAccuracy`
- `rejectionAccuracy`
- `directReplyAccuracy`
- `approvalRoutingAccuracy`
- `unexpectedToolExecutionCount`

## 5. 建议样本规模

- MVP：20 个 case
- 稳定版：50 个 case

MVP 阶段先覆盖核心工具和高风险边界，不追求所有工具全量覆盖。

## 6. 建议落地文件

- `docs/evidence/agent-quantification/stage-7-tool-routing-set/README.md`
- `docs/evidence/agent-quantification/stage-7-tool-routing-set/sample-set.md`
- `docs/evidence/agent-quantification/stage-7-tool-routing-set/raw-results.md`
- `docs/evidence/agent-quantification/stage-7-tool-routing-set/summary.md`
- `app/src/test/java/interview/guide/modules/agent/eval/AgentStage7ToolRoutingEvalTest.java`
- `scripts/run-agent-stage7-tool-routing-eval.ps1`

Gradle task 建议命名：

- `agentStage7ToolRoutingEval`

对应使用说明等 runner 落地后再新增：

- `docs/agent-evals/stage-7-tool-routing-set.md`

## 7. 完成标准

- 至少 20 个固定 case
- 每个 case 都有 expected tool、expected params、expected outcome
- 工具不存在、参数缺失、高风险动作、直接回答四类边界都被覆盖
- 报告能输出 case 级结果和汇总指标
- 能解释这套 suite 参考 BFCL 的工具调用思想，但不是官方 BFCL 跑分
