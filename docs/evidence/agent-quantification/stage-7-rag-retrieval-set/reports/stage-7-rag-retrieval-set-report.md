# Stage 7 RAG Retrieval Set Report

- suite: stage-7-rag-retrieval-set
- generatedAt: 2026-05-08T13:39:34.428957900
- totalQueries: 20
- passedQueries: 20
- answerableQueries: 17
- noAnswerQueries: 3
- top1HitRate: 100.0%
- top3HitRate: 100.0%
- answerGroundedRate: 100.0%
- noAnswerRejectionRate: 100.0%
- hallucinationCount: 0
- averageLatencyMs: 87
- maxLatencyMs: 1700

| Case | Type | Top1 | Top3 | Grounded | NoAnswerRejected | Hallucinated | HitCount | RawCandidateHits | RejectionReason | Passed | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| RAG-001 | concept | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-002 | concept | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-003 | concept | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-004 | concept | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-005 | concept | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-006 | concept | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-007 | concept | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-008 | grounded-answer | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-009 | grounded-answer | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-010 | grounded-answer | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-011 | grounded-answer | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-012 | precision-term | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-013 | precision-term | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-014 | precision-term | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-015 | precision-term | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-016 | precision-term | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-017 | grounded-answer | true | true | true | false | false | 2 | 2 | n/a | true | matched retrieval and answer expectations |
| RAG-018 | no-answer | false | false | false | true | false | 0 | 0 | no_hits | true | matched retrieval and answer expectations |
| RAG-019 | no-answer | false | false | false | true | false | 0 | 0 | no_hits | true | matched retrieval and answer expectations |
| RAG-020 | no-answer | false | false | false | true | false | 0 | 1 | missing_precision_tokens:OAuth2 | true | matched retrieval and answer expectations |
