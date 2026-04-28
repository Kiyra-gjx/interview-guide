# stage-3-context-set

## 套件定位

- suiteId: `stage-3-context-set`
- capability: `长上下文治理`
- stage: `Stage 3`
- suiteType: `quantification`

## 目标

这套固定样本只回答一件事：当前的 context assembly 机制，能否在有限 budget 下稳定保留当前请求理解所需的信息，并对裁剪结果给出可解释记录。

## 代码与测试入口

- 代码入口：`app/src/main/java/interview/guide/modules/agent/service/AgentContextAssemblyService.java`
- 当前测试基线：`app/src/test/java/interview/guide/modules/agent/service/AgentContextAssemblyServiceTest.java`

## 证据要求

- `sample-set.md`：固定 case 清单与验收规则
- `raw-results.md`：逐 case 原始记录
- `summary.md`：汇总指标与是否可写简历的判断
- `baselines/`：改动前上下文基线
- `reports/`：JSON / Markdown / diff 报告
- `traces/`：代表性 section 截图、API 输出或 workbench 观察结果

## 当前边界

- 这套样本关注的是 `budget / section status / requestBroken`
- 不把 prompt 长度变化直接等同于回答质量提升
- 没有 case 级原始记录时，不允许只报平均压缩率
