# S7-02：RAG Retrieval Evaluation Set

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 7 的 RAG 检索可信性评测任务，当前实现为固定 fixture 的 deterministic regression harness
- 前置依赖：S7-01 至少完成 MVP 语料和 chunk 来源证据

## 1. 任务目标

建立 `stage-7-rag-retrieval-set`，用固定 query 验证知识库检索、答案依据和无答案拒答能力。

## 2. 要解决的问题

- 当前项目已经集成 RAG，但缺少专门证明检索有效性的固定样本
- 如果简历写了 RAG，只讲“接了 pgvector”不够，需要能说明怎么评测
- 不能只看 Recall@K，还要验证回答是否基于检索证据、无答案时是否拒答

## 3. 本任务范围

- 固定 query 集
- expected document / expected section / expected evidence
- 检索命中指标
- grounded answer 指标
- no-answer 拒答指标

## 4. 建议样本规模

MVP：20 条 query。

建议分布：

- 12 条可回答 query
- 5 条术语精确匹配 query，例如 `AOP`、`MVCC`、`CMS`
- 3 条 no-answer query，知识库中没有答案，必须拒答

## 5. 建议指标

- `totalQueries`
- `top1HitRate`
- `top3HitRate`
- `answerGroundedRate`
- `noAnswerRejectionRate`
- `hallucinationCount`

## 6. 建议落地文件

- `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/README.md`
- `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/sample-set.md`
- `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/raw-results.md`
- `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/summary.md`
- `app/src/test/java/interview/guide/modules/agent/eval/AgentStage7RagRetrievalEvalTest.java`
- `scripts/run-agent-stage7-rag-retrieval-eval.ps1`

Gradle task 建议命名：

- `agentStage7RagRetrievalEval`

对应使用说明等 runner 落地后再新增：

- `docs/agent-evals/stage-7-rag-retrieval-set.md`

## 7. 验收标准

- 固定 query 至少 20 条
- 每条 query 都有 expected evidence
- no-answer case 至少 3 条
- no-answer case 至少包含 1 条“有弱相关候选文档但缺少 precision token，因此拒答”的场景
- 报告区分 retrieval 命中和 final answer grounded
- 所有结果能回到 sample set、raw results、summary、report、baseline 和 diff

## 8. 简历表述边界

可以写：

> 构建固定 RAG retrieval set，覆盖术语精确检索、无答案拒答和答案依据校验，用于验证知识库检索链路。

不要写：

> RAG 检索准确率达到线上生产标准。

除非有真实线上数据和独立评测口径。


## 9. Completion Notes

- status: completed on `2026-05-08`
- Gradle entry: `./gradlew.bat :app:agentStage7RagRetrievalEval`
- script entry: `powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-rag-retrieval-eval.ps1`
- evidence root: `docs/evidence/agent-quantification/stage-7-rag-retrieval-set/`
- eval usage doc: `docs/agent-evals/stage-7-rag-retrieval-set.md`
- fixed queries: `20`
- no-answer cases: `3`
- weak-hit no-answer cases: `1`
- archived report, baseline, and diff are available under the evidence root.
