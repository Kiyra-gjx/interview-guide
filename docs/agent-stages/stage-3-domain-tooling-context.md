# Stage 3: Domain Tooling and Context Assembly

## 0. 阶段状态
- 阶段状态：进行中
- 已完成任务：S3-01、S3-02
- 当前任务：[S3-03：Tool Output Normalization](../agent-tasks/s3-task-03-tool-output-normalization.md)
- 前置条件：Stage 2 完成

## 0.1 当前进展

- 已落地首批 interview domain 读型 / 轻量分析型工具，覆盖历史概况、短板分析与追问建议
- Stage 3 当前已完成统一上下文装配策略落地，剩余重点转向工具输出归一化，由 S3-03 承接

## 1. 阶段目标

把 Agent 从“安全可运行”推进到“具备 interview domain 价值密度”，聚焦 interview 相关工具能力与上下文组装策略本身，不承担 workbench 展示，也不提前进入多步自治能力。

## 2. 本阶段必须解决的问题

- interview domain 相关工具覆盖不足，无法稳定支撑核心场景
- 上下文组装策略不统一，简历、知识库、历史记录等输入难以稳定拼装
- 领域能力与运行时能力边界不够清楚，容易把 UI 或 loop 设计混入本阶段

## 3. 本阶段交付物

- 面向 interview 场景的读型或分析型工具集合
- 统一的 context assembly 策略与装配边界
- 简历、知识库、历史面试、复盘等核心 domain context 的组装路径
- 可被单 Agent 直接消费的领域能力层

## 4. 任务拆分

- [S3-01：Interview Context Tools](../agent-tasks/s3-task-01-interview-context-tools.md)
- [S3-02：Context Assembly Policy](../agent-tasks/s3-task-02-context-assembly-policy.md)
- [S3-03：Tool Output Normalization](../agent-tasks/s3-task-03-tool-output-normalization.md)

## 5. 进入条件 / 依赖关系

- 必须先完成 Stage 2，确保 Trace、Guardrails、Eval 已经可用
- 本阶段只承接 interview domain tooling 与 context assembly
- Stage 4 会消费本阶段产出的 domain 能力做 workbench / demo 展示
- Stage 5 如需多步能力，也应建立在本阶段的领域能力之上

## 6. 不在本阶段范围内

- Agent Workbench 页面与 demo surface 设计
- 完整单 Agent 的产品化演示收口
- bounded loop、step budget、handoff、subagent
- 多 Agent 编排或更高阶自治框架

## 7. 阶段完成标准

- interview 关键场景已具备足够的 domain tooling 支撑
- 上下文组装路径清晰且可复用，不再依赖临时拼接
- 单 Agent 已能稳定拿到所需领域上下文，但本阶段仍不要求提供完整 demo surface

## 8. 建议留存的证据

- 一组 interview 领域固定任务，覆盖简历、知识库、历史记录等主要上下文来源
- 上下文组装前后对比，例如 prompt 长度、裁剪情况、当前请求是否完整保留
- 工具调用质量对比，例如工具命中率、重复调用次数、无效工具调用比例
- 输出归一化前后对比，例如回答层与调试层字段是否稳定、一致
- 具体记录方式可参考 [Agent Evidence Playbook](../agent-evidence-playbook.md)
