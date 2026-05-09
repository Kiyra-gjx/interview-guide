# Stage 8 RAG Real Eval Pipeline Evidence

## 目录结构

```
stage-8-rag-e2e/
├── README.md                          # 本文件
├── eval-dataset/                      # 评测数据集
│   ├── README.md                      # 数据集说明
│   ├── relevance-judgments.json       # 带 graded relevance 的查询集
│   ├── reference-answers.json         # 参考答案
│   └── annotation-guide.md            # 标注指南
├── reports/                           # 评测报告
│   ├── stage-8-rag-e2e-report.json    # JSON 报告
│   ├── stage-8-rag-e2e-report.md      # Markdown 报告
│   └── stage-8-rag-e2e-diff.md        # baseline 对比
└── baselines/                         # 基线数据
    └── stage-8-rag-e2e-baseline-YYYY-MM-DD.json
```

## 关联文档

- 阶段定义：`docs/agent-stages/stage-8-rag-real-eval-pipeline.md`
- 任务文档：`docs/agent-tasks/s8-task-01-graded-relevance-eval-dataset.md`、`docs/agent-tasks/s8-task-02-real-end-to-end-eval-pipeline.md`
- 使用说明：`docs/agent-evals/stage-8-rag-real-eval-pipeline.md`

## 当前状态

- 固定评测集和运行入口已落地：`30` 条 query，覆盖 concept、precision-term、multi-doc、no-answer、weak-hit。
- 真实评测需要 `APP_STAGE8_RAG_E2E_ENABLED=true`、PostgreSQL + pgvector 和 `AI_BAILIAN_API_KEY`。
- 每条 query 都在全 Stage 7 corpus 上检索，不用 `expectedSources` 限制检索范围。
- `reports/`、`raw-results.md`、`summary.md` 和 `baselines/` 会在运行 `scripts/run-stage8-rag-e2e-eval.ps1` 后生成。
