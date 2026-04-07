# 提交流程参考

## 推荐顺序

1. 看 `git status --short`
2. 看 `git diff --stat`
3. 必要时查看关键文件 diff
4. 判断这次提交是否边界清晰
5. 生成 1 到 3 个 commit message 候选
6. 给出最小验证建议
7. 用户确认后再执行 `git add` / `git commit`
8. 只有用户明确要求时才 `git push`

## commit message 风格

- 优先遵循仓库已有的 Conventional Commits 风格。
- 常用类型包括：`fix`、`feat`、`docs`、`refactor`、`test`、`chore`。
- 默认格式：`type: 简洁中文描述`。
- 结合仓库历史，修复类提交优先使用 `fix:`。

## 什么时候要提醒用户拆分提交

- 功能改动和纯格式化改动混在一起
- 后端、前端、配置、文档完全无关却放在一次提交里
- 自动生成文件和手工业务改动混在一起
- 有明显不是当前任务范围的工作区改动

## 什么时候要提醒做验证

- 改后端 Java 代码：至少考虑 `.\gradlew.bat :app:compileJava`
- 改后端业务逻辑或测试：考虑 `.\gradlew.bat :app:test` 或定向测试
- 改前端页面、类型或 API：考虑在 `frontend/` 下执行 `pnpm build`
- 改接口契约、统一返回结构、异步状态、prompt 或结构化输出：优先提醒跨层检查

## 输出建议

- 先说明这次提交包含什么
- 再给 commit message
- 再说是否建议立刻提交 / 推送
- 如果缺验证，明确写出未验证项

