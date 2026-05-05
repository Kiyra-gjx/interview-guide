# Stage 5 Recovery Set 使用说明

## 1. 这份说明解决什么问题

这套入口把 approval 恢复、trace 收尾、budget exhausted 和 handoff 边界等恢复语义，从零散测试收口成一套固定样本集。

它关注的不是“功能有没有”，而是：

- 本该失败时是否还在继续跑
- 本该收尾时是否错误重放副作用
- 本该读 trace 终态时是否还在相信陈旧消息

## 2. 当前固定样本覆盖什么

当前最小 Recovery Set 固定覆盖 9 个场景：

1. reject pending approval
2. expire stale pending approval
3. replay block after started execution
4. recover from trace terminal reply
5. approval resume failure
6. stale turn explicit failure
7. budget exhausted terminal trace
8. reject handoff on single step
9. recover handoff success without degraded terminal

## 3. 如何运行

```powershell
./gradlew.bat :app:agentStage5RecoveryEval
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage5-recovery-eval.ps1
```

运行完成后，会在 `app/build/reports/agent-eval/` 看到：

- `stage-5-recovery-set-report.json`
- `stage-5-recovery-set-report.md`

## 4. 报告里看什么

当前报告至少包含这些核心指标：

- 恢复正确率
- wrongStateContinued 数量
- replayedSideEffect 数量
- recoveryType 覆盖数

## 5. 如何做改动前 / 改动后对比

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-5-recovery-set-report.json `
  app/build/reports/agent-eval/baselines/stage-5-recovery-set-before-change.json
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage5-recovery-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-5-recovery-set-before-change.json
```

脚本会额外生成：

- `app/build/reports/agent-eval/stage-5-recovery-set-diff.md`

## 6. 边界

- 这套评测关注的是恢复语义和副作用边界，不是业务成功率
- 即使恢复正确率更高，也不自动代表模型更强
- 真正写进简历时，要配合固定样本、原始记录和 summary 一起使用
