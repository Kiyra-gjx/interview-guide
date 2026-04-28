# Agent Quantification Evidence

## 1. 目录目标

这个目录只做一件事：把 Agent 项目的量化证据沉淀成**固定样本、原始记录、汇总结果、报告和 trace**，避免结果散落在 `build/`、聊天记录和临时截图里。

## 2. 标准目录结构

每个 suite 使用一个独立目录：

- `docs/evidence/agent-quantification/<suiteId>/README.md`
- `docs/evidence/agent-quantification/<suiteId>/sample-set.md`
- `docs/evidence/agent-quantification/<suiteId>/raw-results.md`
- `docs/evidence/agent-quantification/<suiteId>/summary.md`
- `docs/evidence/agent-quantification/<suiteId>/baselines/`
- `docs/evidence/agent-quantification/<suiteId>/reports/`
- `docs/evidence/agent-quantification/<suiteId>/traces/`

## 3. 命名约定

### 3.1 suiteId

统一使用 kebab-case，例如：

- `stage-2-fixed-regression`
- `stage-3-context-set`
- `stage-3-memory-set`
- `stage-5-benchmark`

### 3.2 报告文件

统一使用下面 3 类命名：

- `<suiteId>-report.json`
- `<suiteId>-report.md`
- `<suiteId>-diff.md`

### 3.3 baseline 文件

统一使用：

- `<suiteId>-baseline-YYYY-MM-DD.json`

## 4. 最小证据要求

任意一组数据，至少要能回到下面 6 项：

- suiteId
- 样本集名称
- 控制变量
- 原始结果
- 汇总结果
- 证据位置

缺任何一项，都不要进简历。

## 5. 初始化方式

使用脚本初始化一个新 suite：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/init-agent-evidence-suite.ps1 `
  -SuiteId stage-5-benchmark `
  -Capability stage-5-benchmark `
  -SampleSetName Stage5BenchmarkSet `
  -SuiteType benchmark
```

## 6. 当前已接入入口

- Stage 2 固定回归：`scripts/run-agent-stage2-eval.ps1`
- Stage 5 benchmark：`scripts/run-agent-stage5-benchmark.ps1`

## 7. 使用原则

- `build/reports/` 是运行产物，不是长期证据归档目录
- `docs/evidence/agent-quantification/` 才是长期可复核证据目录
- 先跑评测，再把关键 baseline / diff / trace 复制或整理到这里
