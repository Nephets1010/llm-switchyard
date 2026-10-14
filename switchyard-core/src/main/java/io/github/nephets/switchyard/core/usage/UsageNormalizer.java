/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

/**
 * Token 计量归一化器（SPI，契约 USG-001）。
 *
 * <p>存在的意义是把「六家供应商六种 usage 结构」收敛成「一套归一化算法」。 抽象成 SPI 而不是写死在一个类里，是为了允许按供应商定制（比如某家把 reasoning 折进
 * output， 需要在归一化时拆出来），但<b>默认实现必须对所有供应商产出同一口径</b>， 这是契约测试 {@code UsageNormalizerContractTest}
 * 逐个供应商锁死的行为。
 */
public interface UsageNormalizer {

    /**
     * 执行归一化。
     *
     * @param raw 上游 usage（宽松解析后的并集结构），可为 null（等价于 {@link RawUsage#empty()}）
     * @param ctx 归一化上下文，提供 streaming / 文本 / maxTokens 等兜底依据
     * @param estimator 估算器，上游缺字段时兜底；可为 null（则直接退到 maxTokens 上界）
     * @return 归一化后的 {@link Usage}，{@code totalTokens} 由本仓重算
     */
    Usage normalize(RawUsage raw, NormalizeContext ctx, TokenEstimator estimator);

    /**
     * 归一化器标识，写入审计与 span（{@code swy.usage.normalizer}）。
     *
     * @return 归一化器名
     */
    String name();
}
