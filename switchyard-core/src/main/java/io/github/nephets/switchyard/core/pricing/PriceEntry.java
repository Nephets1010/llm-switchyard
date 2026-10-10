/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 价格条目（契约 PRC-001）。
 *
 * <p>⚠️ <b>禁止在代码里编造价格</b>：内置价格表中的每一条都必须标注 {@link PriceSource} 与 {@link #effectiveFrom()}（架构开放问题
 * Q-B）。{@link PriceSource#ESTIMATED} 的条目在 控制台上必须显式标「社区估算」，否则用户会拿一个我们自己猜的数去做预算决策。
 *
 * <p>内部核算一律用 {@code CNY}（契约硬约束）。对外展示层负责换汇 —— 汇率波动是展示层的事， 不应该让成本核算的数值随汇率每天变化。
 *
 * @param providerId 供应商标识，取值见 {@code ProviderCatalog}（全小写）
 * @param modelId 供应商侧模型名
 * @param currency ISO 4217，契约固定 {@code CNY}
 * @param unit 计价单位，固定 {@link PriceUnit#PER_1K_TOKENS}
 * @param inputPricePerUnit 每 1000 输入 token 价格
 * @param outputPricePerUnit 每 1000 输出 token 价格
 * @param reasoningPricePerUnit 每 1000 推理 token 价格；null 表示按 {@code outputPricePerUnit} 计
 * @param cachedInputPricePerUnit 每 1000 缓存命中输入 token 价格；null 表示按 {@code inputPricePerUnit} 计
 * @param effectiveFrom 生效日
 * @param source 价格来源
 */
public record PriceEntry(
        String providerId,
        String modelId,
        String currency,
        PriceUnit unit,
        BigDecimal inputPricePerUnit,
        BigDecimal outputPricePerUnit,
        BigDecimal reasoningPricePerUnit,
        BigDecimal cachedInputPricePerUnit,
        LocalDate effectiveFrom,
        PriceSource source) {

    /** 契约固定的核算币种。 */
    public static final String CURRENCY_CNY = "CNY";

    /** 计价分母：每 1000 token。 */
    public static final BigDecimal THOUSAND = BigDecimal.valueOf(1000L);

    /**
     * 紧凑构造器：校验必填项与价格非负。
     *
     * @throws IllegalArgumentException 必填字段为空或价格为负时
     */
    public PriceEntry {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId 不可为空");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不可为空");
        }
        if (currency == null || currency.isBlank()) {
            currency = CURRENCY_CNY;
        }
        if (unit == null) {
            unit = PriceUnit.PER_1K_TOKENS;
        }
        if (inputPricePerUnit == null || outputPricePerUnit == null) {
            throw new IllegalArgumentException("输入/输出单价不可为空");
        }
        if (inputPricePerUnit.signum() < 0 || outputPricePerUnit.signum() < 0) {
            throw new IllegalArgumentException("价格不可为负");
        }
        if (reasoningPricePerUnit != null && reasoningPricePerUnit.signum() < 0) {
            throw new IllegalArgumentException("推理单价不可为负");
        }
        if (cachedInputPricePerUnit != null && cachedInputPricePerUnit.signum() < 0) {
            throw new IllegalArgumentException("缓存输入单价不可为负");
        }
        if (effectiveFrom == null) {
            effectiveFrom = LocalDate.of(2026, 1, 1);
        }
        if (source == null) {
            source = PriceSource.CUSTOM;
        }
    }

    /** 便捷工厂：使用默认币种与单位。 */
    public static PriceEntry of(
            String providerId,
            String modelId,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            LocalDate effectiveFrom,
            PriceSource source) {
        return new PriceEntry(
                providerId,
                modelId,
                CURRENCY_CNY,
                PriceUnit.PER_1K_TOKENS,
                inputPrice,
                outputPrice,
                null,
                null,
                effectiveFrom,
                source);
    }

    /** 推理 token 的实际单价：null 时回落到输出单价。 */
    public BigDecimal effectiveReasoningPrice() {
        return reasoningPricePerUnit == null ? outputPricePerUnit : reasoningPricePerUnit;
    }

    /** 缓存输入 token 的实际单价：null 时回落到输入单价。 */
    public BigDecimal effectiveCachedInputPrice() {
        return cachedInputPricePerUnit == null ? inputPricePerUnit : cachedInputPricePerUnit;
    }

    /** 查表键：{@code providerId/modelId}。 */
    public String key() {
        return key(providerId, modelId);
    }

    /**
     * 构造查表键。
     *
     * @param providerId 供应商标识
     * @param modelId 模型名
     * @return 组合键
     */
    public static String key(String providerId, String modelId) {
        return (providerId == null ? "" : providerId) + "/" + (modelId == null ? "" : modelId);
    }
}
