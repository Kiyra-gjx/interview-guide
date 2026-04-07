# 开发工作流

## 1. 改之前先定位代码切片

- 页面问题：从 `frontend/src/pages` 或 `frontend/src/components` 开始，再追到 `frontend/src/api`，最后到对应的后端 controller/service。
- API 问题：从后端 controller 开始，再追 service、repository、共享基础设施，最后看前端调用方。
- AI 问题：同时定位 service 类、prompt 模板和 `common/ai/StructuredOutputInvoker.java`。
- 异步问题：同时定位 stream producer、consumer、重试/状态更新逻辑，以及前端轮询 UI。

## 2. 保持仓库特有契约

- 标准 JSON 接口保持 `Result<T>` 包装。
- 不要悄悄改掉前端已经在消费的字段名。
- 异常处理尽量保持和 `GlobalExceptionHandler` 一致。
- 保持清晰分层：controller -> service -> repository/infrastructure。

## 3. 做定向验证

- 仅后端逻辑改动：优先 `:app:compileJava` 加定向测试。
- 解析/文件/AI 改动：优先补或跑最近的后端测试。
- 前端改动：优先 `pnpm build`；行为变更时同时检查路由和 API 类型。
- 跨层改动：只要请求/响应契约变了，就同时验证后端编译和前端构建。

## 4. 重点关注这些热点文件

- `common/ai/StructuredOutputInvoker.java`
- `common/async/AbstractStreamConsumer.java`
- `common/exception/GlobalExceptionHandler.java`
- `common/result/Result.java`
- `frontend/src/api/request.ts`
- `app/src/main/resources/prompts/*`

## 5. 明确说明剩余风险

如果本地无法运行 Redis、PostgreSQL、对象存储或 Docker 相关链路，要明确指出哪些运行路径仍未验证。
