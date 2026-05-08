# Stage 7 RAG Corpus and Chunk Evidence Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 给知识库补一套固定面试语料、可解释 chunk 元数据和可复核的检索调试证据。

**Architecture:** 维持现有知识库上传和向量检索主链路不变，只在向量化阶段增加章节切分与 chunk 证据元数据，在查询阶段把 metadata 还原成可读的来源信息。证据目录独立落在 `docs/evidence/agent-quantification/stage-7-rag-corpus/`，供后续 retrieval eval 直接引用。

**Tech Stack:** Spring Boot, Spring AI `Document`/`TextSplitter`, Markdown 文档, JUnit 5

---

### Task 1: Chunk Evidence Metadata

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java`
- Add: `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseChunkEvidenceMapper.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/model/QueryDebugInfo.java`

**Step 1: Write the failing test**

```java
// 断言向量化后 chunk metadata 含 source/section/preview
// 断言 debug hit 能返回 sourceTitle、sectionTitle、chunkIndex、preview
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorServiceTest --tests interview.guide.modules.agent.support.AgentToolResultTest`
Expected: fail with missing metadata fields or constructor mismatch

**Step 3: Write minimal implementation**

```java
// 在向量化时为每个 chunk 补齐 kb/source/section/index/preview metadata
// 在 query debug 里把 metadata 还原成可读 Hit
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorServiceTest --tests interview.guide.modules.agent.support.AgentToolResultTest`
Expected: PASS

### Task 2: Fixed Corpus Evidence

**Files:**
- Add: `docs/evidence/agent-quantification/stage-7-rag-corpus/README.md`
- Add: `docs/evidence/agent-quantification/stage-7-rag-corpus/chunk-policy.md`
- Add: `docs/evidence/agent-quantification/stage-7-rag-corpus/sample-docs/*.md`

**Step 1: Write the corpus files**

```markdown
### Java 并发面试知识
### JVM GC 与内存模型
### Spring 事务与 AOP
### Redis 缓存问题
### MySQL 索引与事务
### 系统设计：限流与缓存
### 面试回答评分 Rubric
### 项目讲解 STAR 模板
### 简历亮点表达规范
### 后端开发岗 JD 与能力要求
```

**Step 2: Add the chunk policy**

```markdown
说明当前采用“Markdown 章节优先 + token chunk”的策略，保留 sourceTitle / sectionTitle / chunkIndex / preview。
```

**Step 3: Verify the evidence tree exists**

Run: `Get-ChildItem docs/evidence/agent-quantification/stage-7-rag-corpus -Recurse`
Expected: README, chunk-policy, and 10 sample docs

