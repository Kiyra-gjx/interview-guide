# Stage 3 Memory Set 使用说明

## 1. 这份说明解决什么问题

这套入口不是用来证明模型“更聪明”，而是用来量化 memory 契约本身有没有真的减少重复劳动。

它关注的核心是三件事：

- memory phase 是否稳定推进
- summary / facts / nextFocus 写回前是否被统一归一化
- follow-up 或委派回写后，系统是否还会重复读同一资源

## 2. 当前固定样本覆盖什么

当前最小 Memory Set 固定覆盖 9 组场景：

1. phase mapping
2. fact dedup and cap
3. summary and fact normalization
4. explicit next focus
5. legacy fact normalization
6. preserve short facts
7. follow-up reuse known fact
8. follow-up reuse tool result
9. delegated memory writeback

其中 `MEM-07` 到 `MEM-09` 会带一条固定 no-reuse 对照，用来比较 memory 命中前后的重复调用差异。

## 3. 如何运行

```powershell
./gradlew.bat :app:agentStage3MemoryEval
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage3-memory-eval.ps1
```

运行完成后，会在 `app/build/reports/agent-eval/` 看到：

- `stage-3-memory-set-report.json`
- `stage-3-memory-set-report.md`

## 4. 报告里看什么

当前报告至少包含这些核心指标：

- 平均 `toolCallCount`
- `repeatedToolCallsBefore / After`
- `repeatedFactChecksBefore / After`
- `extraCallsAfterMemoryReady`

这里的 `Before` 不是线上历史数据，而是固定 no-reuse 对照路径；`After` 才是当前 memory 套件的实际目标路径。

## 5. 如何做改动前 / 改动后对比

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-3-memory-set-report.json `
  app/build/reports/agent-eval/baselines/stage-3-memory-set-before-change.json
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage3-memory-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-3-memory-set-before-change.json
```

脚本会额外生成：

- `app/build/reports/agent-eval/stage-3-memory-set-diff.md`

## 6. 边界

- 这套评测关注的是 memory 契约和 follow-up 复用，不是最终回答质量
- `repeatedToolCallsBefore -> After` 只说明固定样本里的重复劳动变化，不能直接写成线上效率提升
- 真正写进简历时，要连同固定样本、原始记录、summary 和 diff 一起使用
