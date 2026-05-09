# Stage 8 RAG E2E Report

- suite: stage-8-rag-e2e
- generatedAt: 2026-05-10T00:28:22.560234500
- corpus: docs/evidence/agent-quantification/stage-7-rag-corpus/sample-docs
- embeddingModel: text-embedding-v3
- llmModel: qwen-plus
- judgeMode: llm-as-judge with rubric fallback

## Summary

- totalQueries: 30
- passedQueries: 29
- answerableQueries: 22
- noAnswerQueries: 8
- recallAt3: 0.9242
- recallAt5: 0.9697
- recallAt10: 0.9697
- hitRateAt3: 1.0
- hitRateAt5: 1.0
- hitRateAt10: 1.0
- mrr: 1.0
- ndcgAt3: 0.9447
- ndcgAt5: 0.9704
- ndcgAt10: 0.9704
- correctness: 5.0
- attribution: 4.7727
- completeness: 4.7273
- faithfulness: 4.8636
- readability: 5.0
- latencyP50Ms: 7486
- latencyP95Ms: 15043
- latencyP99Ms: 15625

## Case Results

| Case | Type | Answerable | Recall@3 | Hit@3 | MRR | nDCG@3 | Correctness | Faithfulness | NoAnswerMatched | LatencyMs | Passed | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| S8-001 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 7124 | true | matched Stage 8 expectations |
| S8-002 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 11464 | true | matched Stage 8 expectations |
| S8-003 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 11889 | true | matched Stage 8 expectations |
| S8-004 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 6236 | true | matched Stage 8 expectations |
| S8-005 | precision-term | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 6376 | true | matched Stage 8 expectations |
| S8-006 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 10468 | true | matched Stage 8 expectations |
| S8-007 | multi-doc | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 14806 | true | matched Stage 8 expectations |
| S8-008 | multi-doc | true | 0.5 | 1.0 | 1.0 | 0.7039 | 5.0 | 5.0 | false | 9933 | true | matched Stage 8 expectations |
| S8-009 | no-answer | false | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | true | 1442 | true | matched Stage 8 expectations |
| S8-010 | weak-hit | false | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | true | 1202 | true | matched Stage 8 expectations |
| S8-011 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 9435 | true | matched Stage 8 expectations |
| S8-012 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 11898 | true | matched Stage 8 expectations |
| S8-013 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 10394 | true | matched Stage 8 expectations |
| S8-014 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 7864 | true | matched Stage 8 expectations |
| S8-015 | concept | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 5220 | true | matched Stage 8 expectations |
| S8-016 | precision-term | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 7486 | true | matched Stage 8 expectations |
| S8-017 | precision-term | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 5955 | true | matched Stage 8 expectations |
| S8-018 | precision-term | true | 1.0 | 1.0 | 1.0 | 0.8262 | 5.0 | 2.0 | false | 5858 | false | expectation mismatch |
| S8-019 | precision-term | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 9214 | true | matched Stage 8 expectations |
| S8-020 | precision-term | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 8559 | true | matched Stage 8 expectations |
| S8-021 | multi-doc | true | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | false | 12117 | true | matched Stage 8 expectations |
| S8-022 | multi-doc | true | 0.5 | 1.0 | 1.0 | 0.6131 | 5.0 | 5.0 | false | 11783 | true | matched Stage 8 expectations |
| S8-023 | multi-doc | true | 0.6667 | 1.0 | 1.0 | 0.81 | 5.0 | 5.0 | false | 15043 | true | matched Stage 8 expectations |
| S8-024 | multi-doc | true | 0.6667 | 1.0 | 1.0 | 0.8303 | 5.0 | 5.0 | false | 15625 | true | matched Stage 8 expectations |
| S8-025 | weak-hit | false | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | true | 1340 | true | matched Stage 8 expectations |
| S8-026 | no-answer | false | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | true | 1470 | true | matched Stage 8 expectations |
| S8-027 | no-answer | false | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | true | 1228 | true | matched Stage 8 expectations |
| S8-028 | no-answer | false | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | true | 1729 | true | matched Stage 8 expectations |
| S8-029 | no-answer | false | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | true | 875 | true | matched Stage 8 expectations |
| S8-030 | weak-hit | false | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | true | 1177 | true | matched Stage 8 expectations |
