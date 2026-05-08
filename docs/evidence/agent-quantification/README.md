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
- Stage 2 Safety Set：`scripts/run-agent-stage2-safety-eval.ps1`
- Stage 3 Context Set：`scripts/run-agent-stage3-context-eval.ps1`
- Stage 3 Memory Set：`scripts/run-agent-stage3-memory-eval.ps1`
- Stage 5 Recovery Set：`scripts/run-agent-stage5-recovery-eval.ps1`
- Stage 5 benchmark：`scripts/run-agent-stage5-benchmark.ps1`
- Stage 7 Injection Safety Set：`scripts/run-agent-stage7-injection-safety-eval.ps1`

## 7. 当前已填实的证据包

- `stage-2-fixed-regression`：已有报告、diff、baseline 归档
- `stage-2-safety-set`：已有固定样本、真实 report、diff、baseline 与可写简历句子
- `stage-3-context-set`：已有固定样本、真实 report、diff、baseline 与可写简历句子
- `stage-3-memory-set`：已有固定样本、真实 report、diff、baseline 与可写简历句子
- `stage-5-recovery-set`：已有固定样本、真实 report、diff、baseline 与可写简历句子
- `stage-5-benchmark`：已有固定样本、原始结果、summary、resume pack、报告与 diff 归档
- `stage-7-rag-retrieval-set`：已有固定 query、原始结果、summary、report、baseline 与 diff 归档
- `stage-7-injection-safety-set`：已有固定 case、原始结果、summary、report、baseline 与 diff 归档

## 8. 使用原则

- `build/reports/` 是运行产物，不是长期证据归档目录
- `docs/evidence/agent-quantification/` 才是长期可复核证据目录
- 先跑评测，再把关键 baseline / diff / trace 复制或整理到这里
