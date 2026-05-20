# Input Data
请根据以下面试数据评估当前批次。

## 会话 ID
{sessionId}

## 候选人简历摘要
---简历内容开始---
{resumeText}
---简历内容结束---

## 参考基线
---参考基线开始---
{referenceContext}
---参考基线结束---

## 当前批次问答记录
---问答记录开始---
{qaRecords}
---问答记录结束---

## 评估要求
1. 逐题给出分数、反馈、参考答案和核心要点。
2. 批次级 strengths / improvements 各输出 1-5 条，避免重复。
3. 直接输出 JSON 对象，不要输出 Markdown 代码块。
