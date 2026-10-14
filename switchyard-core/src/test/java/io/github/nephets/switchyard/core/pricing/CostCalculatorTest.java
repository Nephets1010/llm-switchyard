/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.pricing;

import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("成本计算（PRC-001）")
class CostCalculatorTest {

    private final CostCalculator calculator = new CostCalculator();

    /** 每千 token 输入 0.002 元、输出 0.006 元的样例价格，便于手算核对。 */
    private static final PriceEntry SIMPLE =
            PriceEntry.of(
                    "openai",
                    "test-model",
                    new BigDecimal("0.002"),
                    new BigDecimal("0.006"),
                    LocalDate.of(2026, 1, 1),
                    PriceSource.OFFICIAL);

    @Nested
    @DisplayName("基本公式")
    class BasicFormula {

        @Test
        @DisplayName("1000 输入 + 1000 输出 = 0.002 + 0.006 = 0.00800000")
        void oneThousandEach() {
            BigDecimal cost = calculator.compute(Usage.of(1_000L, 1_000L), SIMPLE);
            assertThat(cost).isEqualByComparingTo("0.00800000");
            assertThat(cost.scale()).isEqualTo(8);
        }

        @Test
        @DisplayName("非整千按比例折算")
        void partialThousand() {
            // 500 输入 = 0.001，500 输出 = 0.003 → 0.004
            assertThat(calculator.compute(Usage.of(500L, 500L), SIMPLE))
                    .isEqualByComparingTo("0.00400000");
        }

        @Test
        @DisplayName("金额一律 scale=8 HALF_UP（禁止 double 误差）")
        void scaleIsAlwaysEight() {
            // 1 token 的费用必然有无限小数，必须被正确舍入而不是变成 0.00200000001
            BigDecimal cost = calculator.compute(Usage.of(1L, 1L), SIMPLE);
            assertThat(cost.scale()).isEqualTo(8);
            assertThat(cost).isEqualByComparingTo("0.000008");
        }

        @Test
        @DisplayName("零用量成本为 0.00000000")
        void zeroUsage() {
            assertThat(calculator.compute(Usage.of(0L, 0L), SIMPLE))
                    .isEqualByComparingTo("0.00000000");
        }

        @Test
        @DisplayName("无价格条目时按 0 计（由调用方告警），不抛异常")
        void nullPriceReturnsZero() {
            assertThat(calculator.compute(Usage.of(1_000L, 1_000L), null))
                    .isEqualByComparingTo("0.00000000");
        }

        @Test
        @DisplayName("usage 为 null 直接拒绝（fail-fast）")
        void nullUsageRejected() {
            assertThatThrownBy(() -> calculator.compute(null, SIMPLE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("★ 缓存 token 不重复计费")
    class CachedTokens {

        /** 缓存命中价按输入价 1/10：0.0002 / 1000 token。 */
        private static final PriceEntry WITH_CACHE =
                new PriceEntry(
                        "openai",
                        "cached-model",
                        "CNY",
                        PriceUnit.PER_1K_TOKENS,
                        new BigDecimal("0.002"),
                        new BigDecimal("0.006"),
                        null,
                        new BigDecimal("0.0002"),
                        LocalDate.of(2026, 1, 1),
                        PriceSource.OFFICIAL);

        @Test
        @DisplayName("缓存部分按折扣价、非缓存部分按全价，两者相加")
        void cachedDiscountApplied() {
            // 2000 输入中 1500 命中缓存 → 计费 = 500*0.002/1000 + 1500*0.0002/1000 + 0 输出
            // = 0.001 + 0.0003 = 0.0013
            Usage usage =
                    Usage.of(2_000L, 0L, 0L, 1_500L, UsageSource.VENDOR_REPORTED, false, false);
            assertThat(calculator.compute(usage, WITH_CACHE)).isEqualByComparingTo("0.00130000");
        }

        @Test
        @DisplayName("★ 缓存单价为 null 时回落到输入全价（而不是 0，也不是报错）")
        void cachedPriceFallsBackToInputPrice() {
            // 无缓存价条目：2000 输入（1500 缓存）+ 1000 输出 = 500*0.002 + 1500*0.002 + 1000*0.006 (每千)
            // = 0.001 + 0.003 + 0.006 = 0.010
            Usage usage =
                    Usage.of(2_000L, 1_000L, 0L, 1_500L, UsageSource.VENDOR_REPORTED, false, false);
            assertThat(calculator.compute(usage, SIMPLE)).isEqualByComparingTo("0.01000000");
        }

        @Test
        @DisplayName("全命中缓存时输入部分只按折扣价计")
        void fullCacheHit() {
            Usage usage =
                    Usage.of(1_000L, 0L, 0L, 1_000L, UsageSource.VENDOR_REPORTED, false, false);
            // 全部命中：1000 * 0.0002 / 1000 = 0.0002
            assertThat(calculator.compute(usage, WITH_CACHE)).isEqualByComparingTo("0.00020000");
        }
    }

    @Nested
    @DisplayName("★ 推理 token 计价")
    class ReasoningTokens {

        @Test
        @DisplayName("独立推理价生效")
        void separateReasoningPrice() {
            PriceEntry entry =
                    new PriceEntry(
                            "deepseek",
                            "reasoner",
                            "CNY",
                            PriceUnit.PER_1K_TOKENS,
                            new BigDecimal("0.004"),
                            new BigDecimal("0.016"),
                            new BigDecimal("0.002"),
                            null,
                            LocalDate.of(2026, 1, 1),
                            PriceSource.OFFICIAL);

            // 1000 输入(0.004) + 1000 输出(0.016) + 1000 推理(0.002) = 0.022
            Usage usage =
                    Usage.of(1_000L, 1_000L, 1_000L, 0L, UsageSource.VENDOR_REPORTED, false, false);
            assertThat(calculator.compute(usage, entry)).isEqualByComparingTo("0.02200000");
        }

        @Test
        @DisplayName("推理价为 null 时按输出价计（多数供应商的实际情况）")
        void reasoningPriceFallsBackToOutputPrice() {
            // 1000 输入(0.002) + 1000 输出(0.006) + 1000 推理(按输出价 0.006) = 0.014
            Usage usage =
                    Usage.of(1_000L, 1_000L, 1_000L, 0L, UsageSource.VENDOR_REPORTED, false, false);
            assertThat(calculator.compute(usage, SIMPLE)).isEqualByComparingTo("0.01400000");
        }
    }

    @Nested
    @DisplayName("精度不变量（PRD 仓库③ P0-3：与账单误差 < 1%）")
    class PrecisionInvariants {

        @ParameterizedTest(name = "{0} 需求 token → 成本 {1}")
        @DisplayName("8 位精度下与手算结果完全一致")
        @CsvSource({
            "1000,  0.00200000",
            "2000,  0.00400000",
            "10000, 0.02000000",
            "100,   0.00020000",
            "333,   0.00066600",
            "7777,  0.01555400"
        })
        void exactDecimalArithmetic(long tokens, String expected) {
            BigDecimal cost = calculator.compute(Usage.of(tokens, 0L), SIMPLE);
            assertThat(cost).isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("★ 一万次累加不产生 double 级别的漂移（BigDecimal 的核心价值）")
        void noFloatingPointDrift() {
            // 0.1 累加一万次：double 会得到 1000.0000000001588，BigDecimal 精确等于 1000
            BigDecimal unit = calculator.compute(Usage.of(1L, 0L), SIMPLE);
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < 10_000; i++) {
                total = total.add(unit);
            }

            assertThat(total).isEqualByComparingTo("0.02000000");
            assertThat(total.scale()).isEqualTo(8);
        }

        @Test
        @DisplayName("均价反算可用于向用户解释成本构成")
        void averagePerThousand() {
            BigDecimal cost = calculator.compute(Usage.of(1_000L, 1_000L), SIMPLE);
            // 2000 token 共 0.008 元 → 每千 token 均价 0.004
            assertThat(calculator.averagePerThousand(cost, 2_000L))
                    .isEqualByComparingTo("0.00400000");
            assertThat(calculator.averagePerThousand(cost, 0L)).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("价格条目校验")
    class Validation {

        @Test
        @DisplayName("负价直接拒绝")
        void negativePriceRejected() {
            assertThatThrownBy(
                            () ->
                                    PriceEntry.of(
                                            "x",
                                            "y",
                                            new BigDecimal("-1"),
                                            BigDecimal.ONE,
                                            LocalDate.now(),
                                            PriceSource.OFFICIAL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不可为负");
        }

        @Test
        @DisplayName("必填字段为空直接拒绝")
        void requiredFields() {
            assertThatThrownBy(
                            () ->
                                    PriceEntry.of(
                                            "",
                                            "y",
                                            BigDecimal.ONE,
                                            BigDecimal.ONE,
                                            LocalDate.now(),
                                            PriceSource.OFFICIAL))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    PriceEntry.of(
                                            "x",
                                            null,
                                            BigDecimal.ONE,
                                            BigDecimal.ONE,
                                            LocalDate.now(),
                                            PriceSource.OFFICIAL))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("币种默认 CNY（契约硬约束）")
        void currencyDefaultsToCny() {
            assertThat(SIMPLE.currency()).isEqualTo("CNY");
            assertThat(PriceEntry.CURRENCY_CNY).isEqualTo("CNY");
        }

        @Test
        @DisplayName("未显式指定来源时归为 CUSTOM（而非假装 OFFICIAL）")
        void sourceDefaultsToCustom() {
            PriceEntry entry =
                    new PriceEntry(
                            "x",
                            "y",
                            "CNY",
                            PriceUnit.PER_1K_TOKENS,
                            BigDecimal.ONE,
                            BigDecimal.ONE,
                            null,
                            null,
                            LocalDate.of(2026, 1, 1),
                            null);
            assertThat(entry.source()).isEqualTo(PriceSource.CUSTOM);
        }
    }
}
