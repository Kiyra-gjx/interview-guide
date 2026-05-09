# Stage 8: RAG Real Eval Pipeline

## 0. 阶段状态

- 阶段状态：固定评测集和真实评测管线已落地，真实跑数依赖本地基础设施和 `AI_BAILIAN_API_KEY`
- 已完成任务：S8-01、S8-02
- 当前任务：拆分独立 judge model 配置、补充 token usage、补充检索/生成分阶段耗时
- 前置条件：Stage 7 已完成，S7-01 语料可用，S7-02 的 QueryDebugInfo 结构可复用，pgvector + embedding + LLM 服务可接入

## 1. 阶段定位

Stage 7 的 RAG 评测（S7-02）使用 mock harness：向量检索返回预设文档，LLM 回答由固定逻辑拼接。它验证的是 `KnowledgeBaseQueryService` 的编排逻辑（query rewrite 路径、topK/minScore 参数、precision-token 命中判定、no-answer 拒答），不是真实 RAG 管线的端到端表现。

Stage 8 要把 RAG 评测从"mock harness regression"推进到"真实管线端到端评测"，覆盖三大维度：

- **检索质量（Retrieval）**：真实 embedding → pgvector 相似度搜索 → 能不能把对的证据找回来
- **生成质量（Generation）**：真实 LLM 基于检索上下文生成 → 回答是否正确、可追溯、无幻觉
- **系统性能（System）**：本地端到端延迟分位数，后续补 token usage 和吞吐

Stage 8 的结果用于回答面试中"你的 RAG 效果怎么样"这个问题，而不仅仅是"你的 RAG 接上了没有"。

## 2. 本阶段目标

1. 建立带多级相关性标注（graded relevance）的 RAG 评测数据集
2. 构建真实端到端评测管线（真实 embedding + 真实向量检索 + 真实 LLM 生成 + LLM-as-judge，失败时 rubric fallback）
3. 实现检索指标：Recall@K、Hit Rate@K、MRR、nDCG@K
4. 实现生成指标：Correctness、Attribution、Completeness、Faithfulness、Readability
5. 实现系统指标：延迟 P50/P95/P99；token 成本、吞吐 QPS 作为后续扩展
6. 所有结果可回归、可对比、可答辩

## 3. 本阶段必须解决的问题

- Stage 7 的 mock harness 无法衡量真实 embedding 模型的召回质量
- Stage 7 没有 graded relevance（只有 binary hit），无法计算 nDCG 等排序质量指标
- Stage 7 的回答是 mock 的，无法衡量真实 LLM 的生成质量（幻觉、可追溯性、完整性）
- 没有生成质量自动评分机制，无法自动化评估生成质量
- 没有系统级指标，无法说明延迟和成本特征

## 4. 任务拆分

- [S8-01：RAG Graded Relevance Eval Dataset](../agent-tasks/s8-task-01-graded-relevance-eval-dataset.md)
- [S8-02：RAG Real End-to-End Eval Pipeline](../agent-tasks/s8-task-02-real-end-to-end-eval-pipeline.md)

## 5. 推荐执行顺序

1. 先做 S8-01，建立评测数据集（queries + graded relevance judgments + reference answers），否则评测没有 ground truth
2. 再做 S8-02，实现真实评测管线、指标计算和报告生成

## 6. 指标体系总览

### 6.1 检索指标（Retrieval）

衡量"能不能把对的证据找回来"。

| 指标 | 定义 | 说明 |
| --- | --- | --- |
| Recall@K | TopK 中包含的相关文档数 / 总相关文档数 | 覆盖率，RAG 最常用 |
| Hit Rate@K | TopK 是否命中至少一个相关文档（0/1） | 命中率，简单直观 |
| MRR | 1 / 第一个相关结果的排名 | 第一个相关结果越靠前越好 |
| nDCG@K | 支持多等级相关性标注的排序质量 | 越靠前权重越高，适合 graded relevance |

### 6.2 生成指标（Generation）

衡量"回答是否正确、是否基于证据"。

| 指标 | 定义 | 说明 |
| --- | --- | --- |
| Correctness | 答案与标准答案是否一致 | LLM-as-judge，失败时 rubric fallback，0-5 分制 |
| Attribution | 关键结论是否有对应引用，引用是否支持结论 | LLM-as-judge，失败时 rubric fallback，0-5 分制 |
| Completeness | 是否覆盖问题的关键点 | LLM-as-judge，失败时 rubric fallback，0-5 分制 |
| Faithfulness | 回答内容是否都能在证据中找到依据 | LLM-as-judge，失败时 rubric fallback，0-5 分制 |
| Readability | 结构清晰、术语准确、格式符合要求 | LLM-as-judge，失败时 rubric fallback，0-5 分制 |

### 6.3 系统指标（System）

衡量"能不能稳定、便宜、快地跑"。

| 指标 | 定义 | 说明 |
| --- | --- | --- |
| Latency P50/P95/P99 | 端到端延迟分位数 | 包含检索 + 生成各阶段 |
| Token Cost | 每次请求的 token 消耗 | 当前未落地，后续从模型 usage 中提取 |
| Throughput | QPS 与并发稳定性 | 当前未落地，后续做压测型评测 |
| Cache Hit Rate | query rewrite cache / embedding cache 命中率 | 当前未实现 cache，记录为 N/A |

## 7. 不在本阶段范围内

- 不接 RAGBench / RGB 等公开 benchmark
- 不做大规模人工标注（用 LLM-assisted + 抽样人工校验）
- 不做 reranker 评测（当前无 reranker 模块）
- 不做多模态 RAG 评测
- 不为了评测重写 RAG 核心逻辑

## 8. 阶段完成标准

- 固定评测数据集 30 条 query，覆盖概念检索、术语检索、多文档综合、no-answer、weak-hit
- 每条 query 有 graded relevance judgment（0-3 级）和 reference answer
- 真实端到端评测可运行（需基础设施可用）；RAG 核心链路无 mock，每条 query 都在全 Stage 7 corpus 上检索
- 检索指标（Recall@K、Hit Rate@K、MRR、nDCG@K）全部实现并有报告
- 生成指标（Correctness、Attribution、Completeness、Faithfulness、Readability）通过 LLM-as-judge 实现并有报告，异常时回退到 rubric scorer
- 系统指标（延迟）至少有 baseline 数据；token 成本后续补齐
- 所有结果沿用 evidence 规范：sample set、raw results、summary、report、baseline、diff

## 9. 对外表述边界

可以写：

> 构建 RAG 端到端评测管线，覆盖检索质量（Recall@K、MRR、nDCG）、生成质量（LLM-as-judge 评估正确性、可追溯性、完整性、无幻觉）和系统性能（本地延迟分位数），用于验证 RAG 管线的真实效果。

不要写：

> RAG 检索准确率 / 生成质量达到生产标准。

除非有真实线上数据和独立评测口径。

## 10. 面试问答要点

### "你的 RAG 效果怎么评测的？"

> 我们有一套端到端评测管线，覆盖三个维度：检索质量、生成质量和系统性能。
>
> 检索质量方面，我们构建了一个带 graded relevance 的评测数据集，每条 query 标注了 0-3 级相关性（不相关、边缘相关、部分相关、完全相关），然后计算 Recall@K、Hit Rate@K、MRR 和 nDCG@K。nDCG 能区分"找到了但排在后面"和"找到了且排在前面"的差异。
>
> 生成质量方面，用 LLM-as-judge 评估五个维度：正确性（与标准答案是否一致）、可追溯性（结论是否有证据支持）、完整性（是否覆盖关键点）、无幻觉（是否只基于检索证据回答）、可读性。每个维度 0-5 分制，有明确的评分 rubric；如果 judge 调用或 JSON 解析失败，会回退到本地 rubric scorer。
>
> 系统性能方面，追踪本地端到端延迟分位数（P50/P95/P99）。token 成本和检索/生成分阶段耗时是后续增强项，需要从模型 usage 和链路计时中补齐。

### "你的 Recall@K 大概是多少？"

> 需要实际跑评测后填入具体数字。如果数字不理想，可以接着讲改进方向：调整 chunk 策略、优化 embedding 模型、增加 query rewrite 等。

### "你怎么标注 ground truth 的？"

> 我们用 LLM-assisted 标注：先让 LLM 对每个 query-doc pair 做相关性评分，然后人工抽样校验。这样比纯人工标注效率高，比纯 LLM 标注更可靠。标注采用 4 级制：0（不相关）、1（边缘相关）、2（部分相关）、3（完全相关）。

### "nDCG 和 Recall 有什么区别？"

> Recall@K 只关心"TopK 里有没有相关文档"，不关心排序。nDCG@K 同时关心"相关文档排在第几位"——排在第 1 位的完全相关文档贡献比排在第 10 位的大得多。在 RAG 场景下，排序质量很重要，因为用户通常只看前面几个结果。
