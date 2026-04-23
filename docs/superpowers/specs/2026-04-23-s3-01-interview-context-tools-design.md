# S3-01 Interview Context Tools 设计方案

## 1. 背景

当前 `modules/agent` 已经具备单 turn 编排、trace、guardrail、approval 与 memory 基线，但 interview domain 工具仍然偏基础，主要只有：

- `get_resume_profile`
- `search_knowledge_base`

这两类工具能提供简历和知识库上下文，但还不足以支撑“面试教练 Agent”的核心叙事。用户真正高频的问题通常不是“把原始数据查出来”，而是：

- 我最近几次面试整体表现如何
- 我当前最明显的短板是什么
- 下一步该练什么、该怎么继续追问

`S3-01` 的目标不是单纯增加工具数量，而是补齐一组能直接输出 interview 领域结论的读型 / 轻量分析型工具，并保持现有单 Agent 主链路稳定。

## 2. 目标

本次设计要达成以下目标：

1. 补齐一组可复用的 interview domain 核心工具
2. 让工具输出具备明确业务价值，而不是仅返回原始字段
3. 保持现有 Tool 契约、trace、guardrail、memory 和最终回答链路兼容
4. 不引入多步工具编排、二次 LLM 调用或超出 `S3-01` 边界的机制

## 3. 非目标

- 不在 Tool 内再次调用模型
- 不实现统一 context assembly 策略，这属于 `S3-02`
- 不实现 tool output normalization 治理，这属于 `S3-03`
- 不引入多工具串联、bounded loop、planner、subagent
- 不新增写型或高风险副作用工具
- 不为了“看起来工具更多”而拆出低价值原子查询工具

## 4. 方案选择

本次在三种方案中做了取舍：

### 4.1 纯查询聚合型

只做数据读取与简单拼装，实现风险最低，但业务价值密度不足，最终回答仍严重依赖模型自行理解原始数据。

### 4.2 轻量分析型（选定）

Tool 保持只读，但基于现有结构化数据做本地规则归纳，直接输出 interview 领域结论。该方案兼顾可解释性、可测试性和业务价值密度。

### 4.3 模型驱动分析型

在 Tool 内再调用模型生成短板分析与追问建议，表达灵活，但显著增加延迟、不确定性、trace 复杂度和测试成本，不符合当前阶段边界。

最终选择：**轻量分析型**。

## 5. 首批工具集

本次不按底层字段拆工具，而按用户意图与单轮单工具决策约束设计“少而强”的工具集。

### 5.1 保留现有工具

- `get_resume_profile`
- `search_knowledge_base`

### 5.2 新增工具

#### `get_interview_history_summary`

面向“最近面试整体情况”“是否还有未完成面试”“最近几次趋势如何”等问题。

核心输出：

- 总面试数
- 已评估数
- 未完成数
- 最近一次面试状态
- 最近若干次分数
- 简单趋势判断
- 最近一次有效结论

#### `analyze_interview_gaps`

面向“当前短板是什么”“知识点哪里薄弱”“最应该补哪块”等问题。

核心输出：

- 总体结论
- 低分维度
- 重复出现的改进项
- 知识缺口标签
- 练习优先级

#### `suggest_follow_up_questions`

面向“接下来怎么继续练”“给我几个针对性的追问”“下一步该问什么”等问题。

核心输出：

- 3 到 5 个追问建议
- 每条建议对应的能力点
- 推荐原因
- 建议关注点

## 6. 架构与职责边界

### 6.1 总体原则

- `agent` 模块继续站在 `interview` 领域能力之上做编排
- Tool 类保持轻量，避免把查数据、选目标、分析规则、输出组装全部塞进一个类
- 轻量分析逻辑保留在 `agent` 模块，不反向污染 `modules/interview` 主业务服务

### 6.2 建议结构

#### Tool 层

位置：`app/src/main/java/interview/guide/modules/agent/tool/`

新增：

- `InterviewHistorySummaryTool`
- `InterviewGapAnalysisTool`
- `FollowUpQuestionSuggestionTool`

职责：

- 声明工具名、描述、输入契约与风险等级
- 读取并标准化输入
- 调用共享读取 / 分析服务
- 返回 `AgentToolResult`

#### Interview Tool Context 层

位置：`app/src/main/java/interview/guide/modules/agent/tool/interview/`

新增协调服务，例如：

- `InterviewToolContextService`

职责：

- 按 `resumeId` 或 `sessionId` 解析目标面试会话
- 读取最近几次会话、未完成会话、最近一次已评估会话
- 统一处理“显式参数优先、上下文回退、空结果稳定返回”的逻辑
- 将 `InterviewSessionEntity`、`InterviewDetailDTO`、`InterviewReportDTO` 转换为工具侧稳定中间对象

#### 轻量分析层

位置：`app/src/main/java/interview/guide/modules/agent/tool/interview/`

新增：

- `InterviewGapAnalyzer`
- `FollowUpQuestionPlanner`

职责：

- 在内存中基于现有结构化数据做规则归纳
- 不访问数据库
- 不调用模型
- 保持纯规则、可单测

## 7. 输入输出契约

### 7.1 `get_interview_history_summary`

输入：

- `resumeId`
- `limit`

规则：

- 优先使用显式 `resumeId`
- 缺失时回退到当前 Agent session 绑定的 `resumeId`
- `limit` 默认 `5`，范围限制在 `1` 到 `10`

空结果语义：

- 没有面试记录时返回稳定空结果，不抛异常

异常语义：

- 显式和上下文都无法得到 `resumeId` 时，视为无效输入

### 7.2 `analyze_interview_gaps`

输入：

- `sessionId`
- `resumeId`

规则：

- 显式 `sessionId` 优先
- 未给 `sessionId` 时，选择该简历最近一次已评估面试
- 若同时给出 `sessionId` 和 `resumeId`，必须校验归属一致

缺信号语义：

- 指定会话未完成评估时，不伪造短板结论，返回稳定提示结果
- 未指定 `sessionId` 时，若最近一次面试未评估，则继续向前找最近一次已评估会话
- 若完全没有可分析报告，返回“暂无可分析报告”

### 7.3 `suggest_follow_up_questions`

输入：

- `sessionId`
- `resumeId`
- `focusCategory`
- `maxCount`

规则：

- `maxCount` 默认 `3`，范围限制在 `1` 到 `5`
- 优先使用显式 `sessionId`
- 否则优先选择最近一次已评估面试
- 如果没有已评估面试，则回退到最近一次已有题目记录的面试

缺信号语义：

- 如果连可用题目或评估报告都没有，返回稳定空结果，不抛异常

生成约束：

- 必须输出具体追问
- 不允许出现“再详细说说”这类空泛表达
- 每条追问必须绑定明确能力维度或关注点

## 8. 轻量分析规则

### 8.1 短板分析规则

`analyze_interview_gaps` 只允许使用确定性信号：

- `overallScore`
- `categoryScores`
- `improvements`
- `questionDetails`
- `referenceAnswers`

规则方向：

- 低分维度排序
- 高频改进项去重
- 分类与改进项关键词映射为有限知识缺口标签
- 输出练习优先级

边界要求：

- 匹配不到知识标签时保留原分类，不做过度推断
- confirmed facts 只允许写入硬事实，不把主观建议当成事实

### 8.2 追问建议规则

`suggest_follow_up_questions` 采用本地模板与规则组合：

- 优先根据低分维度和重复改进项选择追问模板
- 若缺少评估结果，则按题目分类生成通用追问
- 输出时附带原因、能力点和关注点

边界要求：

- 不调用模型
- 不生成脱离现有上下文的泛化题库
- 不把未来要做的多轮追问链条提前编码成流程

## 9. trace / memory / prompt 兼容

### 9.1 trace 兼容

保持现有 `AgentToolResult` 结构不变：

- `summary`
- `answerPayload`
- `debugPayload`
- `confirmedFacts`

约束：

- `answerPayload` 只放最终回答需要消费的领域结论
- `debugPayload` 只放调试信息，例如：
  - 最终选中的 `sessionId`
  - 是否走了 fallback
  - 使用了哪些信号源
  - 哪些分析因数据缺失被跳过

### 9.2 memory 兼容

继续复用 `AgentMemoryService.updateAfterTool(...)`，但扩展 phase：

- `interview_history_ready`
- `interview_gap_ready`
- `follow_up_ready`

约束：

- `confirmedFacts` 只累积硬事实
- `nextFocus` 直接使用工具 `summary`
- 不把建议性结论写成长期 memory 事实

### 9.3 prompt 兼容

需要更新 `app/src/main/resources/prompts/agent-system.st` 中的工具说明，补充：

- 面试历史概况
- 面试短板分析
- 追问建议生成

保持不变的规则：

- 一轮最多选择 1 个工具
- 不引入多工具串联
- 不在提示词中加入 Stage 5 风格的规划或多步执行暗示

`agent-answer-user.st` 暂不改变结构，新增工具的 `answerPayload` 需按现有回答 prompt 可直接消费的方式设计。

## 10. 失败与空数据语义

本次明确区分“正常空场景”和“真正异常场景”。

### 10.1 正常空场景

以下情况应返回稳定结果，不抛异常：

- 某简历暂无面试记录
- 没有已评估面试报告
- 指定会话仍在评估中
- 可用题目或报告不足以生成追问建议

### 10.2 真正异常场景

以下情况才抛异常，进入现有降级链路：

- 必需输入缺失，例如无法获得 `resumeId`
- 显式输入冲突，例如 `sessionId` 不属于 `resumeId`
- 输入类型非法，例如 `limit` / `maxCount` 无法解析

## 11. 测试策略

本次按 TDD 分三层补测试。

### 11.1 Tool 专项测试

新增 3 个 Tool 测试类，覆盖：

- 输入 fallback
- 空结果稳定返回
- 归属校验
- 输出字段边界
- confirmed facts 仅包含硬事实

### 11.2 规则分析测试

为纯规则分析类单独编写单测，覆盖：

- 低分维度排序
- 重复改进项去重与归纳
- 无评估结果时的降级逻辑
- 追问建议数量、针对性与去空泛化

### 11.3 编排器回归测试

在 `AgentOrchestratorTest` 中补充主链路场景，覆盖：

- 模型选择新增工具后的正常执行
- trace 中的 selectedTool 与 payload 展示
- memory phase 正确推进
- 最终 reply、guardrail、completionMode 与现有语义兼容

## 12. 验收标准

满足以下条件时，本次 `S3-01` 设计视为达标：

- `ToolRegistry.describeTools()` 能暴露新增工具说明
- 新工具不会破坏现有 `get_resume_profile` 与 `search_knowledge_base`
- 至少形成一组能支撑 interview domain 叙事的核心工具
- 工具输入输出边界清楚，可说明每个工具解决什么问题
- trace 与 memory 仍能稳定展示工具调用结果
- 至少有一个端到端场景能体现新增工具的业务价值，例如：
  - 用户询问最近面试薄弱点
  - Agent 选择短板分析或追问建议工具
  - trace 可解释工具选择与结果
  - 最终回复给出具体结论和练习方向

## 13. 风险与控制

### 风险 1：工具变成杂糅大类

控制方式：

- 拆分 Tool 层、Context 层、分析层
- 让 Tool 只做契约与结果组装

### 风险 2：把 Agent 侧语义污染到 interview 主业务模块

控制方式：

- 轻量分析逻辑保留在 `modules/agent`
- `modules/interview` 只提供领域数据与既有业务服务

### 风险 3：正常空数据被误当异常

控制方式：

- 明确空结果返回策略
- 仅对输入缺失、归属冲突与类型错误抛异常

### 风险 4：追问建议变得空泛或模板化

控制方式：

- 追问必须绑定能力点、推荐原因与关注点
- 对输出内容做测试约束，防止空泛模板漏过
