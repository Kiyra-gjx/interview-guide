# Agent Runtime 项目完整介绍

## 面试辅助 Agent Runtime 与 RAG 知识库平台

核心技术：Java 21、Spring Boot 4、Spring AI、Agent Runtime、Tool Calling、RAG、Context Management、Layered Memory、Checkpoint / Resume、Run Trace、PostgreSQL / pgvector、Redis Stream、RustFS、Apache Tika

## 项目架构：

项目采用 Spring Boot 分层架构，整体分为 Agent Runtime 层、Tool Calling 层、RAG 知识库层、运行治理层和评测证据层。

- Agent Runtime 层负责会话创建、上下文装配、模型决策、工具执行、多步预算控制、终态收口和 trace 记录
- Tool Calling 层将简历读取、知识库检索、面试历史分析、追问建议等能力封装成标准 Agent Tool，并统一做参数校验、风险分级和输出归一化
- RAG 知识库层支持文件上传、文本解析、异步向量化、pgvector 相似度检索、query rewrite、动态 topK / minScore 检索和调试信息回传
- 运行治理层覆盖高风险工具审批、approval resume、replay blocked、副作用重放阻断、过期 turn 回收和降级收口
- 评测证据层通过固定样本集、JSON / Markdown 报告、baseline / diff 和 case 级 raw results，沉淀可复核的简历量化证据

## 项目描述：

面向面试辅助和个人知识库问答场景，设计并实现一套 Agent Runtime 工程化链路，围绕模型接入、工具调用、RAG 检索、上下文治理、结构化记忆、审批恢复、运行审计和评测闭环做系统化设计。项目重点解决多轮 Agent 任务中的 prompt 膨胀、重复读取上下文、知识库弱相关召回、状态丢失、工具副作用不可控和结果难复盘等问题，并通过固定样本集验证运行时契约和治理边界。

## 核心职责与贡献：

1. **Agent Runtime 架构设计**：负责 Agent 主链路设计与实现，将会话状态、上下文装配、模型决策、工具调用、终态收口和运行 trace 串成可审计执行链路；围绕 `DIRECT_REPLY / TOOL_CALL / WAITING_APPROVAL / DEGRADED / FAILED / EXHAUSTED / SUCCESS` 等关键终态建立执行契约，并通过 Stage 2 / Stage 3 / Stage 5 固定样本集沉淀 JSON、Markdown、baseline 与 diff 报告。

2. **RAG 知识库与 Agent Tool 接入**：实现知识库上传、Apache Tika 文本解析、RustFS 文件存储、Redis Stream 异步向量化和 PostgreSQL / pgvector 检索链路；设计 `search_knowledge_base` 只读工具，将知识库检索结果、命中数量、检索 query、候选片段等调试信息写入结构化 `answerPayload / debugPayload / confirmedFacts`，使 RAG 能被 Agent 统一调度、审计和写回 memory。

3. **长上下文治理**：设计分层上下文装配与 budget 裁剪机制，按 latest request、goal、memory、resource bindings、confirmed facts、used tools 等 section 组织 prompt；在 `10` 组固定上下文配置中，将平均上下文长度从 `347.3` 压到 `256.1`，平均压缩率 `16.09%`，最高压缩率 `63.53%`，关键 section 保留率 `100%`，未出现 `requestBroken`。

4. **结构化记忆系统**：实现 memory phase 推进、facts 去重与截断、nextFocus 优先级、委派结果写回和 follow-up 复用机制，避免 Agent 在多轮任务中重复读取同一资源或重复确认已知事实；在 `9` 组固定 memory 样本中，将重复工具读取从 `3` 次降到 `0`，重复事实确认从 `2` 次降到 `0`，且 `extraCallsAfterMemoryReady=0`。

5. **任务恢复与工具安全治理**：设计高风险工具审批、approval reject / resume、trace 终态恢复、replay blocked、过期 turn 失败收口和预算耗尽终态语义；在 `9` 组固定恢复场景中恢复正确率 `100%`，`wrongStateContinued=0`，`replayedSideEffect=0`；在 `10` 组固定安全场景中验证高风险动作审批命中率 `100%`、审批拒绝后降级收口率 `100%`，未出现绕过审批直接执行。

## 可追溯证据：

- Stage 6 总结：`docs/agent-stages/stage-6-evidence-benchmark-and-resume-quantification.md`
- 简历初稿：`docs/agent-resume-project-draft.md`
- 证据目录：`docs/evidence/agent-quantification/README.md`
- Context 证据：`docs/evidence/agent-quantification/stage-3-context-set/summary.md`
- Memory 证据：`docs/evidence/agent-quantification/stage-3-memory-set/summary.md`
- Recovery 证据：`docs/evidence/agent-quantification/stage-5-recovery-set/summary.md`
- Safety 证据：`docs/evidence/agent-quantification/stage-2-safety-set/summary.md`
