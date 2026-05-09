# stage-8-rag-e2e Summary

## Core Metrics

- totalQueries: `30`
- passedQueries: `29`
- answerableQueries: `22`
- noAnswerQueries: `8`
- recallAt3: `0.9242`
- recallAt5: `0.9697`
- recallAt10: `0.9697`
- hitRateAt3: `1.0`
- mrr: `1.0`
- ndcgAt3: `0.9447`
- correctness: `5.0`
- attribution: `4.7727`
- completeness: `4.7273`
- faithfulness: `4.8636`
- readability: `5.0`
- latencyP50Ms: `7486`
- latencyP95Ms: `15043`
- latencyP99Ms: `15625`

## Resume Gate

- realPgvectorPath: `yes`
- realEmbeddingPath: `yes`
- realLlmGenerationPath: `yes`
- gradedRelevance: `yes`
- baselineComparable: `yes`
- resumeSafe: `partial`

## Do Not Claim

- Do not claim production RAG accuracy from this suite.
- Do not claim public benchmark results.
- Do not compare local single-machine latency to online performance.