# stage-7-rag-retrieval-set Raw Results

## Run Info

- executedAt: `2026-05-08T13:39:34.428957900`
- runner: `powershell -ExecutionPolicy Bypass -File scripts/run-agent-stage7-rag-retrieval-eval.ps1 -BaselineReport app\build\reports\agent-eval\stage-7-rag-retrieval-set-report.json`
- reportPath: `reports/stage-7-rag-retrieval-set-report.json`
- diffPath: `reports/stage-7-rag-retrieval-set-diff.md`
- archivedBaselinePath: `baselines/stage-7-rag-retrieval-set-baseline-2026-05-08.json`
- diffBaseline: `app/build/reports/agent-eval/stage-7-rag-retrieval-set-report.json (snapshot before rerun)`

## Case Results

| caseId | queryType | top1Hit | top3Hit | answerGrounded | noAnswerRejected | hallucinated | hitCount | rawCandidateHits | rejectionReason | passed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| RAG-001 | concept | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-002 | concept | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-003 | concept | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-004 | concept | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-005 | concept | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-006 | concept | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-007 | concept | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-008 | grounded-answer | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-009 | grounded-answer | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-010 | grounded-answer | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-011 | grounded-answer | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-012 | precision-term | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-013 | precision-term | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-014 | precision-term | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-015 | precision-term | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-016 | precision-term | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-017 | grounded-answer | True | True | True | False | False | 2 | 2 | n/a | True |
| RAG-018 | no-answer | False | False | False | True | False | 0 | 0 | no_hits | True |
| RAG-019 | no-answer | False | False | False | True | False | 0 | 0 | no_hits | True |
| RAG-020 | no-answer | False | False | False | True | False | 0 | 1 | missing_precision_tokens:OAuth2 | True |

## Notes

- `latencyMs` is local test runtime noise and is not resume-safe.
- `passed=true` means the fixed expected source, section, evidence, and no-answer assertions matched.