# 仓库地图

## 后端

- 启动入口：`app/src/main/java/interview/guide/App.java`
- 共享代码：
  - `common/ai`：AI 错误翻译、结构化输出封装
  - `common/annotation`、`common/aspect`：限流
  - `common/async`：Redis Stream 生产者/消费者模板
  - `common/config`：配置类与基础设施装配
  - `common/exception`：业务/AI/全局异常处理
  - `common/result`：统一 `Result<T>` 返回体
- 基础设施：
  - `infrastructure/file`：上传、解析、校验、清洗、哈希
  - `infrastructure/export`：PDF 导出
  - `infrastructure/redis`：缓存与 Redis 工具
  - `infrastructure/mapper`：MapStruct 映射
- 业务模块：
  - `modules/resume`
  - `modules/interview`
  - `modules/knowledgebase`

## 前端

- 应用壳和路由：`frontend/src/App.tsx`
- 请求层：`frontend/src/api/request.ts`
- API 封装：`frontend/src/api/*.ts`
- 页面：`frontend/src/pages/*.tsx`
- 公共组件：`frontend/src/components/*.tsx`
- 类型定义：`frontend/src/types/*.ts`

## Prompt 与配置

- AI 提示词模板：`app/src/main/resources/prompts`
- 主配置：`app/src/main/resources/application.yml`
- 环境变量示例：`.env.example`
- 本地容器编排：`docker-compose.yml`

## 测试

- 后端测试：`app/src/test/java`
- 解析测试样例文件：`app/src/test/resources/test-files`
- 仓库里有 Redis 集成测试，但部分场景需要本地 Redis，且有些用例默认禁用

## 高风险联动点

- 后端 `Result<T>` 契约 <-> 前端 `request.ts`
- 后端 DTO 字段 <-> 前端 `types/*.ts`
- Controller 路径 <-> 前端 `api/*.ts`
- 异步任务状态 <-> 前端轮询和状态渲染
- Prompt 模板 <-> 结构化解析与 service 逻辑
