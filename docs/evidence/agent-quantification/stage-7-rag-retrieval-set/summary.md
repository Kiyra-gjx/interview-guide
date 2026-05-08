# stage-7-rag-retrieval-set Summary

## Core Metrics

- totalQueries: `20`
- passedQueries: `20`
- answerableQueries: `17`
- noAnswerQueries: `3`
- weakHitNoAnswerQueries: `1`
- top1HitRate: `100.0%`
- top3HitRate: `100.0%`
- answerGroundedRate: `100.0%`
- noAnswerRejectionRate: `100.0%`
- hallucinationCount: `0`

## Resume Gate

- fixedSampleSet: `yes`
- unifiedMetricDefinition: `yes`
- rawRecords: `yes`
- reviewableEvidence: `yes`
- resumeSafe: `partial`

## Resume-Safe Claims

- Built a fixed Stage 7 RAG retrieval eval set with `20` queries covering source/section hits, precision-term retrieval, grounded answer checks, empty-hit rejection, and weak-hit no-answer rejection.
- Added report metrics for `top1HitRate`, `top3HitRate`, `answerGroundedRate`, `noAnswerRejectionRate`, and `hallucinationCount`, with case-level debug evidence.
- Anchored retrieval assertions to chunk evidence fields: `sourceTitle`, `sectionTitle`, `chunkIndex`, and `preview`.

## Do Not Claim

- Do not claim production RAG accuracy from this suite.
- Do not claim public benchmark results.
- Do not use local test latency as a performance result.

## Follow-Up

- S7-03 should add external content injection cases.
- S7-04 should add tool routing contract cases.