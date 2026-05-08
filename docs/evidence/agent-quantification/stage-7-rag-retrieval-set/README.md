# stage-7-rag-retrieval-set

## Suite Info

- suiteId: `stage-7-rag-retrieval-set`
- capability: `RAG retrieval evaluation`
- stage: `Stage 7`
- suiteType: `retrieval-eval`

## Goal

Validate the RAG retrieval path with a fixed query set that covers source/section hits, grounded answers, precision-term retrieval, and no-answer rejection. This suite uses controlled stubs for vector search and answer generation, so it is a deterministic regression harness rather than a production retrieval benchmark.

## Scope

- Fixed query count: `20`
- Answerable queries: `17`
- No-answer queries: `3`
- Weak-hit no-answer queries: `1`
- Metrics: `top1HitRate`, `top3HitRate`, `answerGroundedRate`, `noAnswerRejectionRate`, `hallucinationCount`
- Code entry: `app/src/test/java/interview/guide/modules/agent/eval/AgentStage7RagRetrievalEvalTest.java`
- Runner: `scripts/run-agent-stage7-rag-retrieval-eval.ps1`

## Current Baseline

- Baseline: `baselines/stage-7-rag-retrieval-set-baseline-2026-05-08.json`
- Archived reports:
  - `reports/stage-7-rag-retrieval-set-report.json`
  - `reports/stage-7-rag-retrieval-set-report.md`
  - `reports/stage-7-rag-retrieval-set-diff.md`

## Evidence Boundary

This suite proves that the current query service can expose reviewable retrieval evidence and enforce deterministic no-answer behavior under fixed inputs, including a weak-hit candidate that must be rejected by the precision-token gate. It does not claim production retrieval accuracy, public benchmark performance, or live embedding quality.
