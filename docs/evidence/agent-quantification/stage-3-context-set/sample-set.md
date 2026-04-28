# Stage3ContextSet

## 样本信息

- suiteId: `stage-3-context-set`
- capability: `长上下文治理`
- suiteType: `quantification`

## 固定样本清单

| caseId | scenarioType | intent | setup | verifier | notes |
| --- | --- | --- | --- | --- | --- |
| CTX-01 | stable_priority_order | 验证多源上下文按稳定优先级装配 | resume + knowledge base + memory + latest user message | `latest_user_message / goal / memory_state / resource_bindings` 顺序稳定 | 对应 stable order 测试 |
| CTX-02 | budget_trim_low_priority | 验证预算耗尽时先裁低优先级 section | 显式小 budget，facts 较长，used_tools 可省略 | `latest_user_message / goal / memory_state` 不被省略；低优先级 section 被 omitted/truncated | 对应 budget exhausted 测试 |
| CTX-03 | keep_latest_request_complete | 验证 budget 允许时保留完整最新请求和 goal | 长 goal + 当前请求 + 正常 budget | `latest_user_message` 与 `goal` 为 `INCLUDED` | 对应 keep latest request 测试 |
| CTX-04 | memory_goal_fallback | 验证缺少绑定信息时回退到 memory goal | session goal 缺失或绑定不可用 | `goal.reason = memory_goal_fallback` | 对应 missing bindings explainable 测试 |
| CTX-05 | hidden_summary_budget_alignment | 验证隐藏 section 不影响真实 budget 统计 | prompt summary 不展示全部 section | `usedChars == actual assembled cost` | 对应 budget usage alignment 测试 |
| CTX-06 | resume_only_request | 验证只绑定 resume 的轻量请求不会误带多余知识库信息 | resumeId only | `resource_bindings` 只包含 resumeId | 新增固定 case |
| CTX-07 | knowledge_base_only_request | 验证只绑定 knowledge base 的请求能保留资源绑定 | knowledgeBaseIds only | `resource_bindings` 含知识库 IDs，goal 不丢失 | 新增固定 case |
| CTX-08 | follow_up_memory_heavy | 验证 follow-up 场景下 facts 可截断但当前问题不被裁坏 | confirmedFacts 较长 + follow-up 问题 | `confirmed_facts` 可 `TRUNCATED`，`requestBroken=false` | 重点量化压缩收益 |
| CTX-09 | obvious_over_budget_request | 验证明显超预算场景仍有可解释 section status | 超长 user message + 多资源绑定 | 输出 `omittedSections / truncatedSections` 可解释 | 重点量化最高压缩率 |
| CTX-10 | missing_resource_bindings | 验证资源缺失不会让当前请求语义失真 | 无 resume、无 knowledge base | `resource_bindings` 可解释地为空；`requestBroken=false` | 用于简历边界说明 |

## 控制变量

- model: 不依赖真实模型，优先使用固定 context assembly 输出
- runtimeConfig: `multiStepEnabled=false`
- approvalMode: `not involved`
- baselineReference: `docs/evidence/agent-quantification/stage-3-context-set/baselines/`
