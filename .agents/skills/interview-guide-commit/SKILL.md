---
name: interview-guide-commit
description: 在 Interview Guide 仓库中处理代码提交相关工作流。适用于整理当前改动、查看 diff、生成或优化 commit message、执行 git add/git commit、准备 push 前检查，以及提醒最小验证范围。用户说“帮我提交这次改动”“帮我生成 commit message”“帮我整理改动并提交”“帮我推送代码前检查一下”这类话时应触发。
---

# Interview Guide 提交工作流

先读 `references/commit-workflow.md`。

## 工作方式

- 先看当前工作区改动，再决定是否适合提交。
- 先总结改动内容，再生成 commit message 候选。
- 默认不直接 `push`，除非用户明确要求。
- 默认不假设可以跳过验证；至少给出与改动范围匹配的最小验证建议。
- 如果发现工作区里有明显无关改动，提醒用户注意提交边界。

## 提交前检查

- 用 `git status --short` 看工作区范围。
- 用 `git diff --stat` 和必要的定向 diff 看改动是否成组。
- 如果改动涉及后端接口、DTO、enum 或统一返回结构，提醒检查前端联动。
- 如果改动涉及 prompt、结构化输出、异步任务或导出链路，提醒这些属于高风险提交。

## commit message 规则

- 优先遵循仓库现有的 Conventional Commits 风格，例如 `fix:`、`feat:`、`docs:`、`refactor:`、`test:`、`chore:`。
- message 统一使用 `type: 描述` 形式，冒号后用简洁中文说明主要改动。
- 描述要体现“改了什么”，不要只写 `update`、`fix bug` 这种低信息量内容。
- 如果改动跨多个模块，优先概括主改动和主要目的，不要把所有细节塞进一条提交信息。
- 默认优先匹配仓库历史最常见风格；修 bug 优先考虑 `fix:`。

可参考的表达：

- `fix: 修复简历分析结果重复触发的问题`
- `fix: 收敛 AI 结构化输出与异步重试策略`
- `feat: 新增知识库问答重试提示`
- `docs: 补充本地开发与联调说明`
- `refactor: 拆分面试评估服务并补充结构化输出兜底`
- `test: 补充简历领域识别服务单元测试`
- `chore: 调整本地开发脚本与构建配置`

## 执行边界

- 只有在用户明确要求提交时，才执行 `git add` / `git commit`。
- 只有在用户明确要求推送时，才执行 `git push`。
- 如果验证没跑，或存在明显未确认风险，先告诉用户再继续。
