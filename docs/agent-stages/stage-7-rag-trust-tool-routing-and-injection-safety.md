# Stage 7: RAG Trust, Tool Routing, and Injection Safety

## 0. 阶段状态

- 阶段状态：规划中
- 当前任务：S7-01、S7-02、S7-03、S7-04
- 前置条件：Stage 6 已完成，现有 eval / evidence 目录、baseline / diff 和报告机制可复用

## 1. 阶段定位

Stage 6 已经完成“已有能力的证据收口”：Safety、Context、Memory、Recovery、Stage 5 Benchmark 都有固定样本、报告和可复核证据。

Stage 7 不再继续扩展泛化 Agent 形态，而是补齐面试中最容易被追问的三类缺口：

- RAG 知识库到底放什么、怎么切 chunk、怎么证明检索有效
- 外部文档、简历、工具结果里的恶意指令是否会污染 Agent 决策
- Agent 决策层是否真的能稳定选择工具、校验参数、拒绝错误调用

## 2. 本阶段目标

把当前“已经集成 RAG 和 Agent 工具调用”的表述，推进到“RAG / Tool Routing / Injection Safety 都有最小可回归套件证明”。

本阶段重点不是追求大规模公开 benchmark 分数，而是形成项目内可解释、可复现、可答辩的固定样本。

## 3. 本阶段必须解决的问题

- 当前 RAG 已经接入项目和简历表述，但缺少可控知识语料、chunk 证据和检索评测
- 当前知识库使用通用 token 分块，面试中容易被追问“是否切坏业务语义”
- 当前 safety set 已覆盖审批和 guardrail，但还缺少外部内容注入场景
- 当前 Agent 有工具注册、结构化决策和本地校验，但缺少专门评估工具路由正确性的 suite

## 4. 任务拆分

- [S7-01：RAG Corpus and Chunk Evidence](../agent-tasks/s7-task-01-rag-corpus-and-chunk-evidence.md)
- [S7-02：RAG Retrieval Evaluation Set](../agent-tasks/s7-task-02-rag-retrieval-eval-set.md)
- [S7-03：External Content Injection Safety Set](../agent-tasks/s7-task-03-external-content-injection-safety-set.md)
- [S7-04：Tool Routing Contract Evaluation](../agent-tasks/s7-task-04-tool-routing-contract-eval.md)

## 5. 推荐执行顺序

1. 先做 S7-01，确定知识库语料和 chunk / source metadata，否则 RAG 评测没有稳定输入
2. 再做 S7-02，补最小 RAG retrieval set，支撑简历里的 RAG 表述
3. 再做 S7-03，补外部内容注入安全，支撑 Agent + RAG 的安全边界
4. 最后做 S7-04，补工具路由评测，支撑 Agent 决策层能力

如果短期只补两项，优先做 S7-01 和 S7-02，因为 RAG 已经写进简历，需要先把这条线补稳。

## 6. 不在本阶段范围内

- 不接完整公开 benchmark 官方 harness
- 不追求 Hugging Face 排名或通用榜单分数
- 不把固定样本通过率包装成线上成功率
- 不为了 RAG 评测重写整个知识库模块
- 不新增无边界多 Agent / swarm / uncontrolled planner

## 7. 阶段完成标准

- 至少有一组可控面试知识语料，不再依赖临时上传内容解释 RAG 能力
- RAG chunk 能解释来源、标题、段落或 section 级 metadata
- `stage-7-rag-retrieval-set` 至少完成 MVP 固定 query 和报告
- `stage-7-injection-safety-set` 至少覆盖知识库、简历、工具结果三类外部内容注入
- `stage-7-tool-routing-set` 至少覆盖核心 Agent 工具的选择、参数、拒绝和审批路由
- 每个 suite 都沿用 Stage 6 证据规范：sample set、raw results、summary、report、baseline、diff

## 8. Agent Evals 目录规则

`docs/agent-evals/` 只保存已经有明确运行入口或即将实现的 eval 使用说明。

Stage 7 的计划不再单独放 `next-eval-suite-plan.md`，而是收口在本阶段文档和 S7 任务文档里。等对应 runner 落地后，再新增：

- `docs/agent-evals/stage-7-rag-retrieval-set.md`
- `docs/agent-evals/stage-7-injection-safety-set.md`
- `docs/agent-evals/stage-7-tool-routing-set.md`

## 9. 对外表述边界

可以写：

> 在已有 Agent runtime 固定评测基础上，补充 RAG 检索、外部内容注入安全和工具路由评测，覆盖知识来源、检索命中、无答案拒答、审批绕过阻断和工具参数契约。

不要写：

> 通过 BFCL / AgentDojo / RAGBench 官方 benchmark。

除非后续真的接入官方 harness，并能保留可复现实验配置。
