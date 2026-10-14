# ADR-0002：USG-001 归一化算法与「重算 total」的理由

- **状态**：已接受
- **日期**：2026-10-09
- **相关**：[架构文档 §2.2](../../../../deliverables/software-company/llm-infra-architecture.md)、`core/usage/`

## 背景

七家供应商的 usage 结构各不相同（见 [providers.md](../providers.md) 的协议差异表）。
如果每家各写一套解析，「同一段对话在不同供应商上的成本口径不一致」就会成为常态。

而成本口径不一致的后果不是「差一点」，而是**完全不可对账** —— 财务无法回答「这个月为什么涨了」。

## 决策

**定义一套 7 步归一化算法（三仓逐字一致），其中 `totalTokens` 一律由本仓重算。**

```
1. inputTokens       = raw.promptTokens ?: estimator.estimate(prompt) ?: ctx.maxTokens
2. cachedInputTokens = raw.promptTokensDetails.cachedTokens ?: 0
3. outputTokens      = raw.completionTokens ?: estimator.estimate(completion) ?: 0
4. reasoningTokens   = raw.reasoningContent ?: 0
                      reasoningEstimated = (raw.reasoningContent == null)
5. totalTokens       = 1 + 3 + 4          ← 本仓重算，不信任上游
6. usageSource       = raw 全部缺失 ? ESTIMATED : VENDOR_REPORTED
7. streamUsageReported = ctx.streaming ? ctx.usageChunkReceived : false
```

## 为什么第 5 步必须重算

**上游的 `total` 口径各家不同：**

| 供应商 | `total` 的口径 |
|---|---|
| OpenAI | `prompt + completion`（**不含** reasoning，且含缓存命中的全量 input） |
| DeepSeek | `prompt + completion + reasoning`（reasoning 单独计） |
| Anthropic | **根本没有 `total` 字段** |
| Ollama | 没有 `total`，要自己拼 |

直接拷贝上游的 `total`，会导致：
- 同一个模型在不同区域返回不同的 total；
- 供应商哪天改了算法，你的对账就崩了，而且**你无感知**。

**重算的代价**是：如果我们的口径和供应商的口径不同，成本会有偏差。
但这个偏差是**固定的、可预测的**，而拷贝上游的偏差是**不可预测的**。企业财务要的是前者。

## 为什么 `cachedInputTokens` 必须从 `inputTokens` 里扣出来

缓存命中的那部分 token **已经包含在 `prompt_tokens` 里**。若不扣，成本公式会把同一段 token
按「全价 input」+「缓存折扣价」计两次 —— 这是接入 prompt caching 后最常见的对账事故。

因此 `Usage` 的紧凑构造器里有两条硬约束：

```java
if (cachedInputTokens > inputTokens) {
    throw new IllegalArgumentException("cachedInputTokens 不允许大于 inputTokens，否则同一段 token 会被重复计费");
}
```

**fail-fast 在开发期**，而不是让错误的成本进到财务报表。

## 第 6 步的判定：全缺失 vs 部分缺失

`usageSource` 的判定依据是「上游**一个** token 数都没给」，而不是「某个字段缺失」。

理由：DeepSeek 不返回 `reasoning_tokens` 是常态，但它的 `prompt_tokens` / `completion_tokens`
是真实值 —— 这种情况必须标 `VENDOR_REPORTED`（可用于预算决策）。
若按「部分缺失」判定，整个 DeepSeek 的计量都会被标成估算，用户就不敢拿它做预算了。

## 第 7 步为什么非流式恒为 false

字段名是「**流式请求下**是否真的收到了 usage 块」。

非流式响应体里本来就带 usage，写成 true 会让「流式计量丢失」这个告警彻底失效 ——
而那正是 PRD 痛点 4 要监控的东西。**一个永远为 true 的告警等于没有告警。**

## 验收

| 指标 | 门槛 | 实测 |
|---|---|---|
| 10 万条构造流量的 `sum(input+output)` 与账单误差 | < 0.5% | **0.363337%** |
| 流式计量为 0 的条数 | 0 | **0** |

由 `MeteringAccuracyTest` 在每次 `mvn verify` 时实测，随机种子固定（`20261009`），
任何人 clone 后跑出的数字完全一致 —— 所以这个数字可以写进 README 而不用担心造假。

## 诚实声明

内置价格表中多数条目为 `source: ESTIMATED`（社区估算），控制台会显式标注。
**请以你自己的供应商账单为准。** 我们拒绝在代码里编造价格（见 [ADR-0007](0007-no-fabricated-prices.md)）。
