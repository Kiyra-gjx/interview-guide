# Stage 7 Injection Safety Set 使用说明

## 1. 评测目标

`stage-7-injection-safety-set` 用固定外部内容样例验证 Agent 不会被知识库文档、简历、工具 observation、检索结果和历史面试内容中的恶意指令污染。

## 2. 覆盖范围

- 知识库文档注入
- 简历文本注入
- 工具结果注入
- 审批绕过话术
- 检索结果投毒
- 历史内容注入
- 工具名劫持
- 提示词泄露请求
- 工具参数走私

## 3. 运行方式

```powershell
./gradlew.bat :app:agentStage7InjectionSafetyEval
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-injection-safety-eval.ps1
```

## 4. 输出文件

- `app/build/reports/agent-eval/stage-7-injection-safety-set-report.json`
- `app/build/reports/agent-eval/stage-7-injection-safety-set-report.md`
- `docs/evidence/agent-quantification/stage-7-injection-safety-set/reports/stage-7-injection-safety-set-report.json`
- `docs/evidence/agent-quantification/stage-7-injection-safety-set/reports/stage-7-injection-safety-set-report.md`
- `docs/evidence/agent-quantification/stage-7-injection-safety-set/reports/stage-7-injection-safety-set-diff.md`
- `docs/evidence/agent-quantification/stage-7-injection-safety-set/baselines/stage-7-injection-safety-set-baseline-2026-05-08.json`

核心指标包括 `attackBlockedCases`、`secretLeakCount`、`approvalBypassCount`、`unsafeToolExecutionCount`、`unsafeDirectAnswerCount`、`modelPollutionSimulatedCases` 和 `runtimeBoundaryEvidenceCases`。

## 5. 变更基线

对比基线时先复制当前报告作为 baseline，再执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-injection-safety-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-7-injection-safety-set-before-change.json
```

更新归档基线：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-injection-safety-eval.ps1 -UpdateBaseline
```

## 6. 说明边界

- 这不是公开安全基准。
- 这里验证的是项目内固定样例的可回归性。
- 不应把本地通过率等同于生产环境的安全保证。
