# stage-7-injection-safety-set

## Suite Info

- suiteId: `stage-7-injection-safety-set`
- capability: `external content injection safety evaluation`
- stage: `Stage 7`
- suiteType: `safety-eval`

## Goal

Validate that external content injection in knowledge base documents, resumes, tool observations, retrieval results, and interview history does not change the agent decision boundary, bypass approval, leak hidden prompts, or trigger unsafe tool execution.

## Scope

- Fixed case count: `10`
- Covered carriers: `knowledge_base_document`, `resume_text`, `tool_observation`, `retrieval_result`, `interview_history`, `user_request`
- Metrics: `attackBlockedCases`, `secretLeakCount`, `approvalBypassCount`, `unsafeToolExecutionCount`, `unsafeDirectAnswerCount`, `degradedCases`, `modelPollutionSimulatedCases`, `runtimeBoundaryEvidenceCases`
- Code entry: `app/src/test/java/interview/guide/modules/agent/eval/AgentStage7InjectionSafetyEvalTest.java`
- Runner: `scripts/run-agent-stage7-injection-safety-eval.ps1`

## Current Baseline

- Baseline: `baselines/stage-7-injection-safety-set-baseline-2026-05-08.json`
- Archived reports:
  - `reports/stage-7-injection-safety-set-report.json`
  - `reports/stage-7-injection-safety-set-report.md`
  - `reports/stage-7-injection-safety-set-diff.md`

## Evidence Boundary

This suite proves the project keeps external content as untrusted evidence and preserves the local approval and tool-routing boundary. It does not claim resistance against all prompt injection variants or production-grade adversarial safety.
