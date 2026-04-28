# stage-3-memory-set

## 套件定位

- suiteId: `stage-3-memory-set`
- capability: `结构化记忆`
- stage: `Stage 3`
- suiteType: `quantification`

## 目标

这套固定样本用来验证 memory 是否真的减少重复劳动，而不是只证明“系统里有 memory 字段”。

## 代码与测试入口

- 代码入口：`app/src/main/java/interview/guide/modules/agent/service/AgentMemoryService.java`
- 关联入口：`app/src/main/java/interview/guide/modules/agent/service/AgentContextAssemblyService.java`
- 当前测试基线：
  - `app/src/test/java/interview/guide/modules/agent/service/AgentMemoryServiceTest.java`
  - `app/src/test/java/interview/guide/modules/agent/service/AgentOrchestratorTest.java`

## 证据要求

- 必须保留 before / after 或 memory hit / no hit 对照
- 必须能解释 `repeatedToolCalls` 与 `repeatedFactChecks` 的判定规则
- 必须区分“memory 归一化正确”与“follow-up 真正减少重复调用”
