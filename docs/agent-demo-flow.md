# Agent Demo Flow

## 目标

这份文档对应 Stage 4 的 `S4-02：Debuggable Demo Flow`，用于说明：

- 如何启动 Agent Workbench 的默认演示路径
- 如何在页面里观察一次执行为什么成功、为什么降级
- 真实演示时应该先看哪一层，再看哪一层

这份说明面向当前仓库里的真实能力，不依赖额外假数据。

## 前置准备

- 至少准备一份已可用的简历，用于“简历画像成功路径”
- 可选：准备一个已完成向量化的知识库，用于“知识库检索成功路径”
- 前端进入 `/agent`
- 后端 Agent 能力、简历查询、知识库查询接口已可用

## 默认演示路径

### 1. 简历画像成功路径

适用目标：

- 展示 `SUCCESS` 收口
- 展示 `get_resume_profile` 的 `toolOutput`
- 展示 `Session Memory` 的事实写回

操作步骤：

1. 在工作台顶部的 `Demo Flow 场景` 里选择 `简历画像成功路径`
2. 绑定一份简历
3. 点击 `创建 Agent 会话`
4. 直接发起当前输入区里的问题

预期观察：

- 用户视角会看到基于简历画像生成的最终回复
- `Session Memory` 会出现新的事实或工具名
- `Trace Browser` 可以看到 `get_resume_profile` 的结构化 `toolOutput`
- 执行解释面板会显示“本轮已成功收口”

### 2. 知识库检索成功路径

适用目标：

- 展示 `SUCCESS` 收口
- 展示 `answer / debug / facts` 的统一视图
- 展示知识库检索类工具如何被工作台解释

操作步骤：

1. 在 `Demo Flow 场景` 里选择 `知识库检索成功路径`
2. 勾选至少一个知识库
3. 点击 `创建 Agent 会话`
4. 发起当前输入区里的问题

预期观察：

- 用户视角会出现围绕知识库问题的最终回答
- `Trace Browser` 可看到检索类 `toolOutput`
- 如果命中不足，可以继续看 `debug` 里的检索信息

### 3. 内部字段请求降级路径

适用目标：

- 稳定展示 `DEGRADED` 收口
- 展示输入 guardrail 如何拦截内部字段暴露请求
- 展示“失败原因可解释，但不暴露内部数据”的演示叙事

操作步骤：

1. 在 `Demo Flow 场景` 里选择 `内部字段请求降级路径`
2. 点击 `创建 Agent 会话`
3. 发起当前输入区里的问题

预期观察：

- 用户视角不会看到内部 `toolOutput`、`normalization` 或调试字段
- 执行解释面板会显示“本轮因 guardrail 命中而降级收口”
- `Trace Browser` 能看到 guardrail 原因与收口路径

## 页面里的观察顺序

推荐按下面顺序讲解，不要一上来就直接滚到原始 JSON：

1. 先看顶部的 `Demo Flow 场景`
2. 再看右侧的执行解释面板
3. 再看中间的用户视角
4. 然后看 `Session Memory`
5. 最后再进入 `Trace Browser`

这样讲解时能先说“发生了什么”，再说“为什么发生”。

## 如何解释结果

### SUCCESS

- 表示这一轮已经正常收口
- 优先看：是否用了工具、工具结果是否写回 memory、最终回复是否和 `toolOutput` 一致

### DEGRADED

- 表示系统没有直接失败，而是走了保守收口
- 优先看：guardrail 原因、fallback 回复、用户是否还能得到安全但可用的结果

### WAITING_APPROVAL

- 表示高风险动作被暂停，等待显式审批
- 优先看：审批原因、审批状态、对应 trace 是否停在等待审批

### FAILED

- 表示这一轮没有成功收口
- 优先看：turn 错误信息、trace 里的失败 step、原始输入输出

## 当前边界

- 当前默认 live demo 主路径重点覆盖 `SUCCESS` 与 `DEGRADED`
- 这是因为当前已注册的默认工具主要是只读型能力
- 审批链路已经被工作台和运行时支持，但要进入 `WAITING_APPROVAL`，还需要高风险工具或已有审批数据
- 审批语义的验证可参考 `docs/agent-evals/stage-2-regression.md`

## 结论

Stage 4 的默认演示方式不是“把所有底层数据都摊开”，而是：

- 先选一条固定场景
- 再看一次执行如何收口
- 再用 Memory 和 Trace 去证明这个解释是真的
