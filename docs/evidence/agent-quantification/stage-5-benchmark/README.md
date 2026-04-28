# stage-5-benchmark

## 套件定位

- suiteId: `stage-5-benchmark`
- capability: `Stage 5 多步能力基线`
- stage: `Stage 5`
- suiteType: `benchmark`

## 目标

这套 benchmark 不回答“Agent 整体有多强”，只回答 4 个更工程化的问题：

- 多步只读委派能不能回主链路并成功收口
- 单步路径下 handoff 会不会被显式拒绝
- step budget 耗尽时会不会停在明确的 exhausted 终态
- approval 恢复状态不明确时会不会阻断重复副作用执行

## 当前基线

- 固定样本数：`4`
- 已归档报告：
  - `reports/stage-5-benchmark-report.json`
  - `reports/stage-5-benchmark-report.md`
  - `reports/stage-5-benchmark-diff.md`
- 当前 baseline：`baselines/stage-5-benchmark-baseline-2026-04-28.json`

## 简历使用边界

- 可以写：固定 benchmark 下的 stop reason / terminal state 覆盖、受控委派正反例、replay blocked 保护
- 谨慎写：平均步数
- 不要写：离线 mock latency、把 4 个固定 case 的通过率包装成“Agent 成功率”
