# S3-01：Interview Context Tools

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 3 已完成任务，作为首批 interview context tools 基线已落地
- 前置依赖：Stage 2 完成

## 0.1 已完成项

- 已新增 `get_interview_history_summary`、`analyze_interview_gaps`、`suggest_follow_up_questions` 3 个只读 interview domain 工具
- 已新增 `InterviewToolContextService`，统一解析 `resumeId` / `sessionId`、fallback、归属校验与稳定空结果语义
- 已新增 `InterviewGapAnalyzer` 与 `FollowUpQuestionPlanner`，把短板分析与追问规划下沉为本地规则层
- 已补 `AgentTool` 的必填输入与二选一输入契约，`AgentOrchestrator` 会在缺少 `sessionId` / `resumeId` 时前置降级，不再等到工具执行期抛错
- `get_interview_history_summary` 已改为使用全量历史统计，并补齐 spec 承诺的“最近一次有效结论”输出
- 显式 `sessionId` 路径已改为预加载 `resume` 归属，降低 interview 工具链路对 LAZY 关联与 OSIV 的隐式依赖
- 已收紧 confirmed facts 写入范围，避免把短期运行态信息持续污染长期 memory
- 已补齐工具专项测试、`AgentOrchestrator` 回归测试与最小 Stage 2 回归验证

## 0.2 本轮复核结论

- S3-01 范围内的首批 interview context tools 已具备可消费状态
- 当前未发现阻塞 S3-01 达标的已知正确性缺口
- Stage 3 在 S3-02 已完成后，下一步应进入 [S3-03：Tool Output Normalization](./s3-task-03-tool-output-normalization.md)

## 1. 任务目标

把 Tool 集合扩展到足以支撑 interview domain 关键场景的水平，让 Agent 具备明确的业务价值密度。

## 2. 要解决的问题

- 当前 Tool 集合仍偏基础，难以支撑“求职 / 面试 Agent”叙事
- 领域工具的输入输出约定不够统一，扩展后容易破坏主链路
- 缺少能把简历、面试记录、知识库结果转成领域结论的专用工具

## 3. 本任务范围

- interview history / report / summary 类工具
- resume / knowledge gap / follow-up suggestion 类工具
- 工具注册、工具契约与调用入口扩展
- 工具输出与 trace 兼容性保持

## 4. 主要改动点

- 新增 `interview.guide.modules.agent.tool.*`
- Tool registry 与相关 DTO
- `interview.guide.modules.agent.service.AgentOrchestrator`
- 与领域工具相关的测试与样例

## 5. 风险与边界

- 本任务不处理统一上下文组装策略，那属于 S3-02
- 本任务不承担输出归一化治理，那属于 S3-03
- 不为了“工具看起来更多”而堆积低价值工具

## 6. 完成标准

- 至少形成一组能支撑 interview domain 叙事的核心工具
- 新增工具不会破坏既有 Tool 契约、trace 与主回答链路
- 工具输入输出边界清楚，能说明每个工具解决什么问题

## 7. 验证要求

- 新工具具备专项测试或最小集成验证
- 主链路能稳定调用新增工具，并在 trace 中正确展示结果
- 至少有一个 end-to-end 场景能体现新增领域工具的价值
