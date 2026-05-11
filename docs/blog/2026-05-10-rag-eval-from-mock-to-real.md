# RAG 不是接上就完了：从 mock 到真实评测的实战记录

> 项目地址：[interview-agent](https://github.com/Kiyra-gjx/interview-agent)
> 技术栈：Java 21 / Spring Boot 4.0 / Spring AI 2.0 / PostgreSQL pgvector / DashScope

## 问题：你的 RAG 效果怎么样？

面试时被问到"你的 RAG 效果怎么样"，如果只能回答"接了 pgvector"或者"能跑通"，那基本等于没回答。

RAG（Retrieval-Augmented Generation）的工程接入并不难——文档解析、chunk 分块、embedding 向量化、pgvector 存储、相似度检索、LLM 生成回答，每一步都有现成的库和 API。但"能跑通"和"效果好"之间隔着一条评测的沟。

我在 Interview Agent 项目里走了一条弯路：先写了 mock harness 的评测，后来发现它验证不了真实效果，又重写了真实端到端评测。这篇文章记录这个过程，以及从真实评测中发现的问题。

## 第一版：Mock Harness（Stage 7）

第一版 RAG 评测长这样：

```java
// 向量检索被 mock 了
when(vectorService.similaritySearch(anyString(), any(), any(), any()))
    .thenReturn(scenario.documents());  // 直接返回预设的假文档

// LLM 回答被 mock 了
when(answerResponseSpec.content())
    .thenAnswer(invocation -> answerFromPrompt(scenario, capturedUserPrompt.get()));
```

这个设计的初衷是验证 `KnowledgeBaseQueryService` 的编排逻辑：

- query rewrite 是否走了正确的路径
- topK / minScore 参数选择是否合理
- precision-token 命中判定是否准确
- no-answer 时是否正确拒答

它确实有用——能保证改了 RAG 逻辑后不会把已经做好的东西弄坏。但它衡量不了三件事：

1. **真实 embedding 模型的召回质量**：`text-embedding-v3` 对"volatile count++ 原子性"这种 query 能不能把对的 chunk 找回来？
2. **真实 LLM 的生成质量**：基于检索上下文生成的回答有没有幻觉？关键结论能不能追溯到证据？
3. **排序质量**：相关文档排在第 1 位还是第 10 位？

Mock harness 的通过率永远是 100%——因为结果是设计好的。

## 第二版：真实端到端评测（Stage 8）

### 设计思路

第二版的核心原则：**零 mock**。

```
真实 query → 真实 embedding (text-embedding-v3)
           → 真实 pgvector 相似度搜索
           → 真实 LLM 生成 (qwen-plus)
           → LLM-as-judge 评分
```

评测分三个维度：

| 维度 | 衡量什么 | 指标 |
| --- | --- | --- |
| 检索质量 | 能不能把对的证据找回来 | Recall@K, Hit Rate@K, MRR, nDCG@K |
| 生成质量 | 回答是否正确、可追溯、无幻觉 | Correctness, Attribution, Completeness, Faithfulness, Readability |
| 系统性能 | 能不能稳定、快地跑 | 延迟 P50/P95/P99 |

### Graded Relevance：从"命中/未命中"到 4 级标注

第一版只有 binary hit（命中或未命中）。第二版引入 graded relevance：

| 等级 | 含义 | 示例 |
| --- | --- | --- |
| 0 | 不相关 | query 问 volatile，文档讲线程池 |
| 1 | 边缘相关 | 文档包含"原子性"关键词，但不讨论 volatile |
| 2 | 部分相关 | 文档讨论 volatile 可见性，但不涉及 count++ |
| 3 | 完全相关 | 文档直接讨论 volatile + count++ 原子性问题 |

有了 graded relevance，才能算 nDCG@K——它不只关心"有没有找到"，还关心"排在第几位"。排在第 1 位的完全相关文档，贡献比排在第 10 位的大得多。

### LLM-as-Judge：用 LLM 评 LLM

生成质量的评估用 LLM-as-Judge：让一个 LLM 当裁判，给 RAG 回答打分。

Judge prompt 的核心结构：

```
## 用户问题
{question}

## 检索到的证据
{retrieved_context}

## 参考答案
{reference_answer}

## RAG 回答
{rag_answer}

请从 5 个维度评分（0-5 分）：
1. Correctness：与参考答案是否一致
2. Attribution：结论是否有证据支持
3. Completeness：是否覆盖关键点
4. Faithfulness：是否只基于证据回答
5. Readability：结构和格式
```

这里有个设计选择：judge 调用失败时回退到本地 rubric scorer（基于关键词匹配的规则评分）。这样即使 LLM API 不可用，评测也能跑完，只是精度低一些。

## 真实数据

以下是 Stage 8 评测的真实结果（30 条 query，真实 pgvector + 真实 LLM）：

### 检索指标

| 指标 | 数值 | 说明 |
| --- | --- | --- |
| Recall@3 | 0.9242 | Top3 覆盖了 92% 的相关文档 |
| Recall@5 | 0.9697 | Top5 覆盖了 97% |
| Recall@10 | 0.9697 | Top10 和 Top5 一样，说明相关文档基本都在前 5 |
| Hit Rate@3 | 1.0 | 每条可回答 query 在 Top3 都至少命中 1 个相关文档 |
| MRR | 1.0 | 第一个相关结果永远排在第 1 位 |
| nDCG@3 | 0.9447 | 排序质量很好，完全相关文档基本排在最前面 |

解读：检索质量整体不错。Recall@3 和 Recall@5 的差距只有 4.5%，说明相关文档基本都在前 3 个结果里。MRR=1.0 意味着没有出现"找到了但排在后面"的情况。

### 生成指标

| 指标 | 均值 | 说明 |
| --- | --- | --- |
| Correctness | 5.0 | 所有回答都与参考答案一致 |
| Attribution | 4.77 | 大部分结论有证据支持 |
| Completeness | 4.73 | 覆盖了大部分关键点 |
| Faithfulness | 4.86 | 基本无幻觉，但有 1 个例外 |
| Readability | 5.0 | 结构和格式都很好 |

### 系统指标

| 指标 | 数值 | 说明 |
| --- | --- | --- |
| Latency P50 | 7.5s | 一半请求在 7.5s 内完成 |
| Latency P95 | 15s | 95% 的请求在 15s 内完成 |
| Latency P99 | 15.6s | 最慢的请求 15.6s |

延迟偏高，主要瓶颈在 LLM 生成阶段。这是后续优化方向。

## 失败案例：S8-018 的 Faithfulness 问题

30 条 query 中有 1 条没通过：**S8-018**（precision-term 类型，query 是 "CAS"）。

| 指标 | 数值 | 说明 |
| --- | --- | --- |
| Recall@3 | 1.0 | 检索完全正确 |
| Hit Rate@3 | 1.0 | 命中了相关文档 |
| Correctness | 5.0 | 回答内容本身是对的 |
| **Faithfulness** | **2.0** | 回答中有内容不在检索证据中 |

这条 case 的检索完全正确，回答内容也和参考答案一致，但 **Faithfulness 只有 2 分**——LLM 在回答中加入了检索证据之外的内容（幻觉）。

这是一个典型的 RAG 问题：LLM "知道"答案（因为训练数据中有），但它不应该在 RAG 场景下使用这些"额外知识"。RAG 的核心原则是 **只基于检索证据回答**，否则就失去了 RAG 的意义——你无法追溯回答的来源。

这个发现直接影响了我对 RAG prompt 的理解：**prompt 不只要求"回答问题"，还要明确限制"只使用提供的证据"**。

## 经验总结

### 1. Mock 评测验证逻辑，真实评测验证效果

Mock harness 不能省——它验证的是编排逻辑（query rewrite、参数选择、拒答路径），改了 RAG 代码后必须跑。但它衡量不了真实效果。

两种评测的定位：

| | Mock Harness (Stage 7) | Real E2E (Stage 8) |
| --- | --- | --- |
| 向量检索 | 预设文档 | 真实 pgvector |
| LLM 生成 | 固定逻辑 | 真实 qwen-plus |
| 用途 | 回归测试 | 效果验证 |
| 运行条件 | 无外部依赖 | 需要基础设施 |

### 2. nDCG 比 Recall 多告诉你一件事

Recall@K 只关心"TopK 里有没有相关文档"，nDCG@K 还关心"排在第几位"。在 RAG 场景下，排序质量直接影响用户体验——没人会翻到第 10 个结果去找答案。

实际数据印证了这一点：Recall@3=0.92 但 nDCG@3=0.94，说明找到的相关文档确实排在了前面。

### 3. Faithfulness 是最容易被忽视的指标

Correctness 高不代表 Faithfulness 高。LLM 可以给出"正确"的回答，但部分内容不是来自检索证据，而是来自训练数据。在面试、法律、医疗等场景下，这是不可接受的。

### 4. LLM-as-Judge 需要 fallback

LLM judge 本身也可能出错（调用失败、JSON 解析异常、评分偏差）。设计时要准备 fallback 方案——本地 rubric scorer 精度低，但至少能保证评测流程不中断。

### 5. 评测数据集比评测代码更重要

评测代码可以迭代，但评测数据集（query + graded relevance + reference answer）是 ground truth。花时间做好数据集标注，比花时间写花哨的评测框架更有价值。

## 后续方向

- **Chunk 策略优化**：当前用通用 token 分块，对技术文档的语义切分不够精细
- **Reranker**：在 pgvector 检索后加一层重排，提升排序质量
- **Token 成本追踪**：从 LLM 响应中提取 usage 信息，量化每次请求的成本
- **延迟优化**：P50=7.5s 偏高，瓶颈在 LLM 生成，考虑 streaming + 预生成
- **Judge 模型独立配置**：把 judge model 和生成 model 拆开，减少评估偏差

---

*本文数据来自 Interview Agent 项目 Stage 8 RAG 真实端到端评测，评测配置：text-embedding-v3 + pgvector + qwen-plus，30 条固定 query。*
