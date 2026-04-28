# Stage5RecoverySet

## 样本信息

- suiteId: `stage-5-recovery-set`
- capability: `任务恢复`
- suiteType: `quantification`

## 固定样本清单

| caseId | scenarioType | intent | setup | verifier | notes |
| --- | --- | --- | --- | --- | --- |
| RCV-01 | reject_pending_approval | 验证审批拒绝后直接收口，不执行工具 | waiting approval turn + reject | stopReason=`APPROVAL_REJECTED`，无工具执行 | 对应 reject approval 测试 |
| RCV-02 | expire_stale_pending_approval | 验证过期审批会在新 turn 前被回收 | stale pending approval + new chat | 旧 turn 正确降级，新 turn 正常启动 | 对应 expire stale approval 测试 |
| RCV-03 | replay_block_after_started_execution | 验证审批通过后若工具已开始执行，则阻断自动重放 | approved approval + running trace | stopReason=`APPROVAL_REPLAY_BLOCKED` | 关键副作用保护 |
| RCV-04 | recover_from_trace_terminal_reply | 验证恢复快照时优先读取 trace 终态 reply | stale waiting message + terminal trace payload | 回复来自 trace，不来自陈旧消息 | 对应 approved snapshot 测试 |
| RCV-05 | approval_resume_failure | 验证审批恢复失败映射到专用 stop reason | approved approval + resume failure | stopReason=`APPROVAL_RESUME_FAILED` | 边界正确性 case |
| RCV-06 | stale_turn_explicit_failure | 验证过期 turn 显式失败，而不是伪成功 | aborted turn + chat path | 暴露过期错误 | 对应 stale turn 测试 |
| RCV-07 | budget_exhausted_terminal_trace | 验证预算耗尽时写入 dedicated trace 终态 | multi-step enabled + step budget exhausted | terminal=`EXHAUSTED`，trace 持久化成功 | 对应 bounded loop trace 测试 |
| RCV-08 | reject_handoff_on_single_step | 验证单步路径拒绝委派后不会误继续执行 | single-step + handoff request | stopReason=`HANDOFF_NOT_ALLOWED`，无 side effect replay | 受控委派边界 |
| RCV-09 | recover_handoff_success_without_degraded_terminal | 验证成功 handoff 不被误写成 degraded terminal | multi-step + read-only handoff success | terminal=`SUCCESS` 或后续 direct reply 收口 | 对应 trace semantics 测试 |

## 控制变量

- model: 优先用 mock / fixed decision
- runtimeConfig: recovery 与 multi-step case 需显式配置
- approvalMode: `pending / approved / rejected / expired`
- baselineReference: `docs/evidence/agent-quantification/stage-5-recovery-set/baselines/`
