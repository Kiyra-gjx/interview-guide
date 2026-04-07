# 审查热点

## 后端共享热点文件

- `app/src/main/java/interview/guide/common/result/Result.java`
- `app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java`
- `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java`
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java`
- `app/src/main/java/interview/guide/infrastructure/redis/*`
- `app/src/main/java/interview/guide/infrastructure/file/*`
- `app/src/main/java/interview/guide/infrastructure/export/PdfExportService.java`

## 模块热点

- Resume：
  - 上传 / 重新分析链路
  - 重复检测
  - 分析历史 / 详情 / 导出
- Interview：
  - 会话缓存和持久化
  - 答案分批评估
  - 报告导出
- Knowledge Base：
  - 向量化 consumer
  - RAG 查询 / 会话历史
  - 上传 / 管理 / 聊天页面联动

## 前端热点

- `frontend/src/api/request.ts`
- `frontend/src/api/*.ts`
- `frontend/src/types/*.ts`
- `frontend/src/pages/*.tsx`
- `frontend/src/components/*Panel.tsx`

## 这个仓库里常见的审查陷阱

- 后端响应改了，但前端调用方没同步更新
- Prompt 文案改了，但 parser / service 假设没同步更新
- 异步重试链路改了，但持久化状态或错误信息没同步更新
- 导出接口误改成 `Result<T>`，或普通 JSON 接口误改成原始响应
- 引入了新的环境变量 / 配置依赖，但没有在配置或 Docker 文件中体现
