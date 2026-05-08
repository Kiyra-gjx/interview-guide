# Stage 7 RAG Retrieval Set 使用说明

## 1. 评测目标

`stage-7-rag-retrieval-set` 用固定 query 验证 RAG 检索链路是否能返回可解释的 source/section/chunk 证据，并区分“检索命中”和“最终回答是否基于证据”。它也覆盖 no-answer 场景，避免知识库没有答案时继续生成扩展内容。

## 2. 当前覆盖范围

- `17` 条可回答 query：概念检索、术语精确检索、grounded answer 校验
- `3` 条 no-answer query：知识库范围外必须拒答，其中 `1` 条覆盖有弱相关候选文档但缺少 precision token 的拒答路径
- 指标：`top1HitRate`、`top3HitRate`、`answerGroundedRate`、`noAnswerRejectionRate`、`hallucinationCount`

## 3. 如何运行

```powershell
./gradlew.bat :app:agentStage7RagRetrievalEval
```

或：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-rag-retrieval-eval.ps1
```

运行完成后会生成并归档：

- `app/build/reports/agent-eval/stage-7-rag-retrieval-set-report.json`
- `app/build/reports/agent-eval/stage-7-rag-retrieval-set-report.md`
- `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/reports/stage-7-rag-retrieval-set-report.json`
- `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/reports/stage-7-rag-retrieval-set-report.md`
- `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/baselines/stage-7-rag-retrieval-set-baseline-2026-05-08.json`

## 4. 如何做 baseline diff

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-7-rag-retrieval-set-report.json `
  app/build/reports/agent-eval/baselines/stage-7-rag-retrieval-set-before-change.json
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-rag-retrieval-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-7-rag-retrieval-set-before-change.json
```

默认运行不会覆盖 evidence baseline。只有确认当前结果要作为新的归档基线时，才显式追加：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-rag-retrieval-eval.ps1 -UpdateBaseline
```

脚本会额外生成并归档：

- `app/build/reports/agent-eval/stage-7-rag-retrieval-set-diff.md`
- `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/reports/stage-7-rag-retrieval-set-diff.md`

## 5. 边界

- 这不是线上 RAG 准确率评测。
- 这不是公开 benchmark。
- 当前 suite 使用固定 mock 检索结果，并让回答 mock 基于实际 RAG prompt/context 生成；目标是回归 `QueryDebugInfo`、source/section 命中、grounded answer 和 no-answer 契约。
