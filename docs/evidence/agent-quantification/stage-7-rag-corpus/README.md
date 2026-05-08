# stage-7-rag-corpus

## Suite Metadata

- suiteId: `stage-7-rag-corpus`
- capability: `RAG corpus and chunk evidence`
- stage: `Stage 7`
- suiteType: `corpus`

## Goal

Provide a controlled and reviewable interview knowledge corpus for RAG. Each document uses Markdown headings so vectorized chunks can retain source document, section, chunk order, and preview metadata for later retrieval evaluation.

## Contents

- `sample-docs/`: 10 fixed interview knowledge documents.
- `chunk-policy.md`: current chunking and evidence metadata policy.
- `query-candidates.json`: machine-readable candidate queries for S7-02.

## Sample Documents

- `java-concurrency.md`
- `jvm-gc.md`
- `spring-tx-aop.md`
- `redis-cache.md`
- `mysql-index.md`
- `system-design.md`
- `interview-rubric.md`
- `star-template.md`
- `resume-highlights.md`
- `backend-jd.md`

## Candidate Retrieval Queries

These queries are not the Stage 7 retrieval eval runner yet. They are stable candidate inputs for S7-02 and are also available in `query-candidates.json`.

| Query | Expected source | Expected section |
| --- | --- | --- |
| `volatile 为什么不能保证 count++ 原子性` | `java-concurrency.md` | `volatile 与原子类` |
| `ThreadPoolExecutor 队列满了以后怎么处理` | `java-concurrency.md` | `线程池` |
| `如何排查 Java heap space OOM` | `jvm-gc.md` | `常见问题排查` |
| `Spring 事务为什么会因为同类内部调用失效` | `spring-tx-aop.md` | `事务代理机制` |
| `Redis 缓存穿透 击穿 雪崩区别` | `redis-cache.md` | `穿透、击穿、雪崩` |
| `MySQL 联合索引最左前缀和范围条件` | `mysql-index.md` | `联合索引` |
| `限流算法固定窗口滑动窗口令牌桶区别` | `system-design.md` | `限流算法` |
| `面试回答 rubric 怎么记录证据` | `interview-rubric.md` | `证据记录` |
| `STAR 模板怎么讲项目 Result 指标` | `star-template.md` | `Result 结果` |
| `简历 RAG 项目怎么写 chunk 元数据亮点` | `resume-highlights.md` | `项目描述结构` |
| `后端 JD 要求的 Java 数据库缓存能力` | `backend-jd.md` | `必备技术能力` |

## Completion Notes

- The corpus intentionally excludes private user resumes.
- The documents are external interview knowledge resources, not generated answer traces.
- Chunk evidence is expected to expose source title, section title, chunk index, knowledge base id, and preview.
