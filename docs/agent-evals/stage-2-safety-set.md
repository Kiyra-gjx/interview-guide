# Stage 2 Safety Set 使用说明

## 1. 这份说明解决什么问题

这套入口把 Stage 2 的安全与运行治理语义从“零散单测”收口成固定样本集，用来量化：

- 高风险动作是否真的进入审批
- 审批拒绝后是否正确降级收口
- 输入/输出 guardrail 是否留下了可解释证据
- approval replay blocked 是否真的阻断重复副作用

## 2. 当前固定样本覆盖什么

当前最小 Safety Set 固定覆盖 10 个场景：

1. input guardrail rejection
2. waiting for approval
3. approval rejected
4. approval approved execution
5. invalid tool decision degrade
6. output guardrail direct reply
7. output guardrail tool reply
8. missing required input
9. approval replay blocked
10. stale turn failure

## 3. 如何运行

```powershell
./gradlew.bat :app:agentStage2SafetyEval
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage2-safety-eval.ps1
```

运行完成后，会在 `app/build/reports/agent-eval/` 看到：

- `stage-2-safety-set-report.json`
- `stage-2-safety-set-report.md`

## 4. 报告里看什么

当前报告至少包含这些核心指标：

- approval required 命中率
- 审批拒绝后降级收口率
- guardrail 命中样例数
- direct execution bypassed 数量
- replay blocked 样例数
- approval 状态分布

## 5. 如何做改动前 / 改动后对比

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-2-safety-set-report.json `
  app/build/reports/agent-eval/baselines/stage-2-safety-set-before-change.json
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage2-safety-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-2-safety-set-before-change.json
```

脚本会额外生成：

- `app/build/reports/agent-eval/stage-2-safety-set-diff.md`

## 6. 边界

- 这套评测关注的是运行治理边界，不是业务任务成功率
- `guardrailHitCases` 不能直接写成“系统表现更好”
- 真正写进简历时，要配合固定样本、原始记录和 summary 一起使用
