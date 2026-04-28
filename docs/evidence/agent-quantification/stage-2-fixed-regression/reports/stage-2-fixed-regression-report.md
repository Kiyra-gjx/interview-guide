# Stage 2 Agent Eval Report

- suite: stage-2-fixed-regression
- generatedAt: 2026-04-28T13:13:08.268434200
- totalCases: 5
- passedCases: 5
- 成功率: 20.0% (1/5)
- 降级率: 40.0% (2/5)
- 等待审批率: 20.0% (1/5)
- 错误率: 20.0% (1/5)
- 平均延迟: 539 ms
- 最大延迟: 2563 ms
- guardrail 命中样例数: 3
- approval 状态分布: {APPROVED=1, PENDING=1, REJECTED=1}

| Case | Expected | Actual | Approval | Guardrails | LatencyMs | Passed | Note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| tool_execution_success | SUCCESS | SUCCESS | APPROVED | 0 | 2563 | true | 审批通过后执行冻结工具输入 |
| input_guardrail_rejection | DEGRADED | DEGRADED | NONE | 1 | 32 | true | 输入 guardrail 阻止内部信息泄露 |
| waiting_for_approval | WAITING_APPROVAL | WAITING_APPROVAL | PENDING | 1 | 42 | true | 高风险工具进入待审批停靠 |
| approval_rejected | DEGRADED | DEGRADED | REJECTED | 1 | 30 | true | 审批被拒绝后直接降级收口 |
| stale_turn_failure | ERROR | ERROR | NONE | 0 | 29 | true | BusinessException: 当前 turn 已过期并被回收 |
