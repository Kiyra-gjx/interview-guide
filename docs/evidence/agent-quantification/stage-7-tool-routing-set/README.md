# stage-7-tool-routing-set

## Suite Info

- suiteId: `stage-7-tool-routing-set`
- capability: `tool routing contract evaluation`
- stage: `Stage 7`
- suiteType: `routing-eval`

## Goal

Validate the local tool-routing contract under fixed structured decisions, including selected tool handling, parameter shaping, rejection on invalid calls, and approval parking for high-risk actions.

## Scope

- Fixed case count: `20`
- Core paths: `tool execution`, `degraded rejection`, `direct reply`, `waiting approval`
- Metrics: `toolSelectionAccuracy`, `paramAccuracy`, `rejectionAccuracy`, `directReplyAccuracy`, `approvalRoutingAccuracy`, `unexpectedToolExecutionCount`
- Code entry: `app/src/test/java/interview/guide/modules/agent/eval/AgentStage7ToolRoutingEvalTest.java`
- Runner: `scripts/run-agent-stage7-tool-routing-eval.ps1`

## Current Baseline

- Baseline: `baselines/stage-7-tool-routing-set-baseline-2026-05-08.json`
- Archived reports:
  - `reports/stage-7-tool-routing-set-report.json`
  - `reports/stage-7-tool-routing-set-report.md`
  - `reports/stage-7-tool-routing-set-diff.md`

## Evidence Boundary

This suite proves deterministic routing behavior for the project’s fixed structured-decision fixtures and local validation policy. It does not measure live model intent classification, production agent accuracy, or public benchmark ranking.
