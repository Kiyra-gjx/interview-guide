# Stage7RagRetrievalSet

## Sample Info

- suiteId: `stage-7-rag-retrieval-set`
- capability: `RAG retrieval evaluation`
- suiteType: `retrieval-eval`
- corpusReference: `docs/evidence/agent-quantification/stage-7-rag-corpus/`

## Fixed Cases

| caseId | scenarioType | query | expectedSource | expectedSection | expectedEvidence | verifier |
| --- | --- | --- | --- | --- | --- | --- |
| RAG-001 | concept | `volatile count++ atomicity` | `java-concurrency.md` | `volatile` | `volatile`, `count++`, `AtomicInteger` | top1 source/section hit and grounded answer |
| RAG-002 | concept | `ThreadPoolExecutor queue full rejection policy` | `java-concurrency.md` | `thread pool` | `ThreadPoolExecutor`, `queue`, `rejectedExecutionHandler` | top1 source/section hit and grounded answer |
| RAG-003 | concept | `Java heap space OOM troubleshooting` | `jvm-gc.md` | `OOM troubleshooting` | `Java heap space`, `heap dump`, `leak` | top1 source/section hit and grounded answer |
| RAG-004 | concept | `Spring transaction self invocation proxy failure` | `spring-tx-aop.md` | `transaction proxy` | `self invocation`, `proxy`, `@Transactional` | top1 source/section hit and grounded answer |
| RAG-005 | concept | `Redis cache penetration breakdown avalanche` | `redis-cache.md` | `cache failure patterns` | `penetration`, `breakdown`, `avalanche` | top1 source/section hit and grounded answer |
| RAG-006 | concept | `MySQL composite index leftmost prefix range condition` | `mysql-index.md` | `composite index` | `leftmost prefix`, `range condition`, `composite index` | top1 source/section hit and grounded answer |
| RAG-007 | concept | `rate limiting fixed window sliding window token bucket` | `system-design.md` | `rate limiting algorithms` | `fixed window`, `sliding window`, `token bucket` | top1 source/section hit and grounded answer |
| RAG-008 | grounded-answer | `interview rubric evidence recording` | `interview-rubric.md` | `evidence record` | `evidence record`, `observable behavior`, `score` | top1 source/section hit and grounded answer |
| RAG-009 | grounded-answer | `STAR template Result metrics` | `star-template.md` | `Result` | `Result`, `metrics`, `business impact` | top1 source/section hit and grounded answer |
| RAG-010 | grounded-answer | `resume RAG project chunk metadata highlights` | `resume-highlights.md` | `project description` | `RAG`, `chunk metadata`, `sourceTitle` | top1 source/section hit and grounded answer |
| RAG-011 | grounded-answer | `backend JD Java database cache capabilities` | `backend-jd.md` | `required skills` | `Java`, `database`, `cache` | top1 source/section hit and grounded answer |
| RAG-012 | precision-term | `AOP` | `spring-tx-aop.md` | `AOP aspect order` | `AOP`, `@Order`, `proxy` | single-term precision hit and grounded answer |
| RAG-013 | precision-term | `MVCC` | `mysql-index.md` | `transaction isolation` | `MVCC`, `consistent read`, `InnoDB` | single-term precision hit and grounded answer |
| RAG-014 | precision-term | `CMS` | `jvm-gc.md` | `garbage collectors` | `CMS`, `low pause`, `fragmentation` | single-term precision hit and grounded answer |
| RAG-015 | precision-term | `CAS` | `java-concurrency.md` | `volatile` | `CAS`, `AtomicInteger`, `retry` | single-term precision hit and grounded answer |
| RAG-016 | precision-term | `Bloom filter` | `redis-cache.md` | `cache failure patterns` | `Bloom filter`, `cache penetration`, `false positive` | precision hit and grounded answer |
| RAG-017 | grounded-answer | `Redis distributed lock SET NX PX Lua release` | `redis-cache.md` | `distributed lock` | `SET NX PX`, `Lua`, `value` | top1 source/section hit and grounded answer |
| RAG-018 | no-answer | `Kubernetes HPA scaling metrics` | `none` | `none` | none | no effective hit and fixed no-answer rejection |
| RAG-019 | no-answer | `Elasticsearch inverted index segment merge` | `none` | `none` | none | no effective hit and fixed no-answer rejection |
| RAG-020 | no-answer | `OAuth2 authorization code PKCE flow` | `none` | `none` | none | weak-hit candidate rejected by missing precision token and fixed no-answer rejection |

## Control Variables

- vector search: mocked fixed `Document` list per case
- answer generation: mocked from the actual RAG user prompt so grounded answers depend on retrieved context tokens
- query rewrite: disabled in this suite
- knowledgeBaseId: `7001`
- baselineReference: `baselines/stage-7-rag-retrieval-set-baseline-2026-05-08.json`
