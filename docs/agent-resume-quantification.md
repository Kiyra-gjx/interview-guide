# Agent 项目量化操作手册

## 1. 文档目标

这份文档只解决一件事：

- 先把 Agent 项目的关键数据量化出来
- 再决定哪些数据值得写进简历

这不是简历成稿，也不是项目宣传稿。

这里默认你要把项目写成 `Agent Harness / Agent Runtime / Agent Engineering` 方向，而不是普通后端项目。

## 2. 先统一原则

### 2.1 最终简历里不要写什么

不要把下面这些东西当成“结果”写进简历：

- 几个模块
- 几个测试类
- 几张表
- 几个文件夹

这些只能证明你写了代码，不能证明你做成了什么。

### 2.2 最终简历里应该优先写什么

优先写这 4 类数据：

- 固定样本集上的通过率、完成率、压缩率、命中率
- 改造前后对比数据
- 恢复正确率、重复调用下降、预算内收口等机制收益
- 能力覆盖数据
  这里的“覆盖”指场景覆盖、语义覆盖、状态覆盖，不是类文件数量

### 2.3 参考样例和公开优秀简历后，抽出来的共性

结合你给的样例，以及我查到的牛客公开技术简历经验，可以把优秀项目描述总结成下面几条：

- 每一条只讲一类能力，不把架构、性能、安全、评测揉成一句话
- 每一条都尽量有 `样本数 + 指标 + 结果 + 边界`
- 优先写“机制带来了什么可验证收益”，而不是“我实现了什么组件”
- 结果数据如果不是线上业务数据，就明确说它来自固定回归集、benchmark、trace 或离线评测
- 技术项目里的量化重点通常不是 GMV、转化率，而是稳定性、覆盖度、正确率、压缩率、延迟、重复率、恢复率

这也是为什么下面的量化方案会按“能力块”而不是“代码结构”来设计。

## 3. 这份 Agent 项目应该按哪 6 块量化

为了最后能写出像你给的样例那种项目经历，建议把数据拆成 6 块来采：

1. Agent Harness 架构设计
2. 长上下文治理
3. 结构化记忆系统
4. 任务恢复机制
5. 工具安全与运行治理
6. 评测与审计闭环

每一块都单独建样本集、单独出表、单独留证据。

## 4. 量化前先建 5 个固定样本集

先不要急着跑数据。先把样本集建好，否则后面的数字都不稳定。

建议最少准备这 5 组：

### 4.1 Context Set

用途：

- 量化上下文压缩率
- 检查关键 section 是否被误裁剪

建议规模：

- 12 组请求

建议覆盖：

- 短请求
- 长请求
- 带 resume 绑定
- 带 knowledge base 绑定
- 多轮 follow-up
- 明显超预算请求

### 4.2 Memory Set

用途：

- 量化结构化记忆收益
- 检查 follow-up 阶段是否还在重复读同一事实

建议规模：

- 12 组任务

建议覆盖：

- 同一主题多轮追问
- 需要复用已确认事实
- 需要复用上一步工具结果
- 容易重复读取同一资源的任务

### 4.3 Recovery Set

用途：

- 量化 checkpoint / approval / trace 恢复正确率

建议规模：

- 8 到 10 组场景

建议覆盖：

- 审批后继续执行
- 审批拒绝后降级收口
- 过期 turn 显式失败
- trace 已完成时从 trace 收尾
- trace 运行中时阻断重放
- workspace 变化或上下文漂移场景

### 4.4 Safety Set

用途：

- 量化 guardrail、approval、工具边界的治理效果

建议规模：

- 10 组场景

建议覆盖：

- 输入拦截
- 高风险工具进入待审批
- 审批通过后执行
- 审批拒绝后收口
- 工具参数缺失或非法
- replay blocked

### 4.5 Eval Set

用途：

- 量化固定回归和预算内完成率

建议规模：

- 现有 Stage 2 固定回归 5 组
- 后续补 Stage 5 多步任务 10 组

## 5. 六块能力分别怎么量化

## 5.1 Agent Harness 架构设计

这块不要写“有多少类”，而要量化“这个 runtime 已经覆盖了哪些关键执行语义”。

### 建议指标

- 关键执行路径覆盖数
- 关键终态覆盖数
- 关键恢复路径覆盖数
- 关键审计产物覆盖数

### 推荐口径

最少统计下面几类是否有固定样本覆盖：

- `DIRECT_REPLY`
- `TOOL_CALL`
- `WAITING_APPROVAL`
- `APPROVAL_REJECTED`
- `ERROR`
- `RECOVERY_FROM_TRACE`
- `REPLAY_BLOCKED`

### 怎么采

1. 基于 `Eval Set` 和 `Recovery Set` 整理场景矩阵。
2. 对每个场景记录：
   - 触发路径
   - 终态
   - 是否有 trace
   - 是否有 approval
   - 是否可恢复
3. 输出一张覆盖表，不需要先追求“多”，先追求“闭环完整”。

### 这块最后适合写成什么

不是写：

- “设计了 Agent 架构”

而是写：

- “将 Agent 主链路拆成若干固定执行语义，并用固定样本覆盖了直接回复、工具执行、待审批、拒绝收口、错误终止和恢复阻断等关键路径”

## 5.2 长上下文治理

这块是最适合做成硬数据的。

### 当前代码入口

- `app/src/main/java/interview/guide/modules/agent/service/AgentContextAssemblyService.java`
- `app/src/test/java/interview/guide/modules/agent/service/AgentContextAssemblyServiceTest.java`

### 建议指标

- 裁剪前长度
- 裁剪后长度
- 平均压缩率
- 最高压缩率
- 关键 section 保留率
- 请求被裁坏次数

### 关键 section

至少检查下面几项：

- `latest_user_message`
- `goal`
- `memory_state`
- `resource_bindings`

### 计算公式

- 压缩率 = `(裁剪前长度 - 裁剪后长度) / 裁剪前长度`
- 保留率 = `保留关键 section 数 / 应保留关键 section 数`

### 怎么采

1. 用 `Context Set` 固定 12 组请求。
2. 给每组请求记录：
   - 原始上下文总长度
   - 最终装配长度
   - 被截断的 section
   - 被省略的 section
   - 是否影响当前请求理解
3. 每组样本都保留原始记录，不只看平均值。

### 记录表字段

- caseId
- budget
- rawContextChars
- assembledChars
- compressionRate
- omittedSections
- truncatedSections
- requestBroken

### 这块最后适合写成什么

只有在数据采完后，才允许写成：

- “在 12 组固定上下文配置中，将平均 prompt 长度从 X 压到 Y，平均压缩率 Z%，最高压缩率 W%，且当前请求未被裁坏”

## 5.3 结构化记忆系统

这块的重点不是“有 memory”，而是“memory 有没有减少重复劳动”。

### 当前代码入口

- `app/src/main/java/interview/guide/modules/agent/service/AgentMemoryService.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentContextAssemblyService.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentTraceService.java`

### 建议指标

- follow-up 阶段重复读取同一资源次数
- 重复确认同一事实次数
- 平均每任务工具调用数
- memory 命中后减少的额外工具调用数

### 怎么采

1. 用 `Memory Set` 固定 12 组 follow-up 任务。
2. 对每组任务记录：
   - 总 turn 数
   - 工具调用总数
   - 重复工具调用数
   - 重复确认事实数
   - memory 是否足够支撑下一步
3. 至少保留一组 before/after 对比。

### 关键判断标准

如果 Agent 已经拿到某个事实，但在 follow-up 阶段还反复读取同一文件、重复确认同一结论，这块就不能算量化成功。

### 记录表字段

- caseId
- turnCount
- toolCallCount
- repeatedToolCalls
- repeatedFactChecks
- extraCallsAfterMemoryReady
- comments

### 这块最后适合写成什么

- “在 12 个记忆依赖任务中，将 follow-up 阶段重复读取资源次数从 X 次降到 Y 次，不再需要额外工具调用去重新确认已知事实”

## 5.4 任务恢复机制

这块是 Agent 工程项目里很关键的一条，因为它能区分“能跑”和“可恢复”。

### 当前代码入口

- `app/src/main/java/interview/guide/modules/agent/service/AgentApprovalRuntimeService.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentSessionService.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentOrchestrator.java`

### 建议指标

- 恢复场景覆盖数
- 恢复正确率
- 误恢复次数
- 重复副作用执行次数
- 旧状态继续执行次数

### 怎么采

1. 用 `Recovery Set` 固定 8 到 10 个恢复场景。
2. 每个场景记录：
   - 恢复前状态
   - 恢复触发方式
   - 恢复后终态
   - 是否错误沿用旧状态
   - 是否发生重复执行
3. 重点看两类错误：
   - 本该失败却继续跑
   - 本该只收尾却重放副作用

### 记录表字段

- caseId
- recoveryType
- expectedTerminalState
- actualTerminalState
- wrongStateContinued
- replayedSideEffect
- passed

### 这块最后适合写成什么

- “覆盖 10 个固定恢复场景，恢复正确率 X%，旧状态误续跑 0 次，重复副作用执行 0 次”

## 5.5 工具安全与运行治理

这块重点不是“做了审批”，而是“高风险动作是否真的被拦住了”。

### 当前代码入口

- `app/src/main/java/interview/guide/modules/agent/service/AgentApprovalRuntimeService.java`
- `app/src/main/java/interview/guide/modules/agent/guardrail/AgentGuardrailService.java`
- `app/src/main/java/interview/guide/modules/agent/model/AgentExecutionSummaryDTO.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentMetricsService.java`

### 建议指标

- 高风险工具待审批命中率
- guardrail 命中样本数
- 审批通过后执行正确率
- 审批拒绝后降级收口率
- replay blocked 命中数
- 参数非法拦截率

### 现有可直接拿到的基线

当前仓库已有 Stage 2 固定回归：

- 样本数：5
- 截至 `2026-04-28` 回归通过率：`100%`
- guardrail 命中样例数：`3`
- approval 状态覆盖：`APPROVED / PENDING / REJECTED`
- 平均延迟：`539 ms`

这些数据适合当“当前基线”，但不够当最终简历亮点。后面要继续补 `Safety Set`。

### 直接运行方式

```powershell
./gradlew.bat :app:agentStage2Eval --rerun-tasks
```

或：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage2-eval.ps1
```

产物位置：

- `app/build/reports/agent-eval/stage-2-regression-report.json`
- `app/build/reports/agent-eval/stage-2-regression-report.md`

### 这块最后适合写成什么

- “在固定回归任务中保持 100% 通过率，并验证高风险动作不会绕过审批直接执行”

如果后面补完 `Safety Set`，可以继续升级成：

- “在 N 个固定安全场景中，高风险工具待审批命中率 100%，审批拒绝后全部正确降级收口，未出现绕过审批直接执行”

## 5.6 评测与审计闭环

这块决定你最后能不能把所有数据串成一套可信叙事。

### 当前代码入口

- `app/src/test/java/interview/guide/modules/agent/eval/AgentStage2RegressionEvalTest.java`
- `scripts/run-agent-stage2-eval.ps1`
- `docs/agent-evals/stage-2-regression.md`

### 建议指标

- 固定回归通过率
- 预算内完成率
- verifier 或人工验收通过率
- 平均延迟
- 场景覆盖数
- before/after 差异结果

### 当前能直接做的事

1. 跑 Stage 2 固定回归。
2. 保存一份基线报告。
3. 带基线重跑，产出 diff。

命令：

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-2-regression-report.json `
  app/build/reports/agent-eval/baselines/stage-2-before-change.json
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage2-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-2-before-change.json
```

diff 产物：

- `app/build/reports/agent-eval/stage-2-regression-diff.md`

### 后续要补的事

如果你想写出接近样例那种“100% 预算内完成率”“100% verifier 通过率”，还要再补一套 Stage 5 固定任务集，重点量化：

- 多步任务预算内完成率
- stop reason 分布
- terminal state 分布
- recoverable 场景分布

### 这块最后适合写成什么

- “形成固定 benchmark、对照实验和运行审计三类评测路径，用于验证运行时合同稳定性、上下文治理收益和恢复边界正确性”

## 6. 现在就能直接跟着做的量化顺序

不要六块一起做。按下面顺序推进：

1. 先跑现有 Stage 2 回归，拿到第一组真实基线
2. 再补 `Context Set`，先做上下文压缩率
3. 再补 `Memory Set`，做重复调用和重复确认下降
4. 再补 `Recovery Set`，做恢复正确率
5. 再补 `Safety Set`，把审批和 guardrail 数据补完整
6. 最后补 `Stage 5` 多步任务，做预算内完成率

原因很简单：

- Stage 2 现在已经能直接跑
- 上下文和记忆最容易先出数据
- 恢复和多步预算通常最费时间，放后面

## 7. 每次采数都必须记录的字段

无论是哪一块，至少都要记录下面 6 项：

- 样本集名称
- 样本数
- 控制变量
  包括模型、budget、开关、是否开启 multi-step、是否带 approval
- 原始结果
- 汇总结果
- 证据位置

如果没有这 6 项，这组数据不要进简历。

## 8. 证据应该放在哪里

建议统一放在 `docs` 下，别分散到聊天记录里。

推荐至少保留：

- 一份样本清单
- 一份原始结果表
- 一份汇总结果表
- 一份评测报告或 diff
- 一组代表性 trace 截图或接口输出

如果你后面要继续沉淀，可以新建：

- `docs/evidence/agent-quantification/`

按能力块再拆子目录。

## 9. 数据采完后，怎么判断能不能写进简历

满足下面 4 条，才允许写：

1. 有固定样本集
2. 有统一口径
3. 有原始记录
4. 有可复核证据

只要缺一条，就先别写。

## 10. 数据采完后，简历句子的生成公式

这里只给公式，不给成稿。

### 公式 1：机制收益型

`围绕 [能力块] 设计 [机制]，在 [样本数] 组 [任务/场景] 中，将 [指标] 从 [X] 降到 [Y] / 提升到 [Z]，并保持 [边界条件]`

### 公式 2：覆盖闭环型

`围绕 [能力块] 构建固定 [回归/恢复/安全] 场景集，覆盖 [关键路径]，在 [日期] 的基线评测中达到 [结果]`

### 公式 3：运行治理型

`围绕 [高风险动作/多步执行/恢复语义] 补齐 [审批/trace/guardrail/budget] 机制，在 [样本数] 组场景中验证 [正确率/命中率/完成率]`

注意：

- 这里的 `[样本数]`、`[X]`、`[Y]`、`[Z]` 必须来自前面的记录表
- 不要先写句子再倒推数据

## 11. 当前这份文档的使用方式

你接下来应该这样用：

1. 先按第 4 节建样本集
2. 再按第 5 节逐块采数据
3. 每采完一块，就把结果填进记录模板
4. 等至少完成 3 块高质量数据后，再开始写简历

如果你现在就写，只会回到“实现了某某机制”的空话。
