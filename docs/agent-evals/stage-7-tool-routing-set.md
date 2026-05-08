# Stage 7 Tool Routing Set 使用说明

## 1. 评测目标

`stage-7-tool-routing-set` 用固定样本验证 Agent 工具决策契约是否稳定，包括：

- 工具选择是否正确
- 参数补齐和保留是否正确
- 非法调用是否拒绝
- 高风险动作是否进入审批

## 2. 覆盖范围

- `20` 条固定 case
- 路由类型：`tool execution`、`degraded rejection`、`direct reply`、`waiting approval`
- 核心指标：`toolSelectionAccuracy`、`paramAccuracy`、`rejectionAccuracy`、`directReplyAccuracy`、`approvalRoutingAccuracy`、`unexpectedToolExecutionCount`
- 决策输入模式：固定 `AgentDecisionDTO` 样本（用于验证本地路由契约，不用于衡量线上模型意图分类准确率）

## 3. 运行方式

```powershell
./gradlew.bat :app:agentStage7ToolRoutingEval
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-tool-routing-eval.ps1
```

说明：

- 直接跑 Gradle task 只会生成 `report.json` 和 `report.md`。
- `diff`、evidence 归档、baseline 更新由 runner 脚本负责。

## 4. 输出文件

- `app/build/reports/agent-eval/stage-7-tool-routing-set-report.json`
- `app/build/reports/agent-eval/stage-7-tool-routing-set-report.md`
- `app/build/reports/agent-eval/stage-7-tool-routing-set-diff.md`
- `docs/evidence/agent-quantification/stage-7-tool-routing-set/reports/stage-7-tool-routing-set-report.json`
- `docs/evidence/agent-quantification/stage-7-tool-routing-set/reports/stage-7-tool-routing-set-report.md`
- `docs/evidence/agent-quantification/stage-7-tool-routing-set/reports/stage-7-tool-routing-set-diff.md`
- `docs/evidence/agent-quantification/stage-7-tool-routing-set/baselines/stage-7-tool-routing-set-baseline-2026-05-08.json`

## 5. 基线对比

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-tool-routing-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-7-tool-routing-set-before-change.json
```

更新归档基线：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-tool-routing-eval.ps1 -UpdateBaseline
```

## 6. 说明边界

- 这不是公开 benchmark。
- 这组通过率只说明固定样本下的本地回归稳定性。
- 不应把该结果直接等同于线上全量流量表现。
