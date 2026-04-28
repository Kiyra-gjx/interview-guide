# Stage 5 Benchmark Traces

建议至少补 2 组代表性证据：

- `bounded_handoff_success` 的 workbench trace 截图或 turn detail 输出
- `approval_replay_blocked` 的 terminal payload / trace 输出

这两组证据最能说明：

- handoff 是受控只读委派，而不是无限扩散执行
- approval 恢复不会盲目重放副作用
