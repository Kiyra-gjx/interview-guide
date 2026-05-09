# Stage 8 RAG E2E Eval Dataset

## 定位

这是 Stage 8 真实 RAG 端到端评测的固定评测集，复用 Stage 7 的面试知识语料，给每条 query 提供 graded relevance judgment；每条可回答 query 提供 reference answer。

## 范围

- suiteId: `stage-8-rag-e2e`
- corpus: `docs/evidence/agent-quantification/stage-7-rag-corpus/sample-docs/`
- queryCount: `30`
- answerableQueries: `22`
- noAnswerOrWeakHitQueries: `8`
- queryTypes: `concept`, `precision-term`, `multi-doc`, `no-answer`, `weak-hit`
- relevanceScale: `0-3`

## 文件

- `relevance-judgments.json`: 查询、相关性标注、预期来源和 no-answer 期望
- `reference-answers.json`: 可回答 query 的参考答案和 key points
- `annotation-guide.md`: 标注和评分口径

## 边界

该数据集用于本项目 RAG 管线的本地端到端验证。它可以回答“当前项目 RAG 管线在固定样本上的表现如何”，但不是公开 benchmark，也不是线上准确率统计。
