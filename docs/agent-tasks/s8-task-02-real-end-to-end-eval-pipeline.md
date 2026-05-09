# S8-02：RAG Real End-to-End Eval Pipeline

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 8 的核心评测任务
- 前置依赖：S8-01 固定评测数据集已完成（relevance-judgments.json + reference-answers.json）

## 1. 任务目标

构建真实端到端 RAG 评测管线（RAG 核心链路无 mock），实现检索指标、生成指标和系统指标的完整评测，产出可回归、可对比、可答辩的评测报告。

## 2. 要解决的问题

- Stage 7 的 mock harness 无法衡量真实 embedding → pgvector → LLM 管线的效果
- 没有生成质量自动评分机制，无法自动化评估生成质量
- 没有系统级指标（延迟分位数，后续扩展 token 成本），无法说明 RAG 的工程特征
- 面试时无法用数据回答"你的 RAG 效果怎么样"

## 3. 评测管线架构

```
┌─────────────────────────────────────────────────────────┐
│                    RAG Real Eval Pipeline                │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Phase 1: Retrieval-Only Eval (确定性)                   │
│  ┌─────────────┐    ┌──────────────┐    ┌────────────┐ │
│  │ Eval Dataset │───>│ Real Embedding│───>│ pgvector   │ │
│  │ (queries +   │    │ (DashScope   │    │ Similarity │ │
│  │  relevance)  │    │  text-emb-v3) │    │ Search     │ │
│  └─────────────┘    └──────────────┘    └─────┬──────┘ │
│                                               │        │
│                                    ┌──────────▼──────┐ │
│                                    │ Retrieval Metrics│ │
│                                    │ Recall@K, Hit@K  │ │
│                                    │ MRR, nDCG@K      │ │
│                                    └─────────────────┘ │
│                                                         │
│  Phase 2: Generation Eval (非确定性)                     │
│  ┌─────────────┐    ┌──────────────┐    ┌────────────┐ │
│  │ Retrieved    │───>│ Real LLM     │───>│ LLM-as-    │ │
│  │ Context +    │    │ (DashScope   │    │ Judge      │ │
│  │ Query        │    │  qwen-plus)  │    │ Scoring    │ │
│  └─────────────┘    └──────────────┘    └─────┬──────┘ │
│                                               │        │
│                                    ┌──────────▼──────┐ │
│                                    │ Generation      │ │
│                                    │ Metrics         │ │
│                                    │ Correctness     │ │
│                                    │ Attribution     │ │
│                                    │ Completeness    │ │
│                                    │ Faithfulness    │ │
│                                    │ Readability     │ │
│                                    └─────────────────┘ │
│                                                         │
│  Phase 3: System Metrics (工程指标)                      │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Latency P50/P95/P99 │ Token Cost │ Per-stage Time│  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## 4. Phase 1：检索指标评测

### 4.1 流程

1. 加载 S8-01 的 `relevance-judgments.json`
2. 对每条 query，使用完整 Stage 7 corpus 调用真实 `KnowledgeBaseQueryService.queryKnowledgeBaseWithDebug()`
3. 从 `QueryDebugInfo` 中提取检索结果（hits、candidates、retrievalQuery）
4. 将检索结果与 graded relevance judgments 对比，计算指标

### 4.2 指标计算

**Recall@K**：
```
Recall@K = |{relevant docs in top K}| / |{all relevant docs}|
```
- relevant = relevance >= 2（部分相关或完全相关）
- K = 3, 5, 10

**Hit Rate@K**：
```
Hit Rate@K = 1 if any relevant doc in top K, else 0
```
- 报告所有 query 的平均 Hit Rate

**MRR（Mean Reciprocal Rank）**：
```
MRR = mean(1 / rank_of_first_relevant_doc)
```
- relevant = relevance >= 2

**nDCG@K**：
```
nDCG@K = DCG@K / IDCG@K
DCG@K = sum(rel_i / log2(i+1)) for i in 1..K
IDCG@K = DCG of ideal ranking
```
- 使用 graded relevance（0-3）作为 gain
- K = 3, 5, 10

### 4.3 实现要点

- 使用 `@SpringBootTest` 获取真实 Spring beans（`KnowledgeBaseQueryService`、`KnowledgeBaseVectorService`）
- 需要基础设施可用：PostgreSQL + pgvector、Redis、DashScope API key
- 如果基础设施不可用，测试应 skip（`@EnabledIf` 或 `Assumptions.assumeTrue`）而不是 fail
- RAG 核心链路不 mock：embedding、pgvector 检索、RAG 生成、LLM-as-judge 都走真实实现；非 RAG 的 Redis/队列启动依赖可在测试配置中隔离

## 5. Phase 2：生成指标评测

### 5.1 LLM-as-Judge 设计

使用 LLM 对 RAG 回答做自动化评分。Judge prompt 结构：

```
你是一个 RAG 回答质量评估专家。请根据以下信息对 RAG 回答进行评分。

## 用户问题
{question}

## 检索到的证据
{retrieved_context}

## 参考答案
{reference_answer}

## RAG 回答
{rag_answer}

请从以下 5 个维度评分（每个维度 0-5 分）：

1. **Correctness（正确性）**：答案与参考答案是否一致？
   - 5: 完全正确，覆盖所有关键点
   - 4: 基本正确，遗漏 1 个次要细节
   - 3: 部分正确，有 1-2 个关键点错误或遗漏
   - 2: 大部分不正确
   - 1: 完全不正确
   - 0: 无法评估

2. **Attribution（可追溯性）**：关键结论是否有证据支持？
   - 5: 所有结论都有明确的证据引用
   - 4: 大部分结论有证据支持
   - 3: 部分结论缺乏证据
   - 2: 大部分结论无证据支持
   - 1: 结论与证据矛盾
   - 0: 无法评估

3. **Completeness（完整性）**：是否覆盖问题的所有关键点？
   - 5: 覆盖所有关键点
   - 4: 遗漏 1 个次要关键点
   - 3: 遗漏 1-2 个关键点
   - 2: 遗漏大部分关键点
   - 1: 几乎没有覆盖
   - 0: 无法评估

4. **Faithfulness（无幻觉）**：回答内容是否都能在证据中找到依据？
   - 5: 所有内容都有证据依据
   - 4: 有 1 处轻微推测
   - 3: 有 1-2 处幻觉
   - 2: 大部分内容是幻觉
   - 1: 几乎全是幻觉
   - 0: 无法评估

5. **Readability（可读性）**：结构清晰、术语准确、格式规范？
   - 5: 结构清晰，术语准确，格式规范
   - 4: 基本清晰，有 1-2 处格式问题
   - 3: 结构一般，有术语使用不当
   - 2: 结构混乱，难以理解
   - 1: 几乎不可读
   - 0: 无法评估

请以 JSON 格式返回评分结果。
```

### 5.2 流程

1. 对每条可回答 query，使用 Phase 1 的检索结果构建 context
2. 调用真实 LLM 生成回答（与线上 RAG 流程一致）
3. 用 LLM-as-judge 对回答评分，异常时回退到自动 rubric scorer
4. 汇总各维度分数

### 5.3 实现要点

- 生成回答使用与线上相同的 prompt 模板（`knowledgebase-query-system.st` + `knowledgebase-query-user.st`）
- 使用 LLM-as-judge；后续可以把 judge model 与生成模型拆成独立配置
- judge model 结果需要解析 JSON，建议用 Spring AI 的 `StructuredOutput` 或手动解析
- no-answer query 不需要做生成评测（直接验证是否拒答）

## 6. Phase 3：系统指标评测

### 6.1 延迟指标

- 端到端延迟：从收到 query 到返回 answer 的总时间
- 检索延迟：embedding + pgvector search 的时间
- 生成延迟：LLM 生成回答的时间
- 报告 P50、P95、P99 分位数

### 6.2 成本指标

- Prompt tokens：发送给 LLM 的 token 数
- Completion tokens：LLM 生成的 token 数
- Embedding tokens：embedding 模型处理的 token 数
- 单次请求总 token 成本

### 6.3 实现要点

- 在 `KnowledgeBaseQueryService` 的各阶段插入计时
- 从 LLM 响应中提取 usage 信息（prompt_tokens、completion_tokens）是后续增强；当前暂不写成已落地 token 成本
- 多次运行取统计值（建议每条 query 跑 3 次取中位数）

## 7. 报告格式

最终报告包含：

### 7.1 JSON Report

```json
{
  "suiteId": "stage-8-rag-e2e",
  "generatedAt": "2026-05-09T12:00:00",
  "config": {
    "embeddingModel": "text-embedding-v3",
    "llmModel": "qwen-plus",
    "topK": [3, 5, 10],
    "minScore": 0.28
  },
  "retrievalMetrics": {
    "recallAt3": 0.85,
    "recallAt5": 0.92,
    "recallAt10": 0.96,
    "hitRateAt3": 0.90,
    "hitRateAt5": 0.95,
    "mrr": 0.78,
    "ndcgAt3": 0.82,
    "ndcgAt5": 0.85,
    "ndcgAt10": 0.87
  },
  "generationMetrics": {
    "correctness": { "mean": 4.2, "median": 4.0, "min": 2.0, "max": 5.0 },
    "attribution": { "mean": 3.8, "median": 4.0, "min": 1.0, "max": 5.0 },
    "completeness": { "mean": 4.0, "median": 4.0, "min": 2.0, "max": 5.0 },
    "faithfulness": { "mean": 4.5, "median": 5.0, "min": 3.0, "max": 5.0 },
    "readability": { "mean": 4.3, "median": 4.0, "min": 3.0, "max": 5.0 }
  },
  "systemMetrics": {
    "latencyP50Ms": 1200,
    "latencyP95Ms": 2800,
    "latencyP99Ms": 4500,
    "avgPromptTokens": 1800,
    "avgCompletionTokens": 450,
    "avgEmbeddingTokens": 25
  },
  "caseResults": [...]
}
```

### 7.2 Markdown Report

包含：
- 总览表格（所有指标一行）
- 检索指标详细表（每条 query 一行）
- 生成指标详细表（每条 query 一行）
- 系统指标统计
- 失败案例分析（low score cases）

## 8. 建议落地文件

### 代码

- `app/src/test/java/interview/guide/modules/agent/eval/AgentStage8RagE2eEvalTest.java`

### 脚本

- `scripts/run-stage8-rag-e2e-eval.ps1`

### 证据

- `docs/evidence/agent-quantification/stage-8-rag-e2e/reports/stage-8-rag-e2e-report.json`
- `docs/evidence/agent-quantification/stage-8-rag-e2e/reports/stage-8-rag-e2e-report.md`
- `docs/evidence/agent-quantification/stage-8-rag-e2e/baselines/stage-8-rag-e2e-baseline-YYYY-MM-DD.json`

### Gradle

- `agentStage8RagE2eEval`

## 9. 验收标准

- 真实端到端评测可运行（需基础设施可用），RAG 核心链路无 mock
- 检索指标（Recall@K、Hit Rate@K、MRR、nDCG@K）全部实现
- 生成指标（Correctness、Attribution、Completeness、Faithfulness、Readability）通过 LLM-as-judge 实现，异常时回退到 rubric scorer
- 系统指标（延迟 P50/P95/P99）有 baseline 数据；token 成本后续补齐
- 所有结果沿用 evidence 规范
- 基础设施不可用时测试 skip 而不是 fail

## 10. 简历表述边界

可以写：

> 构建 RAG 端到端评测管线，覆盖检索质量（Recall@K、MRR、nDCG@K）、生成质量（LLM-as-judge 评估正确性、可追溯性、完整性、无幻觉、可读性）和系统性能（延迟分位数），实现评测结果的基线对比和回归检测。

不要写：

> 达到生产级 RAG 准确率 / 通过 RAGBench 官方 benchmark。

除非有真实线上数据或官方 benchmark 结果。
