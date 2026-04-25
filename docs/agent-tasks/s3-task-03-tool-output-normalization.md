# S3-03：Tool Output Normalization

## 0. 任务状态

- 状态：已完成
- 当前定位：Stage 3 的输出收口任务，已作为 Stage 3 最后一块缺口完成落地
- 前置依赖：S3-01、S3-02 已具备可消费的工具与上下文输入

## 0.1 已完成项

- `AgentToolResult` 仍保留 `summary / answerPayload / debugPayload / confirmedFacts` 原始产出，但新增统一消费者视图：
  - `promptPayload()`：回答 Prompt 只读回答相关视图
  - `memoryProjection()`：memory 写回只读 summary / facts 视图
  - `toToolOutput()` / `tracePayload()`：trace 与 API 统一读 `toolOutput`
- 新增 `AgentToolOutputDTO` 与 `AgentToolOutputNormalizationDTO`，显式暴露 `answer / debug / facts / normalization`
- `AgentPromptService` 改为消费统一回答视图，`AgentMemoryService` 改为复用统一 memory 投影，避免各链路自己拼字段
- `AgentTraceService` 改为写入统一 `toolOutputJson` 结构，同时兼容读取历史 `answerPayload / debugPayload / confirmedFacts` 字段
- `AgentTraceDTO` 与 `frontend/src/types/agent.ts` 已新增 `toolOutput` 结构化字段，前端可以直接消费统一视图
- `AgentGuardrailService` 已补对结构化内部字段泄漏的拦截，避免直接暴露 `toolOutput.normalization`、`debugPayload` 等内部结构
- 已补齐 `AgentToolResultTest`、`AgentMemoryServiceTest`、`AgentPromptServiceTest`、`AgentTraceServiceTest` 与相关回归测试

## 0.2 本轮复核结论

- Prompt、Memory、Trace / API 现在都直接消费自己的视图，不再自行组装 Tool 原始结果
- 历史 trace 无需迁移，仍可通过兼容读取逻辑恢复到统一 `toolOutput`
- 当前未发现阻塞 S3-03 达标的已知正确性缺口

## 1. 任务目标

把不同 Tool 的输出统一成稳定的回答层、调试层与事实层契约，降低主链路复杂度。

## 2. 要解决的问题

- 不同工具输出结构差异大，回答链路和调试链路难以统一消费
- 输出过长、粒度不一致或字段混杂时，Prompt 组装很容易失控
- trace 中能看到结果，但很难快速提炼“回答需要什么”和“调试需要什么”

## 3. 本任务范围

- Tool 输出字段与层次归一化
- 输出长度控制与裁剪策略
- answer / debug / facts 边界统一
- Prompt、memory、trace 与前端 DTO 对统一输出结构的消费

## 4. 主要改动点

- `app/src/main/java/interview/guide/modules/agent/support/AgentToolResult.java`
- `app/src/main/java/interview/guide/modules/agent/model/AgentToolOutputDTO.java`
- `app/src/main/java/interview/guide/modules/agent/model/AgentToolOutputNormalizationDTO.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentPromptService.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentMemoryService.java`
- `app/src/main/java/interview/guide/modules/agent/service/AgentTraceService.java`
- `app/src/main/java/interview/guide/modules/agent/model/AgentTraceDTO.java`
- `frontend/src/types/agent.ts`

## 5. 风险与边界

- 本任务不新增复杂 UI，不承担 Stage 4 的工作台展示职责
- 输出统一不能以牺牲关键信息为代价，调试链路必须保留必要细节
- 不把工具输出归一化做成过早抽象，先收口现有主流模式

## 6. 完成标准

- 不同工具的输出可以稳定映射到统一的回答层与调试层结构
- Prompt 消费工具输出时不再依赖大量工具特例分支
- memory、trace 与主回答能对同一份工具结果给出一致解释

## 7. 验证要求

- 至少覆盖多种工具输出形态的归一化测试
- 回答层与调试层都能从统一结构中读取到所需字段
- 输出长度控制后，不会破坏主回答质量与排障可读性
