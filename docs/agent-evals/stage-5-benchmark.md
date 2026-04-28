# Stage 5 Benchmark 使用说明

## 1. 这份说明解决什么问题

Stage 5 已经有多步预算、终态语义和 handoff 边界实现，但之前缺少一条像 Stage 2 那样固定、可重复、可留档的 benchmark 入口。

这份说明定义的不是“最终性能结论”，而是 Stage 6 `S6-01` 需要的统一报告基础。

## 2. 当前固定 benchmark 覆盖什么

当前最小 benchmark 固定覆盖 4 个 Stage 5 关键场景：

1. `bounded_handoff_success`
   验证多步模式下只读委派可以回到主链路并成功收口。
2. `handoff_rejected_single_step`
   验证单步路径下 handoff 会被边界显式拒绝，而不是偷偷扩散执行。
3. `step_budget_exhausted`
   验证多步预算耗尽后会以 `EXHAUSTED` 终态收口，而不是继续执行。
4. `approval_replay_blocked`
   验证审批通过后的恢复场景如果状态已不明确，会进入 replay blocked 收口，而不是重复副作用执行。

这 4 个样例不是 Stage 5 的全部收益证明，但足够作为统一报告入口和后续扩样的基础。

## 3. 如何运行

### 3.1 直接跑 benchmark

```powershell
./gradlew.bat :app:agentStage5Benchmark
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage5-benchmark.ps1
```

运行完成后，会在 `app/build/reports/agent-eval/` 看到：

- `stage-5-benchmark-report.json`
- `stage-5-benchmark-report.md`

## 4. 报告里看什么

当前报告至少包含这些核心指标：

- `passedCases`
- `multiStepCases`
- `averageExecutedSteps`
- `averageLatencyMs`
- `maxLatencyMs`
- `exhaustedCases`
- `handoffAcceptedCases`
- `handoffRejectedCases`
- `replayBlockedCases`
- `stopReason` 分布
- `terminalState` 分布

同时会保留 case 级结果，方便回看某一个 benchmark case 的 stop reason、terminal state 和 executed steps 是否变化。

## 5. 如何做改动前 / 改动后对比

和 Stage 2 一样，先保存 baseline，再重跑 diff：

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-5-benchmark-report.json `
  app/build/reports/agent-eval/baselines/stage-5-benchmark-before-change.json
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage5-benchmark.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-5-benchmark-before-change.json
```

脚本会额外生成：

- `app/build/reports/agent-eval/stage-5-benchmark-diff.md`

## 6. 边界

- 这不是线上性能 benchmark
- 这不是最终的 Stage 5 收益结论
- 这是一条固定、可重复、可留档的最小 benchmark 入口

如果后续要把数据写进简历，还需要继续补样本集、原始记录和证据归档，而不是只看这份报告
