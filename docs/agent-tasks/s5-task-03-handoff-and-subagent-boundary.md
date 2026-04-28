# S5-03：Handoff and Subagent Boundary

## 0. 任务状态

- 状态：已完成（首版）
- 当前定位：Stage 5 的边界治理任务
- 前置依赖：S5-01、S5-02 已完成

## 1. 任务目标

只在收益明确时引入 handoff / subagent，并把它们限制在可解释、可观测、可回收的边界内。

## 2. 要解决的问题

- handoff / subagent 很容易被当作“更像 Agent”的装饰，而不是实际收益点
- 如果没有职责边界与所有权定义，多执行体会快速抬高复杂度
- 主执行体与被委派执行体之间的 trace、审批、失败语义不清楚时，系统很难维护

## 3. 本任务范围

- handoff / subagent 适用条件
- 主执行体与子执行体的职责边界
- 读写集合、结果回传与 trace 契约
- 与审批、预算、失败语义的关系

## 4. 主要改动点

- handoff / subagent 决策策略
- orchestrator 与 delegated unit 的交互边界
- trace / metrics / 审批状态扩展
- 与运行时策略相关的文档和测试样例

## 4.1 当前已落地范围

- 决策层已扩展 `shouldDelegate / delegateTask / delegateReason / delegateExpectedOutput`，由主执行体先做本地校验，再决定是否进入委派路径
- handoff 当前只支持“受控只读委派”：委派单元不能调用工具、不能修改外部状态、不能继续派生新的子执行体
- handoff 只允许在 `runtimeConfig.multiStepEnabled=true` 的 bounded loop 中触发；单步路径会显式拒绝并写入 `HANDOFF_NOT_ALLOWED`
- 单个 turn 最多只允许一次 handoff，且必须保留后续整合步数；否则会被本地边界拒绝
- 委派结果不会新建独立 session/turn，而是回投为 `AgentToolResult`，继续复用现有 memory、trace、workbench 与 demo narrative 收口链路
- 委派成功、拒绝、失败分别以 `delegation_result`、`delegation_rejected`、`delegation_failed` 写入 trace；其中成功 handoff 只表示 step 完成，不写 turn 级 terminal payload
- 前端已把 `subagent_handoff` 识别为 internal trace marker，不再参与“业务工具命中 / 工具计数”叙事

## 5. 风险与边界

- 不引入 uncontrolled multi-agent swarm
- 不为了概念完整性而委派立即阻塞主链路、收益不清晰的工作
- handoff 后的结果必须能回到主链路并保持可解释性

## 6. 完成标准

- handoff / subagent 何时允许、何时不允许有明确规则
- 主执行体与子执行体的职责、结果回传与失败处理边界清晰
- 复杂度增加能够被明确说明，且收益可被验证

当前实现状态：

- 前三项主体能力已落地
- “收益可被验证”仍需要继续依赖固定样例、正反例与 benchmark 证据补强

## 7. 验证要求

- 至少有一组正例和反例说明何时应该、何时不应该 handoff
- handoff 后的 trace 能说明委派原因、委派范围与最终结果
- 失败、超时或被拒绝时，主链路仍保持可回收、可解释

当前已具备的验证样例：

- 正例：多步模式下的只读委派成功返回后，主执行体继续基于更新后的 memory 做下一步决策
- 反例：单步路径下请求 handoff，会被显式拒绝并写入 `HANDOFF_NOT_ALLOWED`
- 回归样例：委派显式 `nextFocus` 会真正写回 memory；成功 handoff step 不再被误写成 `DEGRADED` terminal；前端不会把 `subagent_handoff` 误讲成业务工具
