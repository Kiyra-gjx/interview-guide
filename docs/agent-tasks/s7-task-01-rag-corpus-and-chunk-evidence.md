# S7-01：RAG Corpus and Chunk Evidence

## 0. 任务状态

- 状态：规划中
- 当前定位：Stage 7 的 RAG 语料与 chunk 证据前置任务
- 前置依赖：现有知识库上传、解析、向量化和 query debug 链路已可用

## 1. 任务目标

为 RAG 建立一组可控、可解释、可复用的面试知识语料，并补齐 chunk 来源、section 和预览证据，让后续 RAG retrieval eval 有稳定输入。

## 2. 要解决的问题

- 当前知识库可以上传任意文档，但缺少一组可复核的默认评测语料
- 当前 chunk 主要依赖 token 分块，面试中不好解释“命中内容来自哪个章节”
- 如果不知道知识库里放什么，就很难证明 RAG 不是只做了工程接入

## 3. 知识库语料建议

不要把用户简历放进知识库。简历是独立业务资源，知识库应放外部知识资料。

MVP 语料建议 10 份，每份 800 到 1500 字：

- Java 并发面试知识
- JVM GC 与内存模型
- Spring 事务与 AOP
- Redis 缓存问题
- MySQL 索引与事务
- 系统设计：限流与缓存
- 面试回答评分 Rubric
- 项目讲解 STAR 模板
- 简历亮点表达规范
- 后端开发岗位 JD 与能力要求

这些文档可以自写，不需要抓取真实面经。核心是内容可控、答案可标注、适合做固定 query。

## 4. 主要改动点

- 为评测准备固定知识语料
- 为 chunk 增加可解释 metadata，例如：
  - `sourceTitle`
  - `sectionTitle`
  - `chunkIndex`
  - `kbId`
  - `preview`
- 检索 debug 信息中展示来源和预览，避免只返回无来源文本

## 5. 建议落地文件

- `docs/evidence/agent-quantification/stage-7-rag-corpus/README.md`
- `docs/evidence/agent-quantification/stage-7-rag-corpus/sample-docs/`
- `docs/evidence/agent-quantification/stage-7-rag-corpus/chunk-policy.md`

如果实现代码改动，优先检查：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/model/QueryDebugInfo.java`

## 6. 风险与边界

- 不要声称当前已经支持复杂法律条款级语义切分
- 不要把简历当成知识库语料
- 不要为了 chunk metadata 做大规模重构
- MVP 阶段先做到来源可解释，再考虑 parent-child chunk 或重排

## 7. 完成标准

- 至少有 10 份固定面试知识语料
- 每份语料能对应后续 retrieval query
- chunk 级结果能说明来源文档、section 和 chunk 顺序
- query debug 能展示命中 preview
- 能清楚解释当前策略：通用 token chunk + section metadata，后续再扩展 parent-child chunk
