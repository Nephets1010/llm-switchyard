/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

/**
 * 归一化后的 Token 计量（契约 USG-001）—— 本项目最关键的契约。
 *
 * <p><b>为什么 totalTokens 必须由本仓重算</b>：不同供应商对 {@code total} 的口径并不一致 —— 有的把 reasoning token 算进去，有的只算
 * {@code input + output}，OpenAI 的缓存命中 token 在 {@code prompt_tokens} 里但计费按折扣价。直接拷贝上游的 {@code total}
 * 会让「配额对账」在不同 供应商之间产生系统性偏差。因此本 record 的唯一合法构造路径都会执行 {@code total = input + output + reasoning}。
 *
 * <p><b>为什么 cachedInputTokens 必须是 inputTokens 的子集</b>：缓存命中的那部分 token 本来就包含在 {@code prompt_tokens}
 * 里。若允许 {@code cached > input}，成本公式会把同一段 token 计费两次。 构造器直接 fail-fast 拦住这种错误数据。
 *
 * @param inputTokens 输入 token 数
 * @param outputTokens 输出 token 数
 * @param reasoningTokens 推理/思考 token 数
 * @param cachedInputTokens 输入中命中缓存的部分，{@code <= inputTokens}
 * @param totalTokens <b>由本仓重算</b>：{@code inputTokens + outputTokens + reasoningTokens}
 * @param usageSource 计量来源
 * @param reasoningEstimated reasoning 是否为推算值
 * @param streamUsageReported 流式请求下是否真的收到了 usage 块（PRD 痛点 4 的核心可观测字段）
 */
public record Usage(
        long inputTokens,
        long outputTokens,
        long reasoningTokens,
        long cachedInputTokens,
        long totalTokens,
        UsageSource usageSource,
        boolean reasoningEstimated,
        boolean streamUsageReported) {

    /**
     * 紧凑构造器：校验非负与子集关系，并<b>重算</b> totalTokens。
     *
     * <p>用 {@code ignoreTotal} 技巧让调用方无法传入自定义 total —— record 的规范构造器无法拦截 同名参数，这里通过私有辅助工厂实现「单一合法路径」。
     *
     * @throws IllegalArgumentException 任一字段为负，或 {@code cachedInputTokens > inputTokens}
     */
    public Usage {
        if (inputTokens < 0
                || outputTokens < 0
                || reasoningTokens < 0
                || cachedInputTokens < 0
                || totalTokens < 0) {
            throw new IllegalArgumentException(
                    "Token 计数不允许负值：input="
                            + inputTokens
                            + ", output="
                            + outputTokens
                            + ", reasoning="
                            + reasoningTokens
                            + ", cached="
                            + cachedInputTokens
                            + ", total="
                            + totalTokens);
        }
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException(
                    "cachedInputTokens("
                            + cachedInputTokens
                            + ") 不允许大于 inputTokens("
                            + inputTokens
                            + ")，否则同一段 token 会被重复计费");
        }
        if (usageSource == null) {
            throw new IllegalArgumentException("usageSource 不可为 null");
        }
        // ★ 核心不变量：total 一律重算，忽略调用方传入的值
        totalTokens = inputTokens + outputTokens + reasoningTokens;
    }

    /**
     * 最常用工厂：供应商明确返回了输入/输出 token。
     *
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @return Usage，总数已重算
     */
    public static Usage of(long inputTokens, long outputTokens) {
        return new Usage(
                inputTokens, outputTokens, 0, 0, 0, UsageSource.VENDOR_REPORTED, true, false);
    }

    /**
     * 完整工厂。
     *
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @param reasoningTokens 推理 token
     * @param cachedInputTokens 缓存命中输入 token
     * @param source 计量来源
     * @param reasoningEstimated reasoning 是否推算
     * @param streamUsageReported 流式是否真收到 usage
     * @return Usage，总数已重算
     */
    public static Usage of(
            long inputTokens,
            long outputTokens,
            long reasoningTokens,
            long cachedInputTokens,
            UsageSource source,
            boolean reasoningEstimated,
            boolean streamUsageReported) {
        return new Usage(
                inputTokens,
                outputTokens,
                reasoningTokens,
                cachedInputTokens,
                0,
                source == null ? UsageSource.ESTIMATED : source,
                reasoningEstimated,
                streamUsageReported);
    }

    /**
     * 全 0 的估算用量，用于「没有 token 的事件」占位（如审计一条纯工具调用事件）。
     *
     * <p>刻意<b>不</b>用 {@code null}：契约 EVT-001 要求 {@code usage} 字段不可为 null， 用一个显式的零值对象比到处判空更安全。
     *
     * @param source 计量来源
     * @param streamReported 流式是否真收到 usage
     * @return 全 0 的 Usage
     */
    public static Usage estimated(UsageSource source, boolean streamReported) {
        return new Usage(
                0,
                0,
                0,
                0,
                0,
                source == null ? UsageSource.ESTIMATED : source,
                true,
                streamReported);
    }

    /** 供应商明确返回 usage 的非流式用量。 */
    public static Usage vendorReported(long inputTokens, long outputTokens) {
        return new Usage(
                inputTokens, outputTokens, 0, 0, 0, UsageSource.VENDOR_REPORTED, false, false);
    }

    /**
     * 按相同用量派生一个新 Usage，只改来源与流式标记（重试、降级后要重新归一化时用）。
     *
     * @param source 新来源
     * @param streamUsageReported 流式是否真收到 usage
     * @return 新的 Usage
     */
    public Usage withSource(UsageSource source, boolean streamUsageReported) {
        return new Usage(
                inputTokens,
                outputTokens,
                reasoningTokens,
                cachedInputTokens,
                0,
                source,
                reasoningEstimated,
                streamUsageReported);
    }

    /**
     * 判断计量是否「不可信」——即依赖估算而非供应商真值。
     *
     * <p>配额与成本报表据此决定是否给结果打上「估算」标记。
     *
     * @return 来源为 ESTIMATED 或 FALLBACK 时返回 true
     */
    public boolean estimatedOnly() {
        return usageSource != UsageSource.VENDOR_REPORTED;
    }

    /** 非缓存计费的输入 token 数（{@code inputTokens - cachedInputTokens}）。 */
    public long billableInputTokens() {
        return inputTokens - cachedInputTokens;
    }

    /** 是否为全零用量。 */
    public boolean isZero() {
        return totalTokens == 0;
    }
}
