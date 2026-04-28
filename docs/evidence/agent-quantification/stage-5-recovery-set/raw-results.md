# stage-5-recovery-set Raw Results

## 运行信息

- executedAt:
- runner:
- reportPath:
- baselinePath:

## case 级结果

| caseId | recoveryType | expectedTerminalState | actualTerminalState | wrongStateContinued | replayedSideEffect | passed | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |  |

## 说明

- `wrongStateContinued=true` 表示本该失败/收尾却继续跑
- `replayedSideEffect=true` 表示本该阻断重放却再次执行副作用
- `passed` 以终态语义、恢复边界和副作用控制共同判定
