# Stage 6 Evidence and Resume Quantification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a formal Stage 6 that turns existing Agent runtime signals into fixed eval suites, repeatable reports, and resume-safe quantified evidence.

**Architecture:** Reuse the current Stage 2/3/5 runtime contracts instead of reopening core runtime design. Stage 6 only standardizes sample sets, report schemas, evidence storage, and resume-governance rules so later data can be reproduced and defended in interviews.

**Tech Stack:** Markdown docs, GitHub issues, existing Java test/eval harness, PowerShell regression scripts

---

### Task 1: Add Stage 6 stage/task documentation

**Files:**
- Create: `docs/agent-stages/stage-6-evidence-benchmark-and-resume-quantification.md`
- Create: `docs/agent-tasks/s6-task-01-eval-suite-and-reporting-foundation.md`
- Create: `docs/agent-tasks/s6-task-02-capability-quantification-sets.md`
- Create: `docs/agent-tasks/s6-task-03-stage-5-benchmark-and-resume-pack.md`

**Step 1: Write the doc requirements**

- Stage 6 must state that the current gap is evidence engineering, not runtime redesign.
- Stage 6 must define deliverables around fixed suites, reports, baselines, benchmark evidence, and resume-safe claims.
- Each task doc must explain scope, risks, and verification.

**Step 2: Save the stage doc**

Expected sections:
- stage status
- current progress
- goal
- problems
- deliverables
- task links
- dependencies
- out of scope
- completion criteria
- suggested evidence

**Step 3: Save the task docs**

Expected task split:
- S6-01 unified eval/reporting foundation
- S6-02 context/memory/recovery/safety quantification sets
- S6-03 Stage 5 benchmark and resume-ready evidence pack

**Step 4: Review wording**

Expected:
- no unsupported metrics
- no promise of runtime changes unless evidence shows a real gap

### Task 2: Align top-level docs with Stage 6

**Files:**
- Modify: `docs/agent-roadmap.md`
- Modify: `docs/agent-overview.md`
- Modify: `docs/agent-stages/stage-5-bounded-multi-step-agent.md`

**Step 1: Update roadmap snapshot**

Expected updates:
- current overall status reflects Stage 5 implementation completed and Stage 6 in progress
- current recommendation points to Stage 6 evidence work
- new Stage 6 section is added

**Step 2: Update overview**

Expected updates:
- current progress includes Stage 6
- “what’s missing” focuses on fixed suites, benchmark evidence, and resume-safe quantification

**Step 3: Update Stage 5 handoff note**

Expected updates:
- Stage 5 follow-up points to Stage 6 for benchmark/evidence closure

### Task 3: Triage and close already-resolved GitHub issues

**Files:**
- No repo file changes required

**Step 1: Inspect open issues**

Run:

```powershell
Invoke-RestMethod -Uri "https://api.github.com/repos/Kiyra-gjx/interview-guide/issues?state=open&per_page=100"
```

Expected:
- identify which issues are still open

**Step 2: Match each issue against current code/docs**

Expected:
- close only issues whose Done criteria now have direct code/test/doc evidence
- leave partially completed issues open

**Step 3: Comment before closing**

Expected:
- mention concrete files or docs proving closure

### Task 4: Final review and summary

**Files:**
- Review all changed docs

**Step 1: Check consistency**

Expected:
- Stage 6 wording aligns with `docs/agent-resume-quantification.md`
- no contradiction between Stage 5 and Stage 6 ownership

**Step 2: Prepare final conclusion**

Expected:
- state whether logic changes are required now
- state what evidence work comes next
