# Agent 文档系统重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前混杂的 Agent 文档体系重构为“总览 / 能力地图 / 路线图 / Stage / Task / 历史文档”分层结构，并准确反映当前真实进度：`Stage 1` 已完成、`Stage 2` 进行中、`S2-01` 已完成。

**Architecture:** 先新增顶层总览文档与能力地图，再重写路线图，使“能力缺口”和“执行顺序”分离。随后重写 stage 文档与 task 文档，将当前 4-stage 结构升级为 5-stage 结构，并把历史专项文档迁入 `docs/agent-history/`，同时完成全量交叉链接修复。

**Tech Stack:** Markdown 文档、PowerShell 文件检查、`apply_patch` 文件编辑

---

## 文件结构与职责

### 新建

- `docs/agent-overview.md`
  定义项目定位、Level A / Level B 完成边界、当前整体状态。
- `docs/agent-capability-map.md`
  按能力轴描述当前状态、完成标准、对应 stage/task。
- `docs/agent-history/`
  保存历史专项方案与检查清单。
- `docs/agent-stages/stage-3-domain-tooling-context.md`
  Stage 3：Interview domain tooling 与上下文组装。
- `docs/agent-stages/stage-4-agent-workbench-demo-surface.md`
  Stage 4：Workbench 与 demo 展示面。
- `docs/agent-stages/stage-5-bounded-multi-step-agent.md`
  Stage 5：受控多步 Agent。
- `docs/agent-tasks/s2-task-03-runtime-approval-and-policy.md`
  Stage 2 的 approval 独立任务。
- `docs/agent-tasks/s2-task-04-eval-and-regression.md`
  Stage 2 的 eval 独立任务。
- `docs/agent-tasks/s3-task-01-interview-context-tools.md`
  Stage 3 的 interview context tool 扩展任务。
- `docs/agent-tasks/s3-task-02-context-assembly-policy.md`
  Stage 3 的上下文组装策略任务。
- `docs/agent-tasks/s3-task-03-tool-output-normalization.md`
  Stage 3 的工具输出归一化任务。
- `docs/agent-tasks/s4-task-01-agent-workbench-ui.md`
  Stage 4 的 workbench UI 任务。
- `docs/agent-tasks/s4-task-02-debuggable-demo-flow.md`
  Stage 4 的 demo flow 任务。
- `docs/agent-tasks/s5-task-01-bounded-loop-and-budget-control.md`
  Stage 5 的受控 loop 任务。
- `docs/agent-tasks/s5-task-02-stop-conditions-and-failure-semantics.md`
  Stage 5 的停止条件与失败语义任务。
- `docs/agent-tasks/s5-task-03-handoff-and-subagent-boundary.md`
  Stage 5 的 handoff / subagent 边界任务。

### 修改

- `docs/agent-roadmap.md`
  从“总说明书”收口为“执行路线图”。
- `docs/agent-stages/stage-1-turn-foundation.md`
  对齐新文档体系和当前完成状态。
- `docs/agent-stages/stage-2-observability-guardrails.md`
  对齐 Stage 2 新目标和新 task 划分。
- `docs/agent-tasks/s2-task-01-trace-memory-metrics.md`
  保留已完成状态，补足验证与边界结构。

### 移动或替换

- `docs/agent-mvp-file-plan.md` -> `docs/agent-history/agent-mvp-file-plan.md`
- `docs/agent-mvp-review-checklist.md` -> `docs/agent-history/agent-mvp-review-checklist.md`
- `docs/agent-turn-refactor-plan.md` -> `docs/agent-history/agent-turn-refactor-plan.md`
- `docs/agent-tasks/s2-task-02-guardrails-approval.md`
  替换为新的 `docs/agent-tasks/s2-task-02-guardrails-baseline.md`
- `docs/agent-tasks/s2-task-03-evals-regression.md`
  替换为新的 `docs/agent-tasks/s2-task-04-eval-and-regression.md`
- `docs/agent-tasks/s3-task-01-new-tools-and-context-assembly.md`
  拆分为三个新的 Stage 3 task
- `docs/agent-tasks/s3-task-02-agent-workbench-ui.md`
  替换为新的 Stage 4 task
- `docs/agent-tasks/s4-task-01-controlled-loop-and-step-budget.md`
  替换为新的 Stage 5 task
- `docs/agent-tasks/s4-task-02-handoff-and-subagent-boundaries.md`
  替换为新的 Stage 5 task
- `docs/agent-stages/stage-3-tools-and-workbench.md`
  替换为新的 Stage 3 文档
- `docs/agent-stages/stage-4-controlled-agent-loop.md`
  替换为新的 Stage 4 和 Stage 5 文档

### 校验范围

- 所有 `docs/**/*.md` 中与 Agent 主线相关的交叉链接
- 顶层 `README.md` 中如有直接引用旧 Agent 文档路径，也要同步检查

### 目标目录结构

```text
docs/
  agent-overview.md
  agent-capability-map.md
  agent-roadmap.md
  agent-history/
  agent-stages/
    stage-1-turn-foundation.md
    stage-2-observability-guardrails.md
    stage-3-domain-tooling-context.md
    stage-4-agent-workbench-demo-surface.md
    stage-5-bounded-multi-step-agent.md
  agent-tasks/
    s1-task-01-turn-model-and-state.md
    s1-task-02-response-contract-and-api.md
    s1-task-03-tool-payload-and-session-validation.md
    s2-task-01-trace-memory-metrics.md
    s2-task-02-guardrails-baseline.md
    s2-task-03-runtime-approval-and-policy.md
    s2-task-04-eval-and-regression.md
    s3-task-01-interview-context-tools.md
    s3-task-02-context-assembly-policy.md
    s3-task-03-tool-output-normalization.md
    s4-task-01-agent-workbench-ui.md
    s4-task-02-debuggable-demo-flow.md
    s5-task-01-bounded-loop-and-budget-control.md
    s5-task-02-stop-conditions-and-failure-semantics.md
    s5-task-03-handoff-and-subagent-boundary.md
```

### 约束

- 必须保持 `Stage 1` 为已完成
- 必须保持 `Stage 2` 为进行中
- 必须保持 `S2-01` 为已完成
- 重写后的路线图必须把 `S2-02` 作为当前建议下一任务
- 文档必须明确：`Stage 4` 完成即达到完整单 Agent；`Stage 5` 是增强层

### Task 1: 新建顶层文档骨架

**Files:**
- Create: `docs/agent-overview.md`
- Create: `docs/agent-capability-map.md`
- Modify: `docs/agent-roadmap.md`
- Test: `docs/agent-roadmap.md`

- [ ] **Step 1: 写出顶层文档的最小骨架**

```md
# Interview Guide Agent 概览

## 1. 项目定位
- 它是什么
- 它不是什么

## 2. 完成度定义
### Level A：完整单 Agent
### Level B：受控多步 Agent

## 3. 当前状态
- Stage 1：已完成
- Stage 2：进行中，S2-01 已完成
```

- [ ] **Step 2: 写出能力地图的固定能力轴骨架**

```md
# Interview Guide Agent 能力地图

## Execution Model
## Tooling
## Memory
## Observability
## Guardrails
## Eval
## Workbench
## Controlled Loop
```

- [ ] **Step 3: 收口路线图为“顺序型文档”**

```md
# Interview Guide Agent 路线图

## 当前进度
- Stage 1：已完成
- Stage 2：进行中
- 当前推荐任务：S2-02

## 阶段顺序
1. Stage 1
2. Stage 2
3. Stage 3
4. Stage 4
5. Stage 5
```

- [ ] **Step 4: 检查顶层文档是否已分离“定义 / 能力 / 顺序”**

Run: `Get-Content docs\\agent-overview.md; Get-Content docs\\agent-capability-map.md; Get-Content docs\\agent-roadmap.md`
Expected: 三份文档标题和职责不同，不再重复承载同一种信息

- [ ] **Step 5: Commit**

```bash
git add docs/agent-overview.md docs/agent-capability-map.md docs/agent-roadmap.md
git commit -m "docs: 重构agent顶层文档结构"
```

### Task 2: 重写 Stage 文档为五阶段模型

**Files:**
- Modify: `docs/agent-stages/stage-1-turn-foundation.md`
- Modify: `docs/agent-stages/stage-2-observability-guardrails.md`
- Create: `docs/agent-stages/stage-3-domain-tooling-context.md`
- Create: `docs/agent-stages/stage-4-agent-workbench-demo-surface.md`
- Create: `docs/agent-stages/stage-5-bounded-multi-step-agent.md`
- Test: `docs/agent-stages/`

- [ ] **Step 1: 固定所有 stage 文档统一结构**

```md
## 0. 阶段状态
## 1. 阶段目标
## 2. 本阶段必须解决的问题
## 3. 本阶段交付物
## 4. 任务拆分
## 5. 进入条件 / 依赖关系
## 6. 不在本阶段范围内
## 7. 阶段完成标准
```

- [ ] **Step 2: 保持 Stage 1 与真实进度一致**

```md
- 阶段状态：已完成
- 已完成任务：S1-01、S1-02、S1-03
- 当前任务：无
```

- [ ] **Step 3: 将 Stage 2 改写为 Safe and Observable Runtime**

```md
- 阶段状态：进行中
- 已完成任务：S2-01
- 当前任务：建议进入 S2-02
- 任务拆分：
  - S2-01
  - S2-02
  - S2-03
  - S2-04
```

- [ ] **Step 4: 新建 Stage 3 / 4 / 5 文档，确保每个阶段只承担一类目标**

```md
Stage 3：Domain Tooling and Context Assembly
Stage 4：Agent Workbench and Demo Surface
Stage 5：Bounded Multi-Step Agent
```

- [ ] **Step 5: 检查 stage 目录是否只剩五个主阶段文档**

Run: `Get-ChildItem docs\\agent-stages`
Expected: 目录中只存在新的五阶段文件，标题与文件职责一致

- [ ] **Step 6: Commit**

```bash
git add docs/agent-stages
git commit -m "docs: 重写agent阶段模型为五阶段结构"
```

### Task 3: 重写 Stage 2 文档与任务链路

**Files:**
- Modify: `docs/agent-tasks/s2-task-01-trace-memory-metrics.md`
- Create: `docs/agent-tasks/s2-task-02-guardrails-baseline.md`
- Create: `docs/agent-tasks/s2-task-03-runtime-approval-and-policy.md`
- Create: `docs/agent-tasks/s2-task-04-eval-and-regression.md`
- Test: `docs/agent-tasks/`

- [ ] **Step 1: 保留 S2-01 的已完成状态，并补齐结构**

```md
## 0. 任务状态
- 状态：已完成

## 1. 任务目标
## 2. 本任务范围
## 3. 主要改动点
## 4. 风险与边界
## 5. 完成标准
## 6. 验证要求
```

- [ ] **Step 2: 将原“Guardrails 与 Approval”拆成两个任务**

```md
s2-task-02-guardrails-baseline.md
- input / tool / output guardrails
- 降级 trace 化

s2-task-03-runtime-approval-and-policy.md
- approval model
- risk level
- 审批状态与接口
```

- [ ] **Step 3: 将 eval 单独编号为 S2-04**

```md
# S2-04：Eval 与回归验证

- 固定样例集
- 回归入口
- 指标统计
- 评测结果留档
```

- [ ] **Step 4: 校验 Stage 2 的任务编号与引用是否连续**

Run: `Get-ChildItem docs\\agent-tasks\\s2-*`
Expected: S2 任务编号为 `01` 到 `04`，无缺号，无旧的 `guardrails-approval` 和 `evals-regression` 命名残留

- [ ] **Step 5: Commit**

```bash
git add docs/agent-tasks/s2-*
git commit -m "docs: 重构stage2任务链路"
```

### Task 4: 重写 Stage 3 到 Stage 5 的任务集

**Files:**
- Create: `docs/agent-tasks/s3-task-01-interview-context-tools.md`
- Create: `docs/agent-tasks/s3-task-02-context-assembly-policy.md`
- Create: `docs/agent-tasks/s3-task-03-tool-output-normalization.md`
- Create: `docs/agent-tasks/s4-task-01-agent-workbench-ui.md`
- Create: `docs/agent-tasks/s4-task-02-debuggable-demo-flow.md`
- Create: `docs/agent-tasks/s5-task-01-bounded-loop-and-budget-control.md`
- Create: `docs/agent-tasks/s5-task-02-stop-conditions-and-failure-semantics.md`
- Create: `docs/agent-tasks/s5-task-03-handoff-and-subagent-boundary.md`
- Test: `docs/agent-tasks/`

- [ ] **Step 1: 将原 Stage 3 拆为三类任务**

```md
S3-01：Interview Context Tools
S3-02：Context Assembly Policy
S3-03：Tool Output Normalization
```

- [ ] **Step 2: 将 Workbench 从原 Stage 3 拆出为独立 Stage 4**

```md
S4-01：Agent Workbench UI
S4-02：Debuggable Demo Flow
```

- [ ] **Step 3: 将多步 Agent 作为 Stage 5，并补上停止条件任务**

```md
S5-01：Bounded Loop and Budget Control
S5-02：Stop Conditions and Failure Semantics
S5-03：Handoff and Subagent Boundary
```

- [ ] **Step 4: 确认 Stage 4 被定义为完整单 Agent 的完成边界**

Run: `Get-Content docs\\agent-overview.md; Get-Content docs\\agent-stages\\stage-4-agent-workbench-demo-surface.md`
Expected: 文档明确写出 Stage 4 完成后达到 Level A

- [ ] **Step 5: Commit**

```bash
git add docs/agent-tasks/s3-* docs/agent-tasks/s4-* docs/agent-tasks/s5-* docs/agent-overview.md docs/agent-stages/stage-4-agent-workbench-demo-surface.md
git commit -m "docs: 拆分stage3到stage5任务结构"
```

### Task 5: 迁移历史文档并修复链接

**Files:**
- Create: `docs/agent-history/`
- Modify: `docs/agent-roadmap.md`
- Modify: `docs/agent-overview.md`
- Modify: `docs/agent-capability-map.md`
- Modify: `docs/agent-stages/*.md`
- Modify: `docs/agent-tasks/*.md`
- Test: `docs/**/*.md`

- [ ] **Step 1: 将历史专项文档迁入 `docs/agent-history/`**

```text
docs/agent-history/agent-turn-refactor-plan.md
docs/agent-history/agent-mvp-file-plan.md
docs/agent-history/agent-mvp-review-checklist.md
```

- [ ] **Step 2: 在 roadmap 中降低历史文档权重**

```md
## 历史文档
- 这些文档用于复盘与历史追踪
- 不再作为当前主线指挥文档
```

- [ ] **Step 3: 统一修复所有交叉链接**

```md
- overview 链到 capability map 和 roadmap
- roadmap 链到五个 stage
- stage 链到对应 task
- 历史文档只从 overview 或 roadmap 的“历史资料”区进入
```

- [ ] **Step 4: 扫描旧路径残留**

Run: `rg -n "stage-3-tools-and-workbench|stage-4-controlled-agent-loop|s2-task-02-guardrails-approval|s2-task-03-evals-regression|s3-task-01-new-tools-and-context-assembly|s3-task-02-agent-workbench-ui|s4-task-01-controlled-loop-and-step-budget|s4-task-02-handoff-and-subagent-boundaries" docs README.md`
Expected: 无结果，或者只在 `docs/agent-history/` 中作为历史说明出现

- [ ] **Step 5: Commit**

```bash
git add docs README.md
git commit -m "docs: 迁移agent历史文档并修复链接"
```

### Task 6: 最终一致性检查

**Files:**
- Test: `docs/**/*.md`

- [ ] **Step 1: 检查当前进度表达是否一致**

Run: `rg -n "Stage 1|Stage 2|S2-01|S2-02" docs`
Expected: 顶层文档、stage 文档、task 文档对当前进度表述一致，且都指向 `S2-02` 为下一任务

- [ ] **Step 2: 检查“Level A / Level B”定义是否一致**

Run: `rg -n "Level A|Level B|完整单 Agent|受控多步 Agent|Stage 4|Stage 5" docs`
Expected: 顶层文档与 capability map 对完成边界表述一致，不出现互相冲突的定义

- [ ] **Step 3: 手工快速通读所有顶层 Agent 文档**

```text
按顺序阅读：
1. docs/agent-overview.md
2. docs/agent-capability-map.md
3. docs/agent-roadmap.md
```

Expected: 能明确回答“它是什么”“还差什么”“接下来做什么”

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs: 完成agent文档系统重构校验"
```

## 自检

### 1. Spec 覆盖检查

- “完整 Agent 的重新定义”：由 `agent-overview.md` 与 stage 重新分层覆盖
- “能力缺口与顺序分离”：由 `agent-capability-map.md` 与 `agent-roadmap.md` 分离覆盖
- “保留当前真实进度”：由 Stage 1 / Stage 2 / S2-01 的硬约束覆盖
- “五阶段模型”：由新的 stage 文档和 Stage 3-5 任务文档覆盖
- “历史文档降级但保留”：由 `docs/agent-history/` 迁移任务覆盖

未发现 spec 中存在未映射到任务的要求。

### 2. 占位符扫描

已避免使用：

- `TODO`
- `TBD`
- “后续再补”
- “类似上一任务”

### 3. 名称一致性检查

统一采用以下命名：

- `Level A / Level B`
- `Stage 1` 到 `Stage 5`
- `S2-02 Guardrails Baseline`
- `S2-03 Runtime Approval and Policy`
- `S2-04 Eval and Regression`
- `S4-01 Agent Workbench UI`
- `S4-02 Debuggable Demo Flow`
- `S5-02 Stop Conditions and Failure Semantics`

未发现前后命名冲突。
