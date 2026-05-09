# Stage 8 RAG E2E Annotation Guide

## Relevance Scale

| Score | Meaning | Rule |
| --- | --- | --- |
| 0 | 不相关 | 与 query 无关，或只有泛泛技术背景 |
| 1 | 边缘相关 | 包含部分关键词，但不能支持答案 |
| 2 | 部分相关 | 能支持答案的一部分，但不完整 |
| 3 | 完全相关 | 能直接支持 query 的核心答案 |

## Query Types

- `concept`: 概念或机制类问题，通常应命中单个主要 section。
- `precision-term`: 术语精确检索，要求候选证据包含关键 token。
- `multi-doc`: 需要综合两个或多个文档来源。
- `no-answer`: 知识库没有答案，应拒答。
- `weak-hit`: 有弱相关文档，但缺少关键 precision token，应拒答。

## Generation Scoring

Stage 8 对生成质量记录五个维度，每项 `0-5`：

- `Correctness`: 回答是否覆盖 reference answer 的关键点。
- `Attribution`: 回答是否基于检索证据，且检索证据能支持结论。
- `Completeness`: 回答是否覆盖 key points。
- `Faithfulness`: 回答是否避免引入证据外内容。
- `Readability`: 回答结构、术语和可读性是否满足面试解释场景。

评测使用 LLM-as-judge 产出可回归报告；如果 judge 调用或 JSON 解析失败，评测会回退到本地 rubric scorer，保证报告仍可生成。
