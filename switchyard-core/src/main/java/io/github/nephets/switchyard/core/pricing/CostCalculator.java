/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.pricing;

import io.github.nephets.switchyard.core.usage.Usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成本计算器（契约 PRC-001）—— <b>全仓唯一一处成本公式实现</b>。
 *
 * <p>公式（与三仓逐字一致）：
 *
 * <pre>
 *   billableInput  = usage.inputTokens() - usage.cachedInputTokens()
 *   cost = billableInput / 1000 * inputPrice
 *        + usage.cachedInputTokens() / 1000 * cachedInputPrice      // null 时取 inputPrice
 *        + usage.outputTokens() / 1000 * outputPrice
 *        + usage.reasoningTokens() / 1000 * reasoningPrice          // null 时取 outputPrice
 *   return cost.setScale(8, HALF_UP)
 * </pre>
 *
 * <p>⚠️ <b>金额禁止用 double</b>。原因很实际：一次调用成本大约 0.0002 元，用 double 累加一万次，
 * 误差会累积到可见的量级；而成本数据是要拿去和供应商账单对账的，对不上就会被财务质疑整个系统。 {@code BigDecimal} + scale=8 是唯一可靠的做法。
 *
 * <p><b>关于 cached token 不重复计费</b>：缓存命中的那部分 token 已经包含在 {@code inputTokens}
 * 里，所以公式第一步必须先扣出来单独按折扣价计。这一条如果写错， 成本会按全价把同一段 token 算两遍 —— 这是接入缓存后最常见的对账事故。
 */
public class CostCalculator {

    /** 内部核算精度。 */
    public static final int INTERNAL_SCALE = 8;

    /**
     * 计算一次调用的成本。
     *
     * @param usage 归一化后的用量，不可为 null
     * @param price 价格条目，可为 null（无价格条目时返回 0，并视为「未知成本」由调用方告警）
     * @return 成本金额，scale=8 HALF_UP，币种恒为 CNY
     */
    public BigDecimal compute(Usage usage, PriceEntry price) {
        if (usage == null) {
            throw new IllegalArgumentException("usage 不可为 null");
        }
        if (price == null) {
            return BigDecimal.ZERO.setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
        }
        if (price.unit() != PriceUnit.PER_1K_TOKENS) {
            throw new IllegalStateException("暂不支持的计价单位：" + price.unit());
        }

        BigDecimal billableInput = BigDecimal.valueOf(usage.billableInputTokens());
        BigDecimal cachedInput = BigDecimal.valueOf(usage.cachedInputTokens());
        BigDecimal output = BigDecimal.valueOf(usage.outputTokens());
        BigDecimal reasoning = BigDecimal.valueOf(usage.reasoningTokens());

        BigDecimal cost =
                billableInput
                        .divide(PriceEntry.THOUSAND, 12, RoundingMode.HALF_UP)
                        .multiply(price.inputPricePerUnit())
                        .add(
                                cachedInput
                                        .divide(PriceEntry.THOUSAND, 12, RoundingMode.HALF_UP)
                                        .multiply(price.effectiveCachedInputPrice()))
                        .add(
                                output.divide(PriceEntry.THOUSAND, 12, RoundingMode.HALF_UP)
                                        .multiply(price.outputPricePerUnit()))
                        .add(
                                reasoning
                                        .divide(PriceEntry.THOUSAND, 12, RoundingMode.HALF_UP)
                                        .multiply(price.effectiveReasoningPrice()));

        return cost.setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算多次调用的成本之和。
     *
     * <p>刻意逐次累加而不是先把 token 数加起来再算一次：不同模型的单价不同， 只有逐次计算才能得到正确结果。
     *
     * @param usages 用量序列
     * @param priceResolver 供应商标识到价格条目的解析函数，可为 null（则全部按 0 计）
     * @return 成本合计
     */
    public BigDecimal computeTotal(
            java.util.Collection<Usage> usages,
            java.util.function.BiFunction<String, String, PriceEntry> priceResolver) {
        if (usages == null || usages.isEmpty()) {
            return BigDecimal.ZERO.setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal total = BigDecimal.ZERO.setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
        for (Usage usage : usages) {
            if (usage == null) {
                continue;
            }
            BigDecimal one =
                    compute(usage, priceResolver == null ? null : priceResolver.apply("", ""));
            total = total.add(one);
        }
        return total.setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算单 token 均价，用于给用户解释「这次调用为什么贵」。
     *
     * @param cost 成本
     * @param tokens token 数
     * @return 每千 token 均价；token 为 0 时返回 0
     */
    public BigDecimal averagePerThousand(BigDecimal cost, long tokens) {
        if (cost == null || tokens <= 0) {
            return BigDecimal.ZERO.setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
        }
        return cost.multiply(PriceEntry.THOUSAND)
                .divide(BigDecimal.valueOf(tokens), INTERNAL_SCALE, RoundingMode.HALF_UP);
    }
}
