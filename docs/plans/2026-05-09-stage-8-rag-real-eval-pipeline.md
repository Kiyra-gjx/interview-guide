# Stage 8 RAG Real Eval Pipeline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement a runnable Stage 8 RAG evaluation loop with a graded dataset, real pgvector retrieval, LLM generation scoring, reporting, and evidence archiving.

**Architecture:** Reuse the existing `KnowledgeBaseVectorService` and `KnowledgeBaseQueryService` instead of adding a separate RAG path. The suite is an opt-in Spring Boot integration test that skips unless external infrastructure and DashScope credentials are explicitly enabled.

**Tech Stack:** Java 21, Spring Boot 4, Spring AI, PostgreSQL/pgvector, JUnit 5, PowerShell evidence runner.

---

### Task 1: Evidence Dataset

**Files:**
- Create: `docs/evidence/agent-quantification/stage-8-rag-e2e/eval-dataset/README.md`
- Create: `docs/evidence/agent-quantification/stage-8-rag-e2e/eval-dataset/annotation-guide.md`
- Create: `docs/evidence/agent-quantification/stage-8-rag-e2e/eval-dataset/relevance-judgments.json`
- Create: `docs/evidence/agent-quantification/stage-8-rag-e2e/eval-dataset/reference-answers.json`

**Steps:**
1. Add a 30-query fixed dataset covering concept, precision-term, multi-doc, no-answer, and weak-hit cases.
2. Reference the existing Stage 7 sample corpus.
3. Use graded relevance values 0-3 and reference answers for answerable queries.
4. Add an annotation guide that explains relevance and judge scoring.

### Task 2: Integration Test

**Files:**
- Create: `app/src/test/java/interview/guide/modules/agent/eval/AgentStage8RagE2eEvalTest.java`

**Steps:**
1. Add a Spring Boot test that is skipped unless `APP_STAGE8_RAG_E2E_ENABLED=true`.
2. Seed the Stage 7 sample corpus into the real `knowledge_bases` and `vector_store` path by calling `KnowledgeBaseVectorService.vectorizeAndStore`.
3. Load Stage 8 relevance and reference-answer JSON.
4. For each query, call `KnowledgeBaseQueryService.queryKnowledgeBaseWithDebug`.
5. Compute Recall@3/5/10, Hit Rate@3/5/10, MRR, and nDCG@3/5/10.
6. Compute lightweight generation scores from retrieved evidence, reference key points, and generated answer so the report remains usable even before a separate judge model wrapper exists.
7. Persist JSON and Markdown reports under `app/build/reports/agent-eval`.

### Task 3: Gradle And Runner

**Files:**
- Modify: `app/build.gradle`
- Create: `scripts/run-stage8-rag-e2e-eval.ps1`

**Steps:**
1. Add `agentStage8RagE2eEval` Gradle test task.
2. Add a PowerShell runner that sets the opt-in flag, runs the Gradle task, archives reports to evidence, writes raw results and summary files, and optionally computes a baseline diff.

### Task 4: Documentation Alignment

**Files:**
- Modify: `docs/agent-evals/stage-8-rag-real-eval-pipeline.md`
- Modify: `docs/agent-stages/stage-8-rag-real-eval-pipeline.md`
- Modify: `docs/agent-tasks/s8-task-01-graded-relevance-eval-dataset.md`
- Modify: `docs/agent-tasks/s8-task-02-real-end-to-end-eval-pipeline.md`

**Steps:**
1. Correct the API key variable to `AI_BAILIAN_API_KEY`.
2. Mark S8-01/S8-02 as implemented.
3. Clarify that LLM-as-judge is an automated scoring harness and can later be replaced with a separate judge model call.

### Task 5: Verification

**Steps:**
1. Run `./gradlew.bat :app:agentStage8RagE2eEval --rerun-tasks` without opt-in and confirm it skips.
2. Run a compile/test selection to catch Java errors.
3. Run the Stage 8 script only if local infrastructure and credentials are available.
