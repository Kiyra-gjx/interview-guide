# S8-01：RAG Graded Relevance Eval Dataset

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 8 的评测数据集前置任务
- 前置依赖：S7-01 语料已可用（`docs/evidence/agent-quantification/stage-7-rag-corpus/sample-docs/`）

## 1. 任务目标

构建一组带多级相关性标注（graded relevance）和参考答案（reference answer）的 RAG 评测数据集，为 S8-02 的真实端到端评测提供 ground truth。

## 2. 要解决的问题

- Stage 7 的 S7-02 只有 binary hit（命中/未命中），无法区分"部分相关"和"完全相关"
- Stage 7 没有 reference answer，无法评估生成质量（正确性、完整性）
- Stage 7 的 query 只覆盖单一文档检索，缺少多文档综合和 no-answer 场景的 graded 标注
- 没有 graded relevance 就无法计算 nDCG@K（排序质量指标）

## 3. 评测数据集结构

### 3.1 查询集（Query Set）

固定评测集已落地 30 条 query，分布：

| 类型 | 数量 | 说明 |
| --- | --- | --- |
| concept | 10 | 概念检索，如"volatile 关键字的内存可见性" |
| precision-term | 6 | 术语精确匹配，如"AOP"、"MVCC"、"CAS" |
| multi-doc | 6 | 需要综合多个文档才能完整回答，如"Java 内存模型与 GC 的关系" |
| no-answer | 5 | 知识库中没有答案，必须拒答 |
| weak-hit | 3 | 有弱相关候选文档但缺少 precision token，应触发 precision-token 拒答 |

### 3.2 相关性标注（Relevance Judgment）

每条 query 对知识库中每个相关文档的 section 做 4 级标注：

| 等级 | 含义 | 说明 |
| --- | --- | --- |
| 0 | 不相关 | 文档与 query 无关 |
| 1 | 边缘相关 | 文档包含 query 的部分关键词，但不包含答案 |
| 2 | 部分相关 | 文档包含 query 的部分答案，但不完整 |
| 3 | 完全相关 | 文档包含 query 的完整答案 |

标注方式：LLM-assisted + 人工抽样校验。

1. 用 LLM 对每个 (query, document-section) pair 做初步评分
2. 人工抽样 20% 校验，修正明显错误
3. 最终标注结果存入 `relevance-judgments.json`

### 3.3 参考答案（Reference Answer）

每条可回答 query 配一个参考答案，用于生成质量评估。

参考答案要求：
- 覆盖问题的关键点（3-5 个要点）
- 引用具体的技术细节（而非泛泛而谈）
- 与知识库语料一致（不引入外部知识）

参考答案存入 `reference-answers.json`。

## 4. 数据集文件格式

### 4.1 relevance-judgments.json

```json
{
  "suiteId": "stage-8-rag-e2e",
  "version": "1.0",
  "corpus": {
    "documentCount": 10,
    "sectionCount": 45,
    "source": "stage-7-rag-corpus"
  },
  "queries": [
    {
      "id": "S8-001",
      "query": "volatile count++ 的原子性问题",
      "queryType": "concept",
      "relevanceJudgments": [
        {
          "docId": "java-concurrency.md",
          "section": "volatile",
          "relevance": 3,
          "reason": "直接讨论 volatile 和 count++ 的原子性问题"
        },
        {
          "docId": "java-concurrency.md",
          "section": "thread pool",
          "relevance": 0,
          "reason": "线程池与 volatile 原子性无关"
        }
      ]
    }
  ]
}
```

### 4.2 reference-answers.json

```json
{
  "suiteId": "stage-8-rag-e2e",
  "version": "1.0",
  "answers": [
    {
      "queryId": "S8-001",
      "query": "volatile count++ 的原子性问题",
      "referenceAnswer": "volatile 保证可见性但不保证原子性。count++ 实际上是读取-修改-写入三步操作...",
      "keyPoints": [
        "volatile 只保证可见性，不保证原子性",
        "count++ 是复合操作（read-modify-write）",
        "需要使用 AtomicInteger 或 synchronized",
        "CAS 是 AtomicInteger 的底层机制"
      ],
      "sourceDocuments": ["java-concurrency.md"]
    }
  ]
}
```

## 5. 与 Stage 7 的关系

- 复用 S7-01 的语料（`stage-7-rag-corpus/sample-docs/`）
- 复用 S7-02 的部分 query，但增加 graded relevance 标注
- 新增多文档综合、weak-hit 等 Stage 7 未覆盖的场景
- Stage 7 的 mock harness 继续用于回归测试，Stage 8 的真实评测用于效果验证

## 6. 建议落地文件

- `docs/evidence/agent-quantification/stage-8-rag-e2e/eval-dataset/README.md`
- `docs/evidence/agent-quantification/stage-8-rag-e2e/eval-dataset/relevance-judgments.json`
- `docs/evidence/agent-quantification/stage-8-rag-e2e/eval-dataset/reference-answers.json`
- `docs/evidence/agent-quantification/stage-8-rag-e2e/eval-dataset/annotation-guide.md`

## 7. 验收标准

- 固定评测集已有 30 条 query，超过至少 25 条的验收目标
- 每条 query 有 graded relevance judgment（0-3 级）
- 至少覆盖 4 种 query 类型（concept、precision-term、multi-doc、no-answer）
- 每条可回答 query 有 reference answer 和 keyPoints
- relevance judgments 经过至少一轮人工抽样校验
- 数据集格式可被 S8-02 的评测代码直接读取

## 8. 简历表述边界

可以写：

> 构建带多级相关性标注的 RAG 评测数据集，支持 Recall@K、MRR、nDCG@K 等排序质量指标的计算。

不要写：

> 由领域专家标注的大规模评测数据集。

除非真的有领域专家参与标注。
