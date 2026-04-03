<div align="center">

<h1>Interview Guide Agent</h1>

基于 Spring AI 的面试 Agent / RAG / Workflow 实验项目（持续迭代中）

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18.3-blue?logo=react)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-blue?logo=typescript)](https://www.typescriptlang.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-336791?logo=postgresql)](https://www.postgresql.org/)
[![Stage](https://img.shields.io/badge/Stage-Agentizing-2563eb)](https://github.com/Kiyra-gjx/interview-guide)
[![Focus](https://img.shields.io/badge/Focus-Java%20Backend%20%2B%20Agent-0f766e)](https://github.com/Kiyra-gjx/interview-guide)
[![License](https://img.shields.io/github/license/Kiyra-gjx/interview-guide)](./LICENSE)

<p>
  <a href="https://github.com/Kiyra-gjx/interview-guide"><img src="https://img.shields.io/badge/Repo-Kiyra--gjx%2Finterview--guide-111827" alt="repo"></a>
  <a href="https://github.com/Snailclimb/interview-guide"><img src="https://img.shields.io/badge/Upstream-Snailclimb%2Finterview--guide-334155" alt="upstream"></a>
  <a href="#快速开始"><img src="https://img.shields.io/badge/Get%20Started-README-7c3aed" alt="get-started"></a>
</p>

<p>
  <a href="#项目快照">项目快照</a> •
  <a href="#界面预览">界面预览</a> •
  <a href="#系统架构">系统架构</a> •
  <a href="#上游来源与致谢">上游来源</a> •
  <a href="#技术栈">技术栈</a> •
  <a href="#快速开始">快速开始</a>
</p>

</div>


---

## 项目介绍

Interview Guide Agent 是我基于 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide) fork 并持续二次开发的项目。

当前它仍然以 `LLM + RAG + Structured Output + Redis Stream 工作流` 为核心，但目标不是停留在一个“会调用大模型的面试平台”，而是把它逐步演进为一个面向求职与面试场景的 `Agent` 项目。

> 这个仓库一边保留原有面试平台的完整业务链路，一边按 `Tool Calling -> Memory -> Agent Loop -> Eval/Trace` 的顺序逐步 Agent 化，目标是沉淀一个更适合 Java 后端转向 Agent 应用开发的长期项目。

## 项目快照

<table>
  <tr>
    <td valign="top" width="33%">
      <strong>Current Shape</strong>
      <br/>
      <br/>
      <strong>已具备</strong>
      <br/>
      - 简历解析与分析
      <br/>
      - 模拟面试与追问
      <br/>
      - RAG 问答与 SSE 对话
      <br/>
      - Redis Stream 异步任务
      <br/>
      - Structured Output 兜底
    </td>
    <td valign="top" width="33%">
      <strong>Agent Direction</strong>
      <br/>
      <br/>
      <strong>正在补齐</strong>
      <br/>
      - Tool Calling
      <br/>
      - Chat / Session Memory
      <br/>
      - 单 Agent 面试教练
      <br/>
      - Trace / Replay / Eval
      <br/>
      - Guardrails
    </td>
    <td valign="top" width="33%">
      <strong>Engineering Focus</strong>
      <br/>
      <br/>
      <strong>工程侧重点</strong>
      <br/>
      - Spring Boot 4 + Java 21
      <br/>
      - Spring AI 2.0
      <br/>
      - PostgreSQL + pgvector
      <br/>
      - Redis / Redis Stream
      <br/>
      - React + TypeScript
    </td>
  </tr>
</table>

## 项目状态

| 维度 | 当前状态 | 下一步 |
| --- | --- | --- |
| 产品定位 | AI 面试平台 | 面试场景 Agent |
| 推理模式 | Prompt + RAG + Workflow | Tool Calling + 多步决策 |
| 上下文 | 会话记录与业务状态 | 真正参与推理的 Memory |
| 工程能力 | 异步任务、状态流转、结构化输出 | Trace、Eval、Guardrails |
| 项目阶段 | `WIP` | 持续迭代 |

## 界面预览

<table>
  <tr>
    <td align="center" width="50%">
      <img src="https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-mock-interview.png" alt="mock interview" width="100%" />
      <br/>
      <strong>模拟面试</strong>
    </td>
    <td align="center" width="50%">
      <img src="https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-qa-assistant.png" alt="qa assistant" width="100%" />
      <br/>
      <strong>知识库问答</strong>
    </td>
  </tr>
  <tr>
    <td align="center" width="50%">
      <img src="https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-upload-analysis.png" alt="resume analysis" width="100%" />
      <br/>
      <strong>简历分析</strong>
    </td>
    <td align="center" width="50%">
      <img src="https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-knowledge-base-management.png" alt="knowledge base" width="100%" />
      <br/>
      <strong>知识库管理</strong>
    </td>
  </tr>
</table>

## 系统架构

**说明**：下面这张图更多反映当前基础平台形态，不完全等同于后续目标中的 Agent 架构。架构图采用 draw.io 绘制，导出为 svg 格式，在 Github Dark 模式下的显示效果会有问题。

![](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/interview-guide-architecture-diagram.svg)

**当前异步处理流程**：

简历分析、知识库向量化和面试报告生成采用 Redis Stream 异步处理，这里以简历分析和知识库向量化为例介绍一下整体流程：

```
上传请求 → 保存文件 → 发送消息到 Stream → 立即返回
                              ↓
                      Consumer 消费消息
                              ↓
                    执行分析/向量化任务
                              ↓
                      更新数据库状态
                              ↓
                   前端轮询获取最新状态
```

状态流转： `PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`。

**Agent 化演进方向**：

- 在问答链路中引入 `Tool Calling + Memory`，让会话不仅是“记录消息”，还能参与下一步推理
- 在模拟面试链路中引入单 Agent 模式，让系统能基于简历、知识库和历史表现自主决定追问、讲解和复盘策略
- 为 Agent 行为补齐 `trace / replay / eval`，把“能跑”升级为“可解释、可调试、可迭代”

## 上游来源与致谢

- 上游项目：[`Snailclimb/interview-guide`](https://github.com/Snailclimb/interview-guide)
- 当前仓库：[`Kiyra-gjx/interview-guide`](https://github.com/Kiyra-gjx/interview-guide)
- 这个仓库保留了上游项目中大量成熟的业务模块和工程结构，并在此基础上按我的 Agent 学习与实践路线继续演进
- 如果你想了解更原始、更完整的基础版实现，请优先参考上游仓库与其原始文档

## 技术栈

### 后端技术

| 技术                  | 版本  | 说明                      |
| --------------------- | ----- | ------------------------- |
| Spring Boot           | 4.0   | 应用框架                  |
| Java                  | 21    | 开发语言                  |
| Spring AI             | 2.0   | AI 集成框架               |
| PostgreSQL + pgvector | 14+   | 关系数据库 + 向量存储     |
| Redis                 | 6+    | 缓存 + 消息队列（Stream） |
| Apache Tika           | 2.9.2 | 文档解析                  |
| iText 8               | 8.0.5 | PDF 导出                  |
| MapStruct             | 1.6.3 | 对象映射                  |
| Gradle                | 8.14  | 构建工具                  |

技术选型常见问题解答：

1. 数据存储为什么选择 PostgreSQL + pgvector？我希望先把检索、会话、业务数据放在尽量少的基础设施里，降低工程复杂度，同时又能保留 RAG 所需的向量检索能力。
2. 为什么引入 Redis？
   - Redis 替代 `ConcurrentHashMap` 实现面试会话缓存和状态恢复。
   - Redis Stream 用于简历分析、知识库向量化、面试报告生成等异步任务，便于后续继续扩展 Agent 任务编排。
3. 构建工具为什么选择 Gradle？当前项目依赖较多，Gradle 在 Spring Boot 4 / Java 21 这套组合下维护依赖和模块化演进都比较顺手。

### 前端技术

| 技术          | 版本  | 说明     |
| ------------- | ----- | -------- |
| React         | 18.3  | UI 框架  |
| TypeScript    | 5.6   | 开发语言 |
| Vite          | 5.4   | 构建工具 |
| Tailwind CSS  | 4.1   | 样式框架 |
| React Router  | 7.11  | 路由管理 |
| Framer Motion | 12.23 | 动画库   |
| Recharts      | 3.6   | 图表库   |
| Lucide React  | 0.468 | 图标库   |

## 功能特性

### 简历管理模块

- **多格式解析**：支持 PDF、DOCX、DOC、TXT 等多种简历格式。
- **异步处理流**：基于 Redis Stream 实现异步简历分析，支持实时查看处理进度（待分析/分析中/已完成/失败）。
- **稳定性保障**：内置分析失败自动重试机制（最多 3 次）与基于内容哈希的重复检测。
- **分析报告导出**：支持将 AI 分析结果一键导出为结构化的 PDF 简历分析报告。

### 模拟面试模块

- **个性化出题**：基于简历内容智能生成针对性的面试题目，支持实时问答交互。
- **智能追问流**：支持配置多轮智能追问（默认 1 条），构建模拟真实场景的线性问答流。
- **分批评估机制**：创新性采用分批评估策略，有效规避大模型 Token 溢出风险，确保长文本评估稳定性。
- **智能汇总建议**：对分批评估结果进行二次汇总，提供多维度的改进建议、表现趋势与统计信息。
- **报告一键导出**：支持异步生成并导出详细的 PDF 模拟面试评估报告。

### 知识库管理模块

- **文档智能处理**：支持 PDF、DOCX、Markdown 等多种格式文档的自动上传、分块与异步向量化。
- **RAG 检索增强**：集成向量数据库，通过检索增强生成（RAG）提升 AI 问答的准确性与专业度。
- **流式响应交互**：基于 SSE（Server-Sent Events）技术实现打字机式流式响应。
- **智能问答对话**：支持基于知识库内容的智能问答，并提供直观的知识库统计信息。

### TODO

- [x] 问答助手的 Markdown 展示优化
- [x] 知识库管理页面的知识库下载
- [x] 异步生成模拟面试评估报告
- [x] Docker 快速部署
- [x] 添加 API 限流保护
- [x] 前端性能优化（RAG 聊天 - 虚拟列表）
- [x] 模拟面试增加追问功能
- [ ] 打通模拟面试和知识库
- [ ] 把知识库、简历、面试记录封装成 Agent Tools
- [ ] 引入真正参与推理的 Chat Memory / Session Memory
- [ ] 增加单 Agent 面试教练模式（规划、检索、追问、复盘）
- [ ] 增加 Agent Trace / Eval / Guardrails

## 效果展示

### 简历与面试

简历库：

![](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-history.png)

简历上传分析：

![](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-upload-analysis.png)

简历分析详情：

![](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-analysis-detail.png)

面试记录：

![](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-interview-history.png)

面试详情：

![](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-interview-detail.png)

模拟面试：

![](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-mock-interview.png)

### 知识库

知识库管理：

![](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-knowledge-base-management.png)

问答助手：

![page-qa-assistant](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-qa-assistant.png)

## 项目结构

```
interview-guide/
├── app/                              # 后端应用
│   ├── src/main/java/interview/guide/
│   │   ├── App.java                  # 主启动类
│   │   ├── common/                   # 通用模块
│   │   │   ├── config/               # 配置类
│   │   │   ├── exception/            # 异常处理
│   │   │   └── result/               # 统一响应
│   │   ├── infrastructure/           # 基础设施
│   │   │   ├── export/               # PDF 导出
│   │   │   ├── file/                 # 文件处理
│   │   │   ├── redis/                # Redis 服务
│   │   │   └── storage/              # 对象存储
│   │   └── modules/                  # 业务模块
│   │       ├── interview/            # 面试模块
│   │       ├── knowledgebase/        # 知识库模块
│   │       └── resume/               # 简历模块
│   └── src/main/resources/
│       ├── application.yml           # 应用配置
│       └── prompts/                  # AI 提示词模板
│
├── frontend/                         # 前端应用
│   ├── src/
│   │   ├── api/                      # API 接口
│   │   ├── components/               # 公共组件
│   │   ├── pages/                    # 页面组件
│   │   ├── types/                    # 类型定义
│   │   └── utils/                    # 工具函数
│   ├── package.json
│   └── vite.config.ts
│
└── README.md
```

## 快速开始

环境要求：

| 依赖          | 版本 | 必需 |
| ------------- | ---- | ---- |
| JDK           | 21+  | 是   |
| Node.js       | 18+  | 是   |
| PostgreSQL    | 14+  | 是   |
| pgvector 扩展 | -    | 是   |
| Redis         | 6+   | 是   |
| S3 兼容存储   | -    | 是   |

### 1. 克隆项目

```bash
git clone https://github.com/Kiyra-gjx/interview-guide.git
cd interview-guide
```

如果你希望查看原始基础版本，请参考上游仓库：<https://github.com/Snailclimb/interview-guide>

### 2. 配置数据库

```sql
-- 创建数据库
CREATE DATABASE interview_guide;

-- 连接数据库并启用 pgvector 扩展（可选，启动后端SpringAI框架底层会自动创建）
CREATE EXTENSION vector;
```

### 3. 配置环境变量

```bash
# AI API 密钥（阿里云 DashScope）
export AI_BAILIAN_API_KEY=your_api_key
```

### 4. 修改应用配置

编辑 `app/src/main/resources/application.yml`：

```yaml
spring:
  # PostgreSQL数据库配置
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:interview_guide}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:123456}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: create #首次启动用 create，表创建成功后，改回 update

  # Redisson配置 (使用 spring.redis.redisson，参考官方文档)
  redis:
    redisson:
      config: |
        singleServerConfig:
          address: "redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}"
          database: 0
          connectionMinimumIdleSize: 10
          connectionPoolSize: 64
          subscriptionConnectionMinimumIdleSize: 1
          subscriptionConnectionPoolSize: 50

# RustFS (S3兼容) 存储配置
app:
  # 面试配置
  interview:
    follow-up-count: ${APP_INTERVIEW_FOLLOW_UP_COUNT:1}    # 每个主问题生成追问数量
    evaluation:
      batch-size: ${APP_INTERVIEW_EVALUATION_BATCH_SIZE:8} # 回答评估分批大小
  storage:
    endpoint: ${APP_STORAGE_ENDPOINT:http://localhost:9000}
    access-key: ${APP_STORAGE_ACCESS_KEY:wr45VXJZhCxc6FAWz0YR}
    secret-key: ${APP_STORAGE_SECRET_KEY:GtKxV57WJkpw4CvASPBzTy2DYElLnRqh8dIXQa0m}
    bucket: ${APP_STORAGE_BUCKET:interview-guide}
    region: ${APP_STORAGE_REGION:us-east-1}



```

⚠️**注意**：

1. JPA 的 `ddl-auto` 首次启动用 `create`，表创建成功后，改回 `update`。
2. 如果本地有 Minio 的话，可以用其替换 RusfFS。

### 5. 启动服务

**后端：**

```bash
./gradlew bootRun
```

后端服务启动于 `http://localhost:8080`

**前端：**

```bash
cd frontend
pnpm install
pnpm dev
```

前端服务启动于 `http://localhost:5173`


## Docker 快速部署

本项目提供了完整的 Docker 支持，可以一键启动所有服务（前后端、数据库、中间件）。

### 1. 前置准备
- 安装 [Docker](https://www.docker.com/products/docker-desktop/) 和 Docker Compose
- 申请阿里云百炼 API Key（用于 AI 对话功能）

### 2. 快速启动
在项目根目录下执行：

```bash
# 1. 复制环境变量配置文件
cp .env.example .env

# 2. 编辑 .env 文件，填入 AI 配置
# vim .env
# 必填：AI_BAILIAN_API_KEY=your_key_here
# 可选：AI_MODEL=qwen-plus        # 默认值为 qwen-plus
#        # 也可以改为 qwen-max、qwen-long 等其他可用模型
#
# 面试参数配置（可选）：
# APP_INTERVIEW_FOLLOW_UP_COUNT=1         # 每个主问题生成追问数量（默认 1）
# APP_INTERVIEW_EVALUATION_BATCH_SIZE=8   # 回答评估分批大小（默认 8）

# 3. 构建并启动所有服务
docker-compose up -d --build
```

### 3. 服务访问
启动完成后，您可以通过以下地址访问各个服务：

| 服务             | 地址                                           | 默认账号     | 默认密码     | 说明                   |
| ---------------- | ---------------------------------------------- | ------------ | ------------ | ---------------------- |
| **前端应用**     | [http://localhost](http://localhost)           | -            | -            | 用户访问入口           |
| **后端 API**     | [http://localhost:8080](http://localhost:8080) | -            | -            | Swagger/接口文档       |
| **MinIO 控制台** | [http://localhost:9001](http://localhost:9001) | `minioadmin` | `minioadmin` | 对象存储管理           |
| **MinIO API**    | `localhost:9000`                               | -            | -            | S3 兼容接口            |
| **PostgreSQL**   | `localhost:5432`                               | `postgres`   | `password`   | 数据库 (包含 pgvector) |
| **Redis**        | `localhost:6379`                               | -            | -            | 缓存与消息队列         |

### 4. 常用运维命令

```bash
# 查看服务状态
docker-compose ps

# 查看后端日志
docker-compose logs -f app

# 停止并移除所有服务
docker-compose down

# 清理无用镜像（构建产生的中间层）
docker image prune -f
```

## 使用场景

| 用户角色        | 使用场景                               |
| --------------- | -------------------------------------- |
| **求职者**      | 上传简历获取分析建议，进行模拟面试练习 |
| **HR/招聘人员** | 批量分析简历，评估候选人能力           |
| **培训机构**    | 提供面试培训服务，管理知识库资源       |
| **AI/Agent 学习者** | 作为 Java 后端转向 Agent 应用开发的实践项目 |

## 常见问题

### Q: 数据库表创建失败/数据丢失

这大概率是 JPA 的 `ddl-auto` 配置不对的原因。`ddl-auto` 模式对比：

| 模式     | 行为                            | 适用场景      |
| -------- | ------------------------------- | ------------- |
| create   | 无条件删除并重建所有表          | 开发/测试环境 |
| update   | 对比现有 schema，只执行增量更新 | 开发环境      |
| validate | 只验证，不修改                  | 生产环境      |
| none     | 什么都不做                      | 生产环境      |

对于新数据库，推荐：

```yaml
# 首次启动用 create
jpa:
  hibernate:
    ddl-auto: create

# 表创建成功后，改回 update
jpa:
  hibernate:
    ddl-auto: update
```

记得改回 **update**，否则每次重启都会删除所有数据！

### Q: 知识库向量化失败

当 `initialize-schema: false` 时，Spring AI **不会自动创建** `vector_store` 表。

```java
spring:
  ai:
    vectorstore:
      pgvector:
        initialize-schema: true 

```

建议开发环境设置为 true，方便快速启动。生产环境设置为 false，手动管理数据库 schema，避免意外变更。

### Q: 简历分析失败

检查一下阿里云 DashScope API KEY 是否配置正确（申请地址：<https://bailian.console.aliyun.com/>）。

### Q: 简历分析一直显示"分析中"？

检查 Redis 连接和 Stream Consumer 是否正常运行。查看后端日志确认是否有错误。

### Q: PDF 导出失败或中文显示异常？

项目已内置中文字体（珠圆玉润仿宋），支持跨平台导出。如遇到问题，请检查：
- 字体文件是否存在：`app/src/main/resources/fonts/ZhuqueFangsong-Regular.ttf`
- 检查日志中的字体加载信息
- 确认 iText 依赖是否正确

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

本仓库基于上游 AGPL-3.0 项目 fork 并持续修改，继续遵循 AGPL-3.0 License（只要通过网络提供服务，就必须向用户公开修改后的源码）。
