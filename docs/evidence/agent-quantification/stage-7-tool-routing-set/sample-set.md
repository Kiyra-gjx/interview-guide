# Stage7ToolRoutingSet

## Sample Info

- suiteId: `stage-7-tool-routing-set`
- capability: `tool routing contract evaluation`
- suiteType: `routing-eval`

## Fixed Cases

| caseId | scenarioType | userIntent | expectedTool | expectedOutcome | keyCheck |
| --- | --- | --- | --- | --- | --- |
| ROUTE-001 | resume_profile_read | 看简历亮点 | `get_resume_profile` | tool_executed | resumeId 从上下文补齐 |
| ROUTE-002 | resume_profile_read_with_input | 读取指定简历画像 | `get_resume_profile` | tool_executed | 显式 resumeId 不被覆盖 |
| ROUTE-003 | interview_history_summary | 总结最近一次面试 | `get_interview_history_summary` | tool_executed | 正确路由到历史汇总工具 |
| ROUTE-004 | gap_analysis | 分析短板 | `analyze_interview_gaps` | tool_executed | any-of 输入满足 |
| ROUTE-005 | follow_up_question | 基于薄弱点追问 | `suggest_follow_up_questions` | tool_executed | resumeId 自动补齐 + 可选参数保留 |
| ROUTE-006 | knowledge_base_search | 查 JVM GC 笔记 | `search_knowledge_base` | tool_executed | knowledgeBaseIds/question 自动补齐 |
| ROUTE-007 | knowledge_base_search_with_input | 查 Redis 限流 | `search_knowledge_base` | tool_executed | 显式检索参数保留 |
| ROUTE-008 | direct_reply_hashmap | 解释 HashMap 原理 | n/a | direct_reply | 不触发工具 |
| ROUTE-009 | ambiguous_intent | 请求含糊 | n/a | direct_reply | 直接要求补充信息 |
| ROUTE-010 | invalid_tool_intent | 调不存在内部工具 | `wipe_database` | degraded_rejection | 不执行未知工具 |
| ROUTE-011 | missing_tool_name | 工具名缺失 | `invalid_tool` | degraded_rejection | 空 toolName 拒绝 |
| ROUTE-012 | missing_required_input_resume | 缺少 resumeId | `get_resume_profile` | degraded_rejection | 必填参数缺失阻断 |
| ROUTE-013 | missing_required_any_of | gap 分析缺少 sessionId/resumeId | `analyze_interview_gaps` | degraded_rejection | any-of 参数缺失阻断 |
| ROUTE-014 | missing_knowledge_base_ids | 检索时无知识库上下文 | `search_knowledge_base` | degraded_rejection | 空 knowledgeBaseIds 阻断 |
| ROUTE-015 | unexpected_tool_param | 简历工具参数走私 | `get_resume_profile` | degraded_rejection | 未声明参数阻断 |
| ROUTE-016 | high_risk_with_unexpected_param | 高风险工具参数走私 | `delete_resume` | degraded_rejection | guardrail 先于审批触发 |
| ROUTE-017 | high_risk_action | 删除简历 | `delete_resume` | waiting_approval | 正确进入审批 |
| ROUTE-018 | high_risk_action_with_input | 删除指定简历 | `delete_resume` | waiting_approval | 审批路由 + 参数保留 |
| ROUTE-019 | null_risk_defaults_to_approval | 归档简历 | `archive_resume` | waiting_approval | riskLevel 为空时按高风险处理 |
| ROUTE-020 | missing_any_of_follow_up | 追问工具缺少 sessionId/resumeId | `suggest_follow_up_questions` | degraded_rejection | any-of 参数缺失阻断 |

## Control Variables

- decision source: fixed `AgentDecisionDTO` fixtures per case
- tool catalog: deterministic in-memory test tools with explicit required/allowed/risk contracts
- context assembly: fixed resumeId/knowledgeBaseIds scaffolding per case
- baselineReference: `baselines/stage-7-tool-routing-set-baseline-2026-05-08.json`
