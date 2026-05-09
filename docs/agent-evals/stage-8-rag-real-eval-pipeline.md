# Stage 8 RAG Real Eval Pipeline 使用说明

## 1. 评测目标

`stage-8-rag-e2e` 对 RAG 核心管线做真实端到端评测（真实 embedding、pgvector 检索、LLM 生成和 LLM-as-judge；测试启动时隔离非 RAG 的 Redis/队列依赖），覆盖三个维度：

- **检索质量**：真实 embedding → pgvector 相似度搜索 → Recall@K、Hit Rate@K、MRR、nDCG@K
- **生成质量**：真实 LLM 基于检索上下文生成 → LLM-as-judge 评分 Correctness、Attribution、Completeness、Faithfulness、Readability，失败时回退到本地 rubric scorer
- **系统性能**：本地端到端延迟 P50/P95/P99

## 2. 前置条件

- PostgreSQL + pgvector 已启动（`docker-compose up -d postgres`）
- DashScope API key 已配置（`AI_BAILIAN_API_KEY` 环境变量）
- 显式开启真实评测：`APP_STAGE8_RAG_E2E_ENABLED=true`
- 评测数据集已准备（S8-01 完成）

## 3. 当前覆盖范围

- 固定评测集已落地 `30` 条 query：概念检索、术语检索、多文档综合、no-answer、weak-hit
- 每条 query 都在完整 Stage 7 corpus 上检索，不用 `expectedSources` 缩小检索范围
- 每条 query 有 graded relevance judgment（0-3 级）
- 每条可回答 query 有 reference answer
- 检索指标：`Recall@3/5/10`、`Hit Rate@3/5/10`、`MRR`、`nDCG@3/5/10`
- 生成指标：`Correctness`、`Attribution`、`Completeness`、`Faithfulness`、`Readability`（0-5 分制）
- 系统指标：`Latency P50/P95/P99`

## 4. 如何运行

默认不连接外部服务，只验证任务可跳过：

```powershell
./gradlew.bat :app:agentStage8RagE2eEval
```

真实评测需要前置条件满足后运行：

```powershell
$env:APP_STAGE8_RAG_E2E_ENABLED="true"
powershell -ExecutionPolicy Bypass -File scripts/run-stage8-rag-e2e-eval.ps1
```

运行完成后会生成并归档：

- `app/build/reports/agent-eval/stage-8-rag-e2e-report.json`
- `app/build/reports/agent-eval/stage-8-rag-e2e-report.md`
- `docs/evidence/agent-quantification/stage-8-rag-e2e/reports/stage-8-rag-e2e-report.json`
- `docs/evidence/agent-quantification/stage-8-rag-e2e/reports/stage-8-rag-e2e-report.md`

## 5. 如何做 baseline diff

```powershell
New-Item -ItemType Directory -Force app/build/reports/agent-eval/baselines
Copy-Item app/build/reports/agent-eval/stage-8-rag-e2e-report.json `
  app/build/reports/agent-eval/baselines/stage-8-rag-e2e-before-change.json
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-stage8-rag-e2e-eval.ps1 `
  -BaselineReport app/build/reports/agent-eval/baselines/stage-8-rag-e2e-before-change.json
```

脚本会额外生成：

- `app/build/reports/agent-eval/stage-8-rag-e2e-diff.md`
- `docs/evidence/agent-quantification/stage-8-rag-e2e/reports/stage-8-rag-e2e-diff.md`

## 6. 与 Stage 7 的区别

| 维度 | Stage 7 (S7-02) | Stage 8 (S8-02) |
| --- | --- | --- |
| 向量检索 | Mock（预设文档） | 真实 pgvector |
| Embedding 模型 | 未调用 | 真实 text-embedding-v3 |
| LLM 生成 | Mock（固定逻辑） | 真实 qwen-plus |
| Relevance | Binary（命中/未命中） | Graded（0-3 级） |
| 检索指标 | top1HitRate, top3HitRate | Recall@K, Hit Rate@K, MRR, nDCG@K |
| 生成指标 | answerGrounded (boolean) | LLM-as-judge 5 维度评分，rubric fallback |
| 系统指标 | 无 | 延迟分位数 |
| 用途 | 回归测试（编排逻辑） | 效果验证（端到端质量） |

## 7. 边界

- 这不是线上 RAG 准确率评测。
- 这不是公开 benchmark。
- 评测结果依赖基础设施可用性（PostgreSQL + pgvector、DashScope API）。
- 生成指标使用 LLM-as-judge；judge 调用或 JSON 解析失败时回退到本地 rubric scorer。
- 系统指标是本地单机数据，不代表线上并发表现。
- 当前未从 Spring AI 响应中提取 token usage，因此不把 token 成本写成已落地指标。
