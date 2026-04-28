# Stage 3 Context Set 使用说明

## 1. 这份说明解决什么问题

这套入口不是用来证明模型能力，而是用来量化 context assembly 机制本身：在固定 budget 和固定上下文来源下，系统如何裁剪、保留和解释各个 section。

## 2. 当前固定样本覆盖什么

当前最小 Context Set 固定覆盖 10 组场景，包括：

1. 多源上下文稳定优先级装配
2. 预算耗尽时先裁低优先级 section
3. budget 允许时保留完整 latest request 和 goal
4. 缺少绑定信息时回退到 memory goal
5. budget 统计与真实装配成本对齐
6. 只绑定 resume
7. 只绑定 knowledge base
8. follow-up 长 facts 截断
9. 明显超预算但关键 section 仍保留
10. 完全未绑定资源时的 explainable bindings

## 3. 如何运行

### 3.1 直接跑固定样本集

```powershell
./gradlew.bat :app:agentStage3ContextEval
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage3-context-eval.ps1
```

运行完成后，会在 `app/build/reports/agent-eval/` 看到：

- `stage-3-context-set-report.json`
- `stage-3-context-set-report.md`

## 4. 报告里看什么

当前报告至少包含这些核心指标：

- 平均原始上下文长度
- 平均装配后长度
- 平均压缩率
- 最高压缩率
- 关键 section 保留率
- `requestBroken` 数量

同时会保留逐 case 结果，方便回看每个场景到底省略了哪些 section、截断了哪些 section。

## 5. 如何做改动前 / 改动后对比

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-3-context-set-report.json `
  app/build/reports/agent-eval/baselines/stage-3-context-set-before-change.json
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage3-context-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-3-context-set-before-change.json
```

脚本会额外生成：

- `app/build/reports/agent-eval/stage-3-context-set-diff.md`

## 6. 边界

- 这套评测关注的是 context assembly 机制，不是最终回答质量
- 即使压缩率更高，也不自动代表回答质量更高
- 真正能否写进简历，还要结合固定样本集、原始记录和 summary 一起判断
