# Claude Code 不只是终端里的 ChatGPT：一个真正能干活的 AI 编程 Agent

> 工具地址：[Claude Code](https://docs.anthropic.com/en/docs/claude-code)
> 当前版本：2.1.143
> 安装方式：`npm install -g @anthropic-ai/claude-code`

## 这东西到底是什么

市面上"AI 编程助手"很多。GitHub Copilot 补全代码，Cursor 在 IDE 里对话，ChatGPT 你贴代码它回答问题。它们的共同特点是：**你是主体，AI 是辅助**。你得告诉它上下文，你得把代码贴给它，你得自己执行它建议的命令。

Claude Code 是另一种东西。它是一个运行在终端里的 **Agent**——给它一个任务描述，它自己去读代码、改文件、跑测试、提交 commit、创建 PR。你不需要把代码贴给它，它自己会找。你不需要跑命令，它自己会跑。

简单说：**它是来干活的，不是来聊天的。**

## 安装

```bash
npm install -g @anthropic-ai/claude-code
```

装完直接敲 `claude` 就进入交互模式。需要 Node.js 18+。

除了终端 CLI，还有五种接入方式：

- **VS Code 扩展**：在扩展市场搜 "Claude Code"，装完在侧边栏直接用
- **JetBrains 插件**：IntelliJ、PyCharm、WebStorm 都支持
- **桌面应用**：macOS / Windows / Linux 独立应用
- **Web**：通过 claude.ai 访问
- **原生安装**：`claude install` 安装独立二进制，不依赖 Node.js

认证方式支持 Anthropic API Key、OAuth、Claude Max 订阅，也支持 Amazon Bedrock 和 Google Vertex AI。

## 它能做什么

### 自主编码

给它一个任务，比如"给这个 API 加一个分页功能"，它会：

1. 读相关文件，理解现有代码结构
2. 找到需要改的地方
3. 做多文件协同修改
4. 跑测试验证
5. 自动提交 commit

如果测试挂了，它会自己看错误信息，修了再跑。不是"建议你改这里"，是直接改了给你看。

### 内置工具集

Claude Code 有一套内置工具，这是它区别于普通聊天 AI 的核心：

| 工具 | 能力 |
|------|------|
| Bash | 执行 shell 命令（有权限控制） |
| Read | 读文件，支持图片、PDF、Jupyter Notebook |
| Edit | 精确字符串替换编辑 |
| Write | 创建新文件或完整重写 |
| Glob | 文件名模式匹配 |
| Grep | 基于 ripgrep 的内容搜索，支持正则 |
| WebFetch | 抓取网页内容 |
| WebSearch | 搜索互联网 |
| TaskCreate 系列 | 后台任务管理 |

这些工具不是手动调用的——Claude 自己决定用哪个、什么时候用。你只管提需求。

### MCP 扩展

MCP（Model Context Protocol）是 Anthropic 的开放协议，让 Claude Code 能接入外部工具服务器。比如接一个数据库 MCP Server，Claude 就能直接查数据库；接一个 Slack MCP，就能发消息。

配置方式：项目根目录放一个 `.mcp.json`，或者用 `--mcp-config` 指定。

### Skills 系统

Skills 是可复用的指令集，通过斜杠命令调用。比如：

- `/blog-post`：按照标准结构写博客
- `/git-conventions`：按 Conventional Commits 规范写 commit message
- `/writing-plans`：在动手写代码前先做实现计划
- `/review`：代码审查
- `/security-review`：安全审查

Skills 存放在 `~/.claude/skills/` 目录下，可以自己写，也可以从插件市场安装。

### Git Worktree 隔离开发

需要同时做多个功能分支？用 `--worktree` 启动一个隔离的工作树：

```bash
claude --worktree feature-x
```

每个 worktree 有自己的工作目录和分支，互不干扰。改完了合回主分支就行。

## 配置体系：CLAUDE.md 是灵魂

### CLAUDE.md

项目根目录放一个 `CLAUDE.md` 文件，Claude 每次启动都会读。里面写：

- 构建和测试命令
- 项目架构概述
- 编码规范
- 常见问题和注意事项

用 `/init` 命令可以自动生成一个模板。支持层级加载：根目录的 CLAUDE.md + 子目录的 CLAUDE.md 都会被读取。

`~/.claude/CLAUDE.md` 是全局配置，写你个人的偏好（比如"用中文回复"、"commit message 用中文"）。

**重要**：CLAUDE.md 会被提交到 git，不要在里面放密钥。

### Settings 层级

权限和配置分三层，优先级从高到低：

```
~/.claude/settings.json           # 全局，所有项目生效
<project>/.claude/settings.json   # 项目级，提交到 git，团队共享
<project>/.claude/settings.local.json  # 本地级，不提交，个人覆盖
```

Settings 的核心是权限控制：

```json
{
  "permissions": {
    "allow": ["Bash(git *)", "Edit", "Read"],
    "deny": ["Bash(rm -rf *)"]
  }
}
```

### 权限模式

从松到严有五种模式：

| 模式 | 行为 |
|------|------|
| `default` | 危险操作要确认 |
| `acceptEdits` | 文件编辑自动通过，命令要确认 |
| `auto` | 大部分操作自动通过 |
| `plan` | 只做规划，不执行 |
| `bypassPermissions` | 跳过所有检查（仅沙箱环境） |

日常开发建议用 `default` 或 `acceptEdits`。CI/CD 场景用 `auto`。想先看 Claude 的计划再决定是否执行，用 `plan`。

## 常用斜杠命令

```
/help            查看帮助
/clear           清空对话历史
/compact         压缩上下文，释放 token
/cost            查看当前 token 用量和费用
/init            初始化 CLAUDE.md
/model           切换模型
/review          代码审查
/security-review 安全审查
/diff            查看当前 diff
/pr-comments     查看 PR 评论
/mcp             管理 MCP 服务器
/memory          编辑记忆文件
```

## 高级用法

### 非交互模式（CI/CD 集成）

```bash
claude -p "跑一遍测试，告诉我结果" --output-format json
```

`-p` 是 print 模式，不进入交互，直接输出结果。`--output-format stream-json` 可以做实时流式输出。`--json-schema` 可以指定输出的 JSON 结构。

### 费用控制

```bash
claude --max-budget-usd 5.0
```

设置本次会话的费用上限。`/cost` 命令随时查看消耗。

### 自定义 Agent

```bash
claude --agents '{"reviewer": {"description": "代码审查员", "prompt": "你是一个严格的代码审查员"}}'
```

定义自定义 Agent 角色，在会话中切换使用。

### 会话管理

```bash
claude --continue          # 继续上次的对话
claude --resume <id>       # 恢复指定会话
claude --fork-session      # 从现有会话分叉
claude --from-pr 42        # 恢复关联到 PR 的会话
```

### Effort 级别

```bash
claude --effort max
```

控制推理深度。`low` 快速响应，`max` 深度思考。复杂任务用 `high` 或 `max`，简单任务用 `low` 省钱。

## 三个实际使用场景

### 场景一：修 Bug

```
> 登录接口返回 500，帮我查一下原因
```

Claude 会：
1. 找到登录相关的代码
2. 读 controller、service、repository 层
3. 分析可能的问题点
4. 如果需要，跑一下本地服务看日志
5. 给出修复方案并直接改代码
6. 跑测试验证

### 场景二：新功能开发

```
> 给用户模块加一个密码重置功能，要求邮件验证
```

Claude 会：
1. 先看现有的用户模块结构
2. 进入 Plan 模式，列出实现方案
3. 你确认后开始写代码
4. 创建 migration、controller、service、前端页面
5. 跑测试，确保不破坏已有功能
6. 提交 commit

### 场景三：代码审查

```
> /review
```

Claude 会审查当前分支的改动，指出潜在问题、安全漏洞、风格不一致等。

## 模型选择

| 模型 | 特点 | 适用场景 |
|------|------|---------|
| Opus | 最强能力，最高成本 | 复杂架构设计、深度调试 |
| Sonnet | 平衡性能和成本 | 日常开发、大部分任务 |
| Haiku | 最快，最便宜 | 简单查询、快速响应 |

用 `/model` 命令随时切换。`--fallback-model` 可以设置备用模型，主模型过载时自动切换。

## 为什么说它是 Agent 而不只是助手

传统 AI 编程助手的工作流是：

```
你：贴代码 → AI：给建议 → 你：自己改 → 你：自己跑测试
```

Claude Code 的工作流是：

```
你：描述任务 → Claude：读代码 → 改代码 → 跑测试 → 提交 → 告诉你结果
```

中间的循环是 Claude 自己在跑。它不是给你建议，是替你干活。你从"操作者"变成了"监督者"。

这不意味着你可以完全不管。复杂的架构决策、业务逻辑的微妙之处、安全敏感的操作——这些仍然需要人来判断。Claude Code 的价值在于把重复性的、模式化的编码工作自动化，让你专注于真正需要人脑的部分。

## 写在最后

Claude Code 是目前我用过的最接近"AI 程序员"的工具。它不是完美的——有时候会犯蠢，有时候会过度设计，有时候需要你纠正方向。但它确实在干活，而且干得比大多数人预期的要好。

如果你还没试过，花 10 分钟装上跑一个真实任务。比看 10 篇评测文章更有说服力。
