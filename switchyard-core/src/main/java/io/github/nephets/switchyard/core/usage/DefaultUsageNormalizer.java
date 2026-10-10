/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

/**
 * 通用归一化器：把 {@link RawUsage} 按 USG-001 的 7 步算法收敛成 {@link Usage}。
 *
 * <p><b>7 步算法（三仓逐字一致，改一处要三仓同改）</b>：
 *
 * <pre>
 *   1. inputTokens       = raw.promptTokens ?: estimator.estimate(ctx.prompt) ?: ctx.maxTokens
 *   2. cachedInputTokens = raw.promptTokensDetails.cachedTokens ?: 0
 *   3. outputTokens      = raw.completionTokens ?: estimator.estimate(ctx.completion) ?: 0
 *   4. reasoningTokens   = raw.reasoningContent ?: 0;  reasoningEstimated = (raw.reasoningContent == null)
 *   5. totalTokens       = 1 + 3 + 4          // ← 本仓重算，不信任上游
 *   6. usageSource       = raw 全部缺失 ? ESTIMATED : VENDOR_REPORTED
 *   7. streamUsageReported = ctx.streaming ? ctx.usageChunkReceived : false
 * </pre>
 *
 * <p><b>为什么第 5 步要重算</b>：见 {@link Usage} 的类注释。一句话：上游的 total 口径各家不同， 拷贝它等于把供应商的算法变更风险转嫁给我们的配额与对账。
 *
 * <p><b>为什么第 7 步非流式恒为 false</b>：字段名是「流式请求下是否真收到 usage 块」。 非流式响应体里本来就带 usage，写成 true
 * 会让「流式计量丢失」这个告警彻底失效 —— 那正是 PRD 痛点 4 要监控的东西。
 */
public class DefaultUsageNormalizer implements UsageNormalizer {

    @Override
    public Usage normalize(RawUsage raw, NormalizeContext ctx, TokenEstimator estimator) {
        RawUsage source = raw == null ? RawUsage.empty() : raw;
        NormalizeContext context = ctx == null ? NormalizeContext.nonStreaming(null) : ctx;

        // 第 1 步：输入 token。用 Long 承载以便区分「上游没给」与「上游给了 0」
        Long inputTokens = firstNonNull(source.promptTokens(), source.promptEvalCount());
        if (inputTokens == null) {
            inputTokens = estimate(estimator, context.promptText());
        }
        if (inputTokens == null) {
            // 估算器也没有 → 按 maxTokens 上界兜底，来源标 FALLBACK（契约要求的第三档）
            inputTokens =
                    context.maxTokens() == null
                            ? Long.valueOf(0L)
                            : context.maxTokens().longValue();
        }
        final long resolvedInputTokens = inputTokens;

        // 第 2 步：缓存命中的输入 token。必须夹紧到 <= inputTokens，否则 Usage 构造器会 fail-fast
        long cachedInputTokens =
                clamp(
                        firstNonNull(source.cachedPromptTokens(), source.cacheReadInputTokens()),
                        0L,
                        Math.max(0L, resolvedInputTokens));

        // 第 3 步：输出 token
        Long outputTokens = firstNonNull(source.completionTokens(), source.evalCount());
        if (outputTokens == null) {
            outputTokens = estimate(estimator, context.completionText());
        }
        final long resolvedOutputTokens = outputTokens == null ? 0L : outputTokens;

        // 第 4 步：推理 token。上游没给就是 0，并标记为估算（不可信）
        Long rawReasoning = source.reasoningTokens();
        long reasoningTokens = rawReasoning == null ? 0L : Math.max(0L, rawReasoning);
        boolean reasoningEstimated = rawReasoning == null;

        // 第 6 步：来源判定。注意「全缺失」而不是「某个字段缺失」——
        // 只要拿到了任何一个真实 token 数，就认为供应商确实报了数
        boolean vendorReported = !source.isCompletelyMissing();
        UsageSource usageSource;
        if (vendorReported) {
            usageSource = UsageSource.VENDOR_REPORTED;
        } else if (estimator == null && context.maxTokens() != null && context.maxTokens() > 0) {
            usageSource = UsageSource.FALLBACK;
        } else {
            usageSource = UsageSource.ESTIMATED;
        }

        // 第 7 步：流式 usage 是否真收到
        boolean streamUsageReported = context.streaming() && context.usageChunkReceived();

        // 第 5 步在 Usage 构造器内部完成（total 一律重算）
        return new Usage(
                resolvedInputTokens,
                resolvedOutputTokens,
                reasoningTokens,
                cachedInputTokens,
                0,
                usageSource,
                reasoningEstimated,
                streamUsageReported);
    }

    @Override
    public String name() {
        return "default";
    }

    /** 估算文本；估算器缺失或文本为空时返回 null，交由调用方决定兜底策略。 */
    private static Long estimate(TokenEstimator estimator, String text) {
        if (estimator == null || text == null || text.isEmpty()) {
            return null;
        }
        long estimated = estimator.estimate(text);
        return estimated <= 0 ? null : estimated;
    }

    private static Long firstNonNull(Long first, Long second) {
        if (first != null) {
            return first;
        }
        return second;
    }

    /** 把值夹到 [min, max] 区间内；null 视为 min。 */
    private static long clamp(Long value, long min, long max) {
        if (value == null) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
