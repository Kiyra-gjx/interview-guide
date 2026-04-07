---
name: interview-guide-dev
description: 在 Interview Guide 仓库中进行代码定位、开发修改、问题排查和验证。适用于这个仓库里的 Spring Boot 后端、React 前端、Redis Stream 异步任务、Spring AI 提示词与结构化输出、文件解析、PDF 导出以及本地 Docker 环境相关工作。用户说“帮我看下这个仓库”“帮我定位这个 bug”“分析一下调用链”“这个需求要改哪些模块”“帮我改下这段代码”“看下前后端联动”这类话时应触发。
---

# Interview Guide 开发

先读 `references/repo-map.md`。如果任务涉及改代码、修 bug、做验证、排查环境相关问题，再读 `references/dev-workflow.md`。

## 先快速圈定范围

先把请求映射到一个或多个业务模块：

- `resume`：上传、解析、异步分析、简历详情/历史、PDF 导出
- `interview`：题目生成、会话缓存、答题提交、评估、报告导出
- `knowledgebase`：文档上传/向量化、问答、RAG 会话/历史

然后检查经常跨模块影响的共享层：

- `app/src/main/java/interview/guide/common`
- `app/src/main/java/interview/guide/infrastructure`
- `app/src/main/resources/prompts`
- `frontend/src/api`
- `frontend/src/types`

## 遵守仓库约定

- 除了文件导出这类明确返回原始二进制内容的接口，后端 API 默认应保持 `HTTP 200 + Result<T>` 结构。
- 默认认为前端 `frontend/src/api/request.ts` 会解包 `Result<T>`，并在 `code !== 200` 时直接报错。后端响应结构变更可能同时影响多个页面。
- 保持异步任务的状态流转和重试行为一致。简历分析、知识库向量化、面试评估都依赖 Redis Stream 生产者/消费者与持久化状态。
- 修改 AI 提示词或结构化输出时，要同时检查提示词模板和 Java 侧解析路径，不要把 prompt 改动当成纯文本调整。
- 优先做和改动范围匹配的定向验证，不要只给笼统结论。

## 必做联动检查

改后端 controller、DTO、enum 时：

- 检查 `frontend/src/api` 里对应的接口封装
- 检查 `frontend/src/types` 里对应的类型定义
- 检查实际渲染结果的页面或组件

改异步逻辑时：

- 检查 producer、consumer、持久化状态字段，以及前端轮询/渲染路径

改 AI 行为时：

- 检查 `app/src/main/resources/prompts` 下的提示词模板
- 检查 `common/ai/StructuredOutputInvoker.java`
- 检查组装 prompt 和消费解析结果的 service 代码

## 最低验证基线

- 后端编译：`.\gradlew.bat :app:compileJava`
- 后端测试：`.\gradlew.bat :app:test`
- 定向测试：`.\gradlew.bat :app:test --tests "interview.guide...."`
- 前端构建：在 `frontend/` 下执行 `pnpm build`

如果因为本地依赖缺失而无法验证，要明确说明具体缺了什么，以及哪些路径仍未验证。

