# Chunk Policy

当前采用的最小策略：

1. 语料优先使用 Markdown。
2. 先按标题切章节，再对章节内容做 token chunk。
3. 每个 chunk 保留以下元数据：
   - `sourceTitle`
   - `sectionTitle`
   - `chunkIndex`
   - `preview`

说明：

- `sourceTitle` 用于标识语料主题或文档名。
- `sectionTitle` 用于标识当前 chunk 所属章节。
- `chunkIndex` 用于标识文档内顺序。
- `preview` 用于调试和证据展示，避免暴露全文。

