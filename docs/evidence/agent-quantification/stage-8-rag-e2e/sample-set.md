# Stage8RagE2eSampleSet

## Sample Info

- suiteId: `stage-8-rag-e2e`
- capability: `Real RAG end-to-end evaluation`
- suiteType: `rag-e2e-eval`
- corpusReference: `docs/evidence/agent-quantification/stage-7-rag-corpus/`
- datasetReference: `eval-dataset/relevance-judgments.json`
- queryCount: `30`
- answerableQueries: `22`
- noAnswerOrWeakHitQueries: `8`

## Fixed Cases

| caseId | scenarioType | query | answerable | expectedSources | verifier |
| --- | --- | --- | --- | --- | --- |
| S8-001 | concept | `volatile count++ 为什么不是原子操作` | true | `java-concurrency.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-002 | concept | `ThreadPoolExecutor 队列满了以后任务提交流程和拒绝策略` | true | `java-concurrency.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-003 | concept | `Java heap space OOM 应该怎么排查` | true | `jvm-gc.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-004 | concept | `Spring @Transactional 同类内部调用为什么会失效` | true | `spring-tx-aop.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-005 | precision-term | `MVCC 在 READ COMMITTED 和 REPEATABLE READ 下 read view 有什么区别` | true | `mysql-index.md` | precision-token retrieval and LLM-as-judge |
| S8-006 | concept | `Redis 缓存穿透、击穿、雪崩分别怎么处理` | true | `redis-cache.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-007 | multi-doc | `接口限流和缓存治理应该监控哪些指标` | true | `system-design.md`, `redis-cache.md` | multi-source retrieval and LLM-as-judge |
| S8-008 | multi-doc | `事务里为什么不应该放远程 HTTP 调用，和连接池锁等待有什么关系` | true | `spring-tx-aop.md`, `mysql-index.md` | multi-source retrieval and LLM-as-judge |
| S8-009 | no-answer | `Kubernetes HPA 根据 CPU 和自定义指标扩容的流程` | false | none | fixed no-answer rejection |
| S8-010 | weak-hit | `OAuth2 authorization code PKCE flow` | false | none | weak-hit/no-answer rejection |
| S8-011 | concept | `synchronized 和 ReentrantLock 在工程上怎么取舍` | true | `java-concurrency.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-012 | concept | `JVM 运行时数据区和 Java 内存模型有什么区别` | true | `jvm-gc.md`, `java-concurrency.md` | multi-source retrieval and LLM-as-judge |
| S8-013 | concept | `Redis 分布式锁为什么要用 SET NX PX 和 Lua 脚本释放` | true | `redis-cache.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-014 | concept | `MySQL 联合索引最左前缀和范围条件后的列还能不能用` | true | `mysql-index.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-015 | concept | `AOP 适合做什么，不适合把什么逻辑放到切面里` | true | `spring-tx-aop.md` | full-corpus Recall@K, MRR, nDCG, LLM-as-judge |
| S8-016 | precision-term | `CAS 的 ABA 问题和 AtomicStampedReference 是什么关系` | true | `java-concurrency.md` | precision-token retrieval and LLM-as-judge |
| S8-017 | precision-term | `Metaspace OOM 通常和什么有关` | true | `jvm-gc.md` | precision-token retrieval and LLM-as-judge |
| S8-018 | precision-term | `Redis maxmemory-policy 里 allkeys-lru 和 volatile-lru 有什么区别` | true | `system-design.md` | precision-token retrieval and LLM-as-judge |
| S8-019 | precision-term | `MySQL Explain 的 type、key、rows、Extra 应该怎么看` | true | `mysql-index.md` | precision-token retrieval and LLM-as-judge |
| S8-020 | precision-term | `Spring 事务传播 REQUIRED 和 REQUIRES_NEW 有什么区别` | true | `spring-tx-aop.md` | precision-token retrieval and LLM-as-judge |
| S8-021 | multi-doc | `简历里怎么表达一个知识库 RAG 项目才不空泛` | true | `resume-highlights.md`, `backend-jd.md` | multi-source retrieval and LLM-as-judge |
| S8-022 | multi-doc | `面试回答 Redis 缓存穿透时怎样从合格答到高分答` | true | `redis-cache.md`, `interview-rubric.md` | multi-source retrieval and LLM-as-judge |
| S8-023 | multi-doc | `后端接口高峰期变慢时，为什么不应该只说加缓存，应该怎么拆解` | true | `system-design.md`, `redis-cache.md`, `mysql-index.md` | multi-source retrieval and LLM-as-judge |
| S8-024 | multi-doc | `项目讲解中怎么说明个人职责、技术动作和量化结果` | true | `star-template.md`, `resume-highlights.md`, `backend-jd.md` | multi-source retrieval and LLM-as-judge |
| S8-025 | weak-hit | `Kafka exactly-once 和事务消息的实现原理` | false | none | weak-hit/no-answer rejection |
| S8-026 | no-answer | `Elasticsearch 倒排索引和 BM25 评分公式怎么计算` | false | none | fixed no-answer rejection |
| S8-027 | no-answer | `TCP 三次握手和 TIME_WAIT 为什么要等 2MSL` | false | none | fixed no-answer rejection |
| S8-028 | no-answer | `RabbitMQ publisher confirm 和 consumer ack 的区别` | false | none | fixed no-answer rejection |
| S8-029 | no-answer | `Vue3 composition API 里 reactive 和 ref 的区别` | false | none | fixed no-answer rejection |
| S8-030 | weak-hit | `Linux epoll 水平触发和边缘触发有什么区别` | false | none | weak-hit/no-answer rejection |

## Control Variables

- vector search: real `KnowledgeBaseVectorService` + Spring AI pgvector store
- retrieval scope: every query searches the full Stage 7 corpus, not only `expectedSources`
- embedding: real `text-embedding-v3`
- answer generation: real `KnowledgeBaseQueryService` with production RAG prompts
- generation scoring: LLM-as-judge over answer, key points, and retrieved evidence; rubric fallback on judge failure
- execution gate: `APP_STAGE8_RAG_E2E_ENABLED=true`
- API key: `AI_BAILIAN_API_KEY`
