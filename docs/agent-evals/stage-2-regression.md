# Stage 2 Eval / Regression 使用说明

## 1. 这份说明解决什么问题

`S2-04` 的目标不是补一堆零散单测，而是建立一条团队可以重复执行、可以留档、可以做前后对比的离线评测入口。

当前最小基线已经落在：

- 固定样例集：`app/src/test/java/interview/guide/modules/agent/eval/AgentStage2RegressionEvalTest.java`
- Gradle 入口：`./gradlew.bat :app:agentStage2Eval`
- PowerShell 入口：`powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage2-eval.ps1`
- 报告输出目录：`app/build/reports/agent-eval/`

## 2. 当前固定样例覆盖什么

这套最小回归集固定包含 5 个 Stage 2 关键场景：

1. `tool_execution_success`
   验证审批通过后，工具可以按冻结输入安全执行并成功收口。
2. `input_guardrail_rejection`
   验证输入 guardrail 会把内部信息泄露请求降级拦截。
3. `waiting_for_approval`
   验证高风险工具不会直通执行，而是停在 `WAITING_APPROVAL`。
4. `approval_rejected`
   验证审批拒绝后 turn 会按降级终态收口。
5. `stale_turn_failure`
   验证过期 turn 会暴露明确错误，而不是伪装成成功回复。

这 5 个样例的目的不是覆盖全部业务细节，而是先覆盖 Stage 2 最核心的行为面：

- 成功
- 降级
- 待审批
- 错误
- guardrail 与 approval 的关键分布

## 3. 如何运行

### 3.1 直接跑固定回归集

```powershell
./gradlew.bat :app:agentStage2Eval
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage2-eval.ps1
```

运行完成后，会在 `app/build/reports/agent-eval/` 看到：

- `stage-2-regression-report.json`
- `stage-2-regression-report.md`

脚本入口会强制重跑 `:app:agentStage2Eval`，并校验 JSON / Markdown 报告都已经生成。直接运行 Gradle 任务时，`agentStage2Eval` 也会禁用 up-to-date 跳过，保证每次执行都会重新生成当前报告。

## 4. 报告里看什么

当前报告至少包含这些核心指标：

- 成功率
- 降级率
- 等待审批率
- 错误率
- 平均延迟
- 最大延迟
- guardrail 命中样例数
- approval 状态分布

同时还会保留逐样例结果，方便回看是哪一个 case 发生了变化。

## 5. 如何做改动前 / 改动后对比

最简单的做法：

1. 先跑一次，把当前 `stage-2-regression-report.json` 复制到一个不会被下一次运行覆盖的位置。
2. 改代码。
3. 再跑一次。
4. 用脚本对比独立保存的 baseline 和当前结果。

示例：

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-2-regression-report.json `
  app/build/reports/agent-eval/baselines/stage-2-before-change.json
```

或者：

```
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage2-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-2-before-change.json
```

脚本会额外生成：

- `app/build/reports/agent-eval/stage-2-regression-diff.md`

这个 diff 文件会直接比较：

- success / degraded / waitingApproval / error 四类核心比例
- 平均延迟和最大延迟
- guardrail 命中样例数
- approval 状态分布
- case 级结果变化

## 6. 如何新增样例

如果后续 Stage 2 增加了新的失败语义、guardrail 语义或 approval 语义，新增样例时按下面顺序做：

1. 在 `AgentStage2RegressionEvalTest` 的 `runFixedSuite(...)` 里补一个新 scenario。
2. 给这个 scenario 明确写出预期：
   `outcome / completionMode / turnStatus / approvalStatus / guardrailCount / errorCode`
3. 实现对应的 case 执行方法，优先复用已有 `AgentOrchestratorTest` 的 mock 风格，保持离线、稳定、可重复。
4. 重新跑 `:app:agentStage2Eval`，确认报告里出现新样例。
5. 如果它代表新的关键风险面，再把它写进这份文档。

## 7. 如何解释异常波动

建议按下面顺序判断：

1. 如果 case 级结果变化，优先当成行为回归处理，即使汇总比例没有变化。
2. 如果成功率、降级率、等待审批率、错误率有变化，优先检查对应 case 的实际 outcome。
3. 如果只有延迟波动，但行为结果没变，先重跑一遍，排除 Gradle/JIT 预热影响。
4. 如果 guardrail 命中数变化，优先检查是不是规则误拦截或漏拦截。
5. 如果 approval 状态分布变化，优先检查高风险动作是否被错误地下沉到了直通路径。
6. 如果逐样例里只有单个 case 变化，先看对应 note，再回到 trace / 单测场景定位。

## 8. 边界

这套 Stage 2 eval 有明确边界：

- 它不是功能测试替代品
- 它不是集成测试替代品
- 它不是 benchmark 平台

它关注的是：

- Stage 2 关键运行时语义有没有回归
- guardrail / approval 是否还在按预期工作
- 改动前后最关键的行为分布是否可比较、可留档、可解释
