# stage-8-rag-e2e Raw Results

## Run Info

- executedAt: `2026-05-10T00:28:22.560234500`
- runner: `powershell -ExecutionPolicy Bypass -File scripts/run-stage8-rag-e2e-eval.ps1 -SkipRealRun -MaxHeap 4g`
- reportPath: `reports/stage-8-rag-e2e-report.json`
- diffPath: `reports/stage-8-rag-e2e-diff.md`
- archivedBaselinePath: `baselines/stage-8-rag-e2e-baseline-2026-05-09.json`
- diffBaseline: `docs\evidence\agent-quantification\stage-8-rag-e2e\baselines\stage-8-rag-e2e-baseline-2026-05-09.json`

## Case Results

| caseId | queryType | answerable | recallAt3 | hitRateAt3 | mrr | ndcgAt3 | correctness | faithfulness | noAnswerMatched | latencyMs | passed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| S8-001 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 7124 | True |
| S8-002 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 11464 | True |
| S8-003 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 11889 | True |
| S8-004 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 6236 | True |
| S8-005 | precision-term | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 6376 | True |
| S8-006 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 10468 | True |
| S8-007 | multi-doc | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 14806 | True |
| S8-008 | multi-doc | True | 0.5 | 1.0 | 1.0 | 0.7039 | 5.0 | 5.0 | False | 9933 | True |
| S8-009 | no-answer | False | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | True | 1442 | True |
| S8-010 | weak-hit | False | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | True | 1202 | True |
| S8-011 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 9435 | True |
| S8-012 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 11898 | True |
| S8-013 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 10394 | True |
| S8-014 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 7864 | True |
| S8-015 | concept | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 5220 | True |
| S8-016 | precision-term | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 7486 | True |
| S8-017 | precision-term | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 5955 | True |
| S8-018 | precision-term | True | 1.0 | 1.0 | 1.0 | 0.8262 | 5.0 | 2.0 | False | 5858 | False |
| S8-019 | precision-term | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 9214 | True |
| S8-020 | precision-term | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 8559 | True |
| S8-021 | multi-doc | True | 1.0 | 1.0 | 1.0 | 1.0 | 5.0 | 5.0 | False | 12117 | True |
| S8-022 | multi-doc | True | 0.5 | 1.0 | 1.0 | 0.6131 | 5.0 | 5.0 | False | 11783 | True |
| S8-023 | multi-doc | True | 0.6667 | 1.0 | 1.0 | 0.81 | 5.0 | 5.0 | False | 15043 | True |
| S8-024 | multi-doc | True | 0.6667 | 1.0 | 1.0 | 0.8303 | 5.0 | 5.0 | False | 15625 | True |
| S8-025 | weak-hit | False | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | True | 1340 | True |
| S8-026 | no-answer | False | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | True | 1470 | True |
| S8-027 | no-answer | False | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | True | 1228 | True |
| S8-028 | no-answer | False | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | True | 1729 | True |
| S8-029 | no-answer | False | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | True | 875 | True |
| S8-030 | weak-hit | False | 0.0 | 0.0 | 0.0 | 0.0 | 5.0 | 5.0 | True | 1177 | True |