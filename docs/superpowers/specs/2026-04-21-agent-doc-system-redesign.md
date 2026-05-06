# Agent 文档系统重构方案

## 1. 背景

当前项目已经在从“偏业务的面试平台”逐步演进为“Agent 工程项目”，但当前文档体系仍然把多种不同性质的内容混在一起：

- 项目北极星目标
- 执行顺序
- 阶段边界
- 任务实施细节
- 一次性重构计划
- 历史复盘与检查清单

这会带来两个实际问题：

1. 很难回答当前项目到底是不是一个 Agent，还是只是“在 Agent 化”
2. 很难判断进度变化后，应该更新哪一类文档

这次重构必须尊重当前真实进度：

- `Stage 1` 已完成
- `Stage 2` 进行中
- `S2-01` 已完成
- 接下来的推荐任务应该是 `S2-01` 的后续任务，而不是重置路线

## 2. 主要目标

重构 Agent 文档系统，让它能稳定、清晰地回答 3 个问题：

1. 对当前项目来说，什么叫“完整的 Agent”
2. 当前已经具备哪些能力，还缺哪些能力
3. 在当前真实进度下，后续应该按什么顺序推进

## 3. 次要目标

- 保留已经完成的工作，不重写历史
- 让 stage 和 task 文档更适合交给 AI 或人单独执行
- 把长期指导文档和临时性重构记录分开
- 让后续吸收 `pico-main` 的设计点时，不会冲击主线文档结构

## 4. 非目标

- 不在本方案中重构后端架构本身
- 不改变当前真实实现状态
- 不以简历措辞优化作为第一优先级
- 不把多 Agent 作为当前近期主目标

## 5. “完整 Agent”的重新定义

文档体系必须区分两种完成度，而不是只看“有没有做完最后一个 stage”。

### Level A：完整的单 Agent 系统

当项目满足以下条件时，就可以定义为一个完整的 Agent 工程项目：

- 有稳定的执行单元模型：`session / turn / message / trace / memory`
- 有稳定的 tool 契约与上下文组装模型
- 有 guardrails、approval 与失败 / 降级语义
- 有 trace、metrics 与 eval
- 有可用的 Agent workbench，可以展示消息、memory、trace、状态与失败原因

在这个定义下，项目不需要 subagent 或开放式 planning loop，也可以被认为是完整的 Agent。

### Level B：更高级的受控 Agent

这是在单 Agent 基础之上的增强层：

- bounded multi-step loop
- budget 控制
- 明确的 stop condition
- handoff 或 subagent 边界
- 更复杂的任务拆解能力

文档必须明确说明：`Level B` 是增强层，不是“项目能否叫 Agent”的最低门槛。

## 6. 新的文档架构

新的文档体系建议分成 6 类文档。

### 6.1 顶层核心文档

#### `docs/agent-overview.md`

作用：

- 定义这个项目想成为一个什么 Agent
- 定义它不想成为什么
- 定义 `Level A` 和 `Level B` 的完成标准

必须包含：

- 项目定位
- 目标 Agent 形态
- 完成度定义
- 当前整体状态摘要

不应该包含：

- 任务清单
- 文件级改动说明
- 一次性 bugfix 细节

#### `docs/agent-capability-map.md`

作用：

- 按能力维度说明项目已经覆盖到哪里，而不是按执行顺序写

建议固定的能力轴：

- Execution Model
- Tooling
- Memory
- Observability
- Guardrails
- Eval
- Workbench
- Controlled Loop

每条能力轴都应该包含：

- 为什么重要
- 当前状态
- 完成标准
- 对应的 stage 与 task

#### `docs/agent-roadmap.md`

作用：

- 只定义“接下来按什么顺序做”

必须包含：

- 当前进度快照
- 阶段顺序
- 各阶段进入条件与退出条件
- 当前推荐下一任务

不应该包含：

- 任务级实施细节
- 历史重构残留内容

### 6.2 Stage 文档

目录：

- `docs/agent-stages/`

每个 stage 文档只应包含：

- 阶段状态
- 阶段目标
- 本阶段必须解决的问题
- 交付物
- 依赖关系
- 不在范围内的内容
- 完成标准

### 6.3 Task 文档

目录：

- `docs/agent-tasks/`

每个 task 文档只应包含：

- 任务状态
- 任务目标
- 任务范围
- 主要改动区域
- 风险与边界
- 完成标准
- 验证要求

task 文档应该做到“单独交给 AI 也能执行”。

### 6.4 历史文档

目录：

- `docs/agent-history/`

作用：

- 保留有价值的历史计划、检查清单和专项方案
- 但不再让它们继续控制当前主线 roadmap

建议迁入的文档：

- `agent-turn-refactor-plan.md`
- `agent-mvp-file-plan.md`
- `agent-mvp-review-checklist.md`

这些文档依然有历史价值，但不应该继续作为顶层导航文档存在。

### 6.5 规格文档

目录：

- `docs/superpowers/specs/`

作用：

- 保存这种“先定结构、再改文档”的设计规格

本文件本身就属于这一类。

## 7. Stage 模型重构

当前 4 个 stage 的模型建议重构为 5 个 stage。

### Stage 1：Execution Foundation

状态：

- 已完成

目标：

- 稳定 turn 级执行模型与基础 runtime 契约

保留任务：

- `S1-01 Turn 模型与状态语义`
- `S1-02 响应契约与 API 收口`
- `S1-03 Tool 契约与 Session 资源校验`

### Stage 2：Safe and Observable Runtime

状态：

- 进行中

当前已完成任务：

- `S2-01`

目标：

- 把 runtime 做成可解释、可控制、可度量的系统

任务：

- `S2-01 Trace、Memory 与 Metrics 收敛`
- `S2-02 Guardrails Baseline`
- `S2-03 Runtime Approval and Policy`
- `S2-04 Eval and Regression`

重构理由：

- guardrails 和 approval 虽然相关，但不属于同一层问题，拆开更合理
- eval 应该成为本阶段的收口任务，而不是模糊挂在末尾

### Stage 3：Domain Tooling and Context Assembly

状态：

- 未开始

目标：

- 把当前最小化 Agent 壳子，扩展成一个更可信的面试场景 Agent

任务：

- `S3-01 Interview Context Tools`
- `S3-02 Context Assembly Policy`
- `S3-03 Tool Output Normalization`

重构理由：

- tool 扩展、上下文选择、输出归一化是三类不同问题，不应该混成一个 task

### Stage 4：Agent Workbench and Demo Surface

状态：

- 未开始

目标：

- 把系统做成一个可展示、可调试、可解释的 Agent 产品界面

任务：

- `S4-01 Agent Workbench UI`
- `S4-02 Debuggable Demo Flow`

重构理由：

- “页面做出来”和“演示真的稳定可复现”不是同一回事
- 这个阶段属于 `Level A` 的组成部分，不应该只是 tool 阶段下的附属任务

### Stage 5：Bounded Multi-Step Agent

状态：

- 未开始

目标：

- 在完整单 Agent 的基础上，升级为受控多步 Agent

任务：

- `S5-01 Bounded Loop and Budget Control`
- `S5-02 Stop Conditions and Failure Semantics`
- `S5-03 Handoff and Subagent Boundary`

重构理由：

- 在 handoff / subagent 之前，必须先把 stop condition 和 failure semantics 单独做清楚

## 8. 完成度与阶段映射

### Level A 的完成边界

当以下阶段全部完成时，项目应被定义为“完整单 Agent 工程系统”：

- `Stage 1`
- `Stage 2`
- `Stage 3`
- `Stage 4`

这时项目已经可以稳定地作为完整 Agent 项目来讲述。

### Level B 的完成边界

当 `Stage 5` 完成时，项目应被定义为“受控多步 Agent 系统”。

## 9. 文档迁移顺序

建议按以下顺序执行文档重写：

1. 新建 `agent-overview.md`
2. 新建 `agent-capability-map.md`
3. 重写 `agent-roadmap.md`
4. 重写 stage 文档，使其匹配新的 5-stage 模型
5. 重写并补齐 task 文档
6. 将历史专项文档移动到 `docs/agent-history/`
7. 更新所有交叉链接，保证新文档系统内部一致

## 10. 文档重写约束

- `Stage 1` 必须继续标记为已完成
- `Stage 2` 必须继续标记为进行中
- `S2-01` 必须继续标记为已完成
- roadmap 必须指向 `S2-01` 的后续任务，而不是暗示重做
- 重写后的文档不能让项目看起来比现在更“未完成”
- 文档必须明确写出：`Stage 4` 完成时，已经可以形成完整单 Agent 叙事

## 11. 风险

### 风险 1：文档先于实现过度膨胀

缓解方式：

- task 文档保持实施导向与边界收敛

### 风险 2：改文档后旧链接失效

缓解方式：

- 把链接更新作为同一批改动的一部分
- 历史文档迁移到 `docs/agent-history/` 后继续保留

### 风险 3：roadmap 看起来比项目实际体量更大

缓解方式：

- 始终以 `Level A` 为第一完成边界
- 明确 `Stage 5` 是增强项，而不是最低完成线

## 12. 验收标准

当满足以下条件时，这次文档系统重构算成功：

- 读者能快速判断当前系统是不是 Agent，以及属于哪个完成层级
- 读者能区分“能力缺口”和“执行顺序”这两类信息
- 当前真实进度被准确表达
- 每个 stage 都有明确的进入边界与退出边界
- 每个 task 都能独立执行，不依赖混杂的 roadmap 长文
- 历史文档不再与主线 roadmap 抢权
