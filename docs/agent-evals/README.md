# Agent Eval 文档索引

## 定位

`docs/agent-evals/` 只放已经有明确运行入口，或即将由任务落地的 eval 使用说明。

阶段规划、任务拆分和优先级不再放在本目录里，统一放到：

- `docs/agent-stages/`
- `docs/agent-tasks/`

## 当前已落地 eval

- `stage-2-regression.md`
- `stage-2-safety-set.md`
- `stage-3-context-set.md`
- `stage-3-memory-set.md`
- `stage-5-recovery-set.md`
- `stage-5-benchmark.md`
- `stage-7-rag-retrieval-set.md`

## 后续 Stage 7 eval

Stage 7 的计划入口是：

- `docs/agent-stages/stage-7-rag-trust-tool-routing-and-injection-safety.md`

对应 runner 落地后，再补充以下使用说明：

- `stage-7-injection-safety-set.md`
- `stage-7-tool-routing-set.md`

## 规则

- 没有 runner 的内容，不单独放成 eval 使用说明
- 没有 sample set / raw results / summary 的内容，不写成已完成 eval
- 公开 benchmark 只作为参考维度，除非接入官方 harness，否则不写“通过官方 benchmark”
