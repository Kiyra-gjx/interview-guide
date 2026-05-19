# MCP 和 Skills：Claude Code 两种扩展机制的本质区别

> 工具：Claude Code 2.1.x
> 协议：MCP (Model Context Protocol) / Skills

## 先说结论

MCP 和 Skills 都是 Claude Code 的扩展机制，但它们解决的是完全不同的问题：

- **MCP 是给 Claude 加工具**——让它能操作外部系统（数据库、API、文件系统）
- **Skills 是给 Claude 加知识**——教它怎么做某类事情（写博客、做代码审查、写 commit message）

一个扩展"能做什么"，一个扩展"怎么做好"。这个区别决定了你什么时候该用哪个。

## MCP：给 Agent 装上手和脚

### 它是什么

MCP（Model Context Protocol）是 Anthropic 定义的一个开放协议。它让 Claude Code 能连接到外部的"工具服务器"，获得新的能力。

没有 MCP 的时候，Claude Code 只能用内置的那些工具：读写文件、跑命令、搜索代码。有了 MCP，它可以查数据库、调 API、操作云服务、发 Slack 消息——任何你能在 MCP Server 里实现的能力。

### 工作原理

```
Claude Code
    │
    │ MCP 协议（JSON-RPC over stdio/SSE）
    │
    ▼
┌──────────────┐
│  MCP Server  │  ← 一个独立的进程或服务
│              │
│  工具列表:    │
│  - query_db  │
│  - send_msg  │
│  - deploy    │
└──────────────┘
```

MCP Server 启动时告诉 Claude Code："我有这些工具"。Claude Code 在需要的时候调用它们，就像调用内置工具一样。

### 配置方式

项目根目录放 `.mcp.json`：

```json
{
  "mcpServers": {
    "postgres": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-postgres", "postgresql://localhost/mydb"]
    },
    "slack": {
      "command": "npx",
      "args": ["-y", "@anthropic-ai/mcp-server-slack"],
      "env": {
        "SLACK_TOKEN": "xoxb-..."
      }
    }
  }
}
```

或者用 CLI 命令管理：

```bash
claude mcp add postgres -- npx -y @modelcontextprotocol/server-postgres ...
claude mcp list
claude mcp remove postgres
```

### 本质

MCP 是**能力扩展**。它给 Claude 加了之前没有的工具。没有数据库 MCP Server，Claude 查不了数据库；有了之后，它就能查了。这是从"不能"到"能"的变化。

## Skills：给 Agent 写操作手册

### 它是什么

Skills 是一套可复用的指令集。它不给 Claude 加新工具，而是教它**怎么用已有的工具做好某类事情**。

你有没有过这种经历：每次让 Claude 写 commit message，它写得都不错但风格每次都不一样？或者让它写博客，结构总是要你重新调整？Skills 就是解决这个的——你把最佳实践写成一份指令，Claude 每次都按这个来。

### 工作原理

```
用户输入: "/git-conventions"
          │
          ▼
┌─────────────────────────────┐
│  Skill 文件被加载到上下文     │
│                             │
│  "Commit Title Format:      │
│   <type>: <中文概述>         │
│   type 用英文，概述用中文"   │
│                             │
│  "Rules:                    │
│   - 不超过 50 字符           │
│   - 不加 scope              │
│   - 结果导向，非实现细节"    │
└─────────────────────────────┘
          │
          ▼
  Claude 按指令生成 commit message
```

Skills 不改变 Claude 的能力边界，改变的是它做事的方式。

### 配置方式

Skill 文件放在 `~/.claude/skills/` 目录下，用 Markdown 写：

```markdown
---
name: git-conventions
description: 规范 Git commit message 和 PR 标题
---

## Commit 标题格式

<type>: <中文概述>

## 类型

- feat: 新功能
- fix: 修 bug
- docs: 文档
- refactor: 重构

## 规则

- type 用英文，概述用中文
- 不超过 50 字符
- 不加 scope
```

通过斜杠命令调用：`/git-conventions`

### 本质

Skills 是**行为引导**。Claude 本来就会写代码、会做审查、会写文档。Skills 告诉它"按我的标准来"。这是从"随便做"到"做好"的变化。

## 核心区别对比

| 维度 | MCP | Skills |
|------|-----|--------|
| 解决什么问题 | 能力不足 | 行为不一致 |
| 改变了什么 | 加了新工具 | 改变了做事方式 |
| 有没有代码执行 | 有，MCP Server 是运行的进程 | 没有，只是指令文本 |
| 谁在干活 | MCP Server 的代码 | Claude 自己 |
| 适用场景 | 需要连接外部系统 | 需要标准化流程 |
| 配置方式 | `.mcp.json` 或 CLI | Skill 文件 + 斜杠命令 |
| 典型例子 | 数据库查询、API 调用、云操作 | 写博客、代码审查、commit 规范 |

## 什么时候用哪个

### 用 MCP 的场景

- 要查数据库 → MCP Server 接 PostgreSQL
- 要调外部 API → MCP Server 接 HTTP 工具
- 要操作云服务 → MCP Server 接 AWS/GCP
- 要发消息通知 → MCP Server 接 Slack/邮件
- 要操作特定工具 → MCP Server 接 Jira/Linear/GitHub

判断标准：**Claude 的内置工具能不能做到？** 做不到，用 MCP。

### 用 Skills 的场景

- 团队有统一的 commit message 规范
- 博客有固定的结构和风格
- 代码审查有特定的检查清单
- 实现有固定的流程（先计划再编码）
- PR 描述有统一的模板

判断标准：**Claude 能做到但做得不够好/不一致？** 用 Skills。

### 两者可以叠加

一个真实场景：用 MCP 连接 Linear（项目管理工具），用 Skills 定义"怎么写 Linear ticket 的描述"。MCP 提供操作 Linear 的能力，Skills 提供写描述的标准。

```
用户：创建一个 ticket

Claude：
1. 加载 /linear-ticket skill → 知道描述格式要包含 Problem / Solution / Acceptance Criteria
2. 调用 MCP 的 create_issue 工具 → 实际在 Linear 里创建
```

## 一个容易混淆的点

有人会问：Skills 里也能写"用 Bash 跑这个命令"，这和 MCP 有什么区别？

区别在于**谁提供能力**。

- Skill 里写"跑 `psql -c 'SELECT ...'`"——Claude 用内置的 Bash 工具执行，前提是本机装了 psql
- MCP 里配 PostgreSQL Server——Claude 通过 MCP 协议调用，不要求本机装 psql，MCP Server 可以在远程

Skill 是"教 Claude 用已有工具"，MCP 是"给 Claude 新工具"。

## 写在最后

把 MCP 想象成给机器人装新的机械臂，把 Skills 想象成给机器人写操作手册。机械臂让它能做新事情，操作手册让它把已有事情做好。

两者不冲突，也不互相替代。大部分项目两者都会用到：MCP 连接你需要的外部系统，Skills 规范你需要的流程标准。
