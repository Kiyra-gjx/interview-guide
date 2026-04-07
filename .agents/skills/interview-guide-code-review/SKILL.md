---
name: interview-guide-code-review
description: 在 Interview Guide 仓库中做项目级代码审查，重点检查后端或前端回归、API 契约漂移、Redis Stream 状态处理、Spring AI 结构化输出稳定性、文件解析与 PDF 导出链路，以及验证覆盖是否足够。适用于审查 diff、PR、重构、bug 修复和自动生成补丁。用户说“帮我 review 一下代码”“帮我 review 我的改动”“帮我 code review 一下”“审查这次提交”“看下这次 diff 有没有风险”“帮我做代码审查”这类话时应触发。
---

# Interview Guide 代码审查

先读 `references/review-checklist.md`。如果 diff 涉及共享基础设施、prompt、异步处理或前后端契约边界，再读 `references/review-hotspots.md`。

## 审查方式

- 先给结论性问题，再给总结。
- 优先关注 bug、回归、契约漂移、缺失验证和运行风险。
- 尽量给出具体文件和行号。
- 如果没发现问题，要明确说没有问题，并补充剩余测试缺口。

## 仓库特有重点

- 检查后端 JSON 接口是否仍然满足 `frontend/src/api/request.ts` 期待的 `Result<T>` 契约。
- 检查 DTO 或 enum 的变更是否仍与 `frontend/src/types` 和消费它们的页面/组件一致。
- 检查 Redis Stream consumer 的状态流转、重试和 ACK 行为是否仍然自洽。
- 检查 prompt 变更是否仍然适配 `common/ai/StructuredOutputInvoker.java` 的解析与兜底路径。
- 检查文件解析、存储、PDF 导出改动是否保留失败处理和用户可见错误语义。

## 审查时必须问的问题

- 这个改动会不会在没有编译报错的情况下直接搞坏前端调用？
- 这个改动会不会让异步任务卡在 `PENDING` / `PROCESSING`，或者过早标记完成？
- 这个改动会不会让 AI 输出更难解析、线上更脆弱？
- 这个改动有没有保持现有异常处理和用户可见错误语义？
- 当前给出的验证证据，是否足够覆盖这次改动的风险面？

## 验证要求

- 纯后端审查时，要看编译/测试证据；没有的话要明确指出。
- 影响前端时，要看 `pnpm build` 或同等级的类型/构建验证。
- 涉及环境依赖的链路，要区分“逻辑已验证”和“集成仍未验证”。

