# Stage 7 Tool Routing Set Report

- suite: stage-7-tool-routing-set
- evaluationMode: fixed structured decision fixtures (local routing contract, not live model intent classification)
- generatedAt: 2026-05-08T18:56:19.566928300
- totalCases: 20
- passedCases: 20
- toolSelectionAccuracy: 100.0%
- paramAccuracy: 100.0%
- rejectionAccuracy: 100.0%
- directReplyAccuracy: 100.0%
- approvalRoutingAccuracy: 100.0%
- unexpectedToolExecutionCount: 0
- averageLatencyMs: 149
- maxLatencyMs: 2640

| Case | Type | ExpectedOutcome | ActualOutcome | ExpectedTool | ActualTool | ToolSelectionMatched | ParamMatched | RejectionMatched | DirectReplyMatched | ApprovalRoutingMatched | UnexpectedToolExecution | Passed | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ROUTE-001 | resume_profile_read | TOOL_EXECUTED | TOOL_EXECUTED | get_resume_profile | get_resume_profile | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-002 | resume_profile_read_with_input | TOOL_EXECUTED | TOOL_EXECUTED | get_resume_profile | get_resume_profile | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-003 | interview_history_summary | TOOL_EXECUTED | TOOL_EXECUTED | get_interview_history_summary | get_interview_history_summary | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-004 | gap_analysis | TOOL_EXECUTED | TOOL_EXECUTED | analyze_interview_gaps | analyze_interview_gaps | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-005 | follow_up_question | TOOL_EXECUTED | TOOL_EXECUTED | suggest_follow_up_questions | suggest_follow_up_questions | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-006 | knowledge_base_search | TOOL_EXECUTED | TOOL_EXECUTED | search_knowledge_base | search_knowledge_base | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-007 | knowledge_base_search_with_input | TOOL_EXECUTED | TOOL_EXECUTED | search_knowledge_base | search_knowledge_base | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-008 | direct_reply_hashmap | DIRECT_REPLY | DIRECT_REPLY | n/a | direct_answer | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-009 | ambiguous_intent | DIRECT_REPLY | DIRECT_REPLY | n/a | direct_answer | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-010 | invalid_tool_intent | DEGRADED_REJECTION | DEGRADED_REJECTION | wipe_database | wipe_database | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-011 | missing_tool_name | DEGRADED_REJECTION | DEGRADED_REJECTION | invalid_tool | invalid_tool | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-012 | missing_required_input_resume | DEGRADED_REJECTION | DEGRADED_REJECTION | get_resume_profile | get_resume_profile | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-013 | missing_required_any_of | DEGRADED_REJECTION | DEGRADED_REJECTION | analyze_interview_gaps | analyze_interview_gaps | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-014 | missing_knowledge_base_ids | DEGRADED_REJECTION | DEGRADED_REJECTION | search_knowledge_base | search_knowledge_base | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-015 | unexpected_tool_param | DEGRADED_REJECTION | DEGRADED_REJECTION | get_resume_profile | get_resume_profile | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-016 | high_risk_with_unexpected_param | DEGRADED_REJECTION | DEGRADED_REJECTION | delete_resume | delete_resume | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-017 | high_risk_action | WAITING_APPROVAL | WAITING_APPROVAL | delete_resume | delete_resume | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-018 | high_risk_action_with_input | WAITING_APPROVAL | WAITING_APPROVAL | delete_resume | delete_resume | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-019 | null_risk_defaults_to_approval | WAITING_APPROVAL | WAITING_APPROVAL | archive_resume | archive_resume | true | true | true | true | true | false | true | tool routing contract matched |
| ROUTE-020 | missing_any_of_follow_up | DEGRADED_REJECTION | DEGRADED_REJECTION | suggest_follow_up_questions | suggest_follow_up_questions | true | true | true | true | true | false | true | tool routing contract matched |
