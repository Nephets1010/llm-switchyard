/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Usage 归一化契约（USG-001）")
class UsageNormalizerContractTest {

    private final DefaultUsageNormalizer normalizer = new DefaultUsageNormalizer();
    private final CharHeuristicTokenEstimator estimator = new CharHeuristicTokenEstimator();

    @Nested
    @DisplayName("第 5 步不变量：totalTokens 一律本仓重算")
    class TotalIsRecomputed {

        @Test
        @DisplayName("上游报的 total 与本仓重算值不同时，采用本仓重算值")
        void upstreamTotalIsIgnored() {
            // 上游报 total=99999（故意离谱），真实是 100 + 200 + 30
            RawUsage raw = RawUsage.openAi(100L, 200L, 0L, 30L, 99_999L);
            Usage usage = normalizer.normalize(raw, NormalizeContext.nonStreaming(500), estimator);

            assertThat(usage.totalTokens()).isEqualTo(330L).isNotEqualTo(99_999L);
            assertThat(usage.inputTokens()).isEqualTo(100L);
            assertThat(usage.outputTokens()).isEqualTo(200L);
            assertThat(usage.reasoningTokens()).isEqualTo(30L);
        }

        @Test
        @DisplayName("六家供应商的 total 口径差异都被统一为 input+output+reasoning")
        void totalIsUnifiedAcrossVendors() {
            // 六家上游分别报不同的 total，期望归一化后口径完全一致
            RawUsage[] samples = {
                RawUsage.openAi(1000L, 500L, 100L, 0L, 1_500L),
                RawUsage.anthropic(1000L, 500L, 100L, 900L),
                RawUsage.ollama(1000L, 500L),
                RawUsage.openAi(1000L, 500L, 0L, 200L, 1_700L),
                RawUsage.openAi(1000L, 500L, 50L, 50L, 999L),
                RawUsage.openAi(1000L, 500L, null, null, null)
            };

            for (RawUsage raw : samples) {
                Usage usage =
                        normalizer.normalize(raw, NormalizeContext.nonStreaming(4000), estimator);
                assertThat(usage.totalTokens())
                        .as("归一化后的 total 必须恒等于 input+output+reasoning")
                        .isEqualTo(
                                usage.inputTokens()
                                        + usage.outputTokens()
                                        + usage.reasoningTokens());
            }
        }
    }

    @Nested
    @DisplayName("流式计量不得为 0（PRD 痛点 4）")
    class StreamingUsage {

        @Test
        @DisplayName("流式且真收到 usage 块 → VENDOR_REPORTED 且 streamUsageReported=true")
        void streamWithUsageChunk() {
            RawUsage raw = RawUsage.openAi(820L, 156L, 512L, 0L, 976L);
            Usage usage =
                    normalizer.normalize(
                            raw, NormalizeContext.streaming("你好", 1000, true), estimator);

            assertThat(usage.usageSource()).isEqualTo(UsageSource.VENDOR_REPORTED);
            assertThat(usage.streamUsageReported()).isTrue();
            assertThat(usage.totalTokens()).isEqualTo(976L);
            assertThat(usage.totalTokens()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("★ 流式但供应商没回 usage → 回退估算，token 仍 > 0（这是修复前恒为 0 的坑）")
        void streamWithoutUsageChunkStillNonZero() {
            RawUsage raw = RawUsage.empty();
            String prompt = "请用三句话解释 CAP 定理";
            Usage usage =
                    normalizer.normalize(
                            raw, NormalizeContext.streaming(prompt, 500, false), estimator);

            assertThat(usage.usageSource()).isEqualTo(UsageSource.ESTIMATED);
            assertThat(usage.streamUsageReported()).isFalse();
            assertThat(usage.inputTokens()).as("流式计量绝不能是 0").isGreaterThan(0L);
            assertThat(usage.totalTokens()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("非流式即使 usage 在响应体里，streamUsageReported 也必须为 false")
        void nonStreamingNeverReportsStreamUsage() {
            RawUsage raw = RawUsage.openAi(100L, 50L, 0L, 0L, 150L);
            Usage usage =
                    normalizer.normalize(
                            raw, new NormalizeContext(false, "hi", "ok", 500, true), estimator);

            // 若这里写成 true，「流式计量丢失」这个告警将永远不触发
            assertThat(usage.streamUsageReported()).isFalse();
        }

        @Test
        @DisplayName("流式收到 usage 块时优先采信上游，忽略估算器")
        void vendorUsageBeatsEstimator() {
            RawUsage raw = RawUsage.openAi(1234L, 567L, 0L, 0L, 1801L);
            Usage usage =
                    normalizer.normalize(
                            raw, NormalizeContext.streaming("一段很长的中文提示词……", 2000, true), estimator);

            assertThat(usage.inputTokens()).isEqualTo(1234L);
            assertThat(usage.outputTokens()).isEqualTo(567L);
            assertThat(usage.usageSource()).isEqualTo(UsageSource.VENDOR_REPORTED);
        }
    }

    @Nested
    @DisplayName("缓存 token 与推理 token")
    class CachedAndReasoning {

        @Test
        @DisplayName("OpenAI prompt_tokens_details.cached_tokens 正确映射且不超过 inputTokens")
        void cachedTokensMapped() {
            RawUsage raw = RawUsage.openAi(2000L, 300L, 1536L, 0L, 2300L);
            Usage usage = normalizer.normalize(raw, NormalizeContext.nonStreaming(500), estimator);

            assertThat(usage.cachedInputTokens()).isEqualTo(1536L);
            assertThat(usage.billableInputTokens()).isEqualTo(464L);
            assertThat(usage.totalTokens()).isEqualTo(2300L);
        }

        @Test
        @DisplayName("cached 超过 input 时被夹紧，绝不允许重复计费")
        void cachedIsClampedToInput() {
            // 上游数据异常：cached > input
            RawUsage raw = RawUsage.openAi(100L, 50L, 500L, 0L, 150L);
            Usage usage = normalizer.normalize(raw, NormalizeContext.nonStreaming(500), estimator);

            assertThat(usage.cachedInputTokens()).isEqualTo(100L);
            assertThat(usage.billableInputTokens()).isZero();
        }

        @Test
        @DisplayName("DeepSeek 的 reasoning_tokens 计入 total")
        void reasoningCountedInTotal() {
            RawUsage raw = RawUsage.openAi(500L, 800L, 0L, 640L, 1_300L);
            Usage usage =
                    normalizer.normalize(raw, NormalizeContext.nonStreaming(2_000), estimator);

            assertThat(usage.reasoningTokens()).isEqualTo(640L);
            assertThat(usage.reasoningEstimated()).isFalse();
            assertThat(usage.totalTokens()).isEqualTo(500L + 800L + 640L);
        }

        @Test
        @DisplayName("供应商不返回 reasoning 时 reasoningEstimated=true 且 reasoningTokens=0")
        void reasoningEstimatedWhenAbsent() {
            RawUsage raw = RawUsage.openAi(100L, 100L, 0L, null, 200L);
            Usage usage = normalizer.normalize(raw, NormalizeContext.nonStreaming(500), estimator);

            assertThat(usage.reasoningTokens()).isZero();
            assertThat(usage.reasoningEstimated()).isTrue();
        }

        @Test
        @DisplayName("Anthropic 的 cache_read_input_tokens 归入 cachedInputTokens")
        void anthropicCacheReadMapped() {
            RawUsage raw = RawUsage.anthropic(4_000L, 300L, 0L, 3_000L);
            Usage usage =
                    normalizer.normalize(raw, NormalizeContext.nonStreaming(1_000), estimator);

            assertThat(usage.cachedInputTokens()).isEqualTo(3_000L);
            assertThat(usage.billableInputTokens()).isEqualTo(1_000L);
        }
    }

    @Nested
    @DisplayName("六家供应商 usage 结构归一化")
    class SixVendors {

        @ParameterizedTest(name = "{0} → source={1}")
        @DisplayName("各家结构都能落到同一套 Usage 口径")
        @CsvSource({
            "openai,    VENDOR_REPORTED",
            "anthropic, VENDOR_REPORTED",
            "dashscope, VENDOR_REPORTED",
            "deepseek,  VENDOR_REPORTED",
            "zhipu,     VENDOR_REPORTED",
            "moonshot,  VENDOR_REPORTED",
            "doubao,    VENDOR_REPORTED",
            "ollama,    VENDOR_REPORTED"
        })
        void allVendorsNormalize(String vendor, UsageSource expectedSource) {
            RawUsage raw =
                    switch (vendor) {
                        case "anthropic" -> RawUsage.anthropic(1_000L, 400L, 0L, 0L);
                        case "ollama" -> RawUsage.ollama(1_000L, 400L);
                        default -> RawUsage.openAi(1_000L, 400L, 0L, 0L, 1_400L);
                    };

            Usage usage =
                    normalizer.normalize(raw, NormalizeContext.nonStreaming(2_000), estimator);

            assertThat(usage.inputTokens()).isEqualTo(1_000L);
            assertThat(usage.outputTokens()).isEqualTo(400L);
            assertThat(usage.usageSource()).isEqualTo(expectedSource);
            assertThat(usage.totalTokens()).isEqualTo(1_400L);
        }
    }

    @Nested
    @DisplayName("Usage 不变量（fail-fast）")
    class Invariants {

        @ParameterizedTest
        @EnumSource(UsageSource.class)
        @DisplayName("负数 token 直接抛异常")
        void negativeTokensRejected(UsageSource source) {
            assertThatThrownBy(() -> new Usage(-1L, 0L, 0L, 0L, 0L, source, true, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不允许负值");
        }

        @Test
        @DisplayName("cached 大于 input 直接抛异常（fail-fast 在开发期，不留到运行时）")
        void cachedGreaterThanInputRejected() {
            assertThatThrownBy(
                            () ->
                                    new Usage(
                                            100L,
                                            0L,
                                            0L,
                                            200L,
                                            0L,
                                            UsageSource.VENDOR_REPORTED,
                                            true,
                                            false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("重复计费");
        }

        @Test
        @DisplayName("usageSource 不可为 null")
        void nullSourceRejected() {
            assertThatThrownBy(() -> new Usage(0L, 0L, 0L, 0L, 0L, null, true, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("usageSource");
        }

        @Test
        @DisplayName("total 由重算得出，与传入值无关")
        void totalAlwaysRecomputed() {
            Usage usage =
                    new Usage(
                            10L, 20L, 30L, 5L, 999_999L, UsageSource.VENDOR_REPORTED, false, false);
            assertThat(usage.totalTokens()).isEqualTo(60L);
        }
    }

    @Nested
    @DisplayName("工厂方法与派生")
    class Factories {

        @Test
        @DisplayName("Usage.of 重算 total")
        void ofRecomputes() {
            assertThat(Usage.of(100L, 50L).totalTokens()).isEqualTo(150L);
            assertThat(Usage.vendorReported(100L, 50L).usageSource())
                    .isEqualTo(UsageSource.VENDOR_REPORTED);
        }

        @Test
        @DisplayName("Usage.estimated 全零但来源明确")
        void estimatedIsZero() {
            Usage usage = Usage.estimated(UsageSource.ESTIMATED, false);
            assertThat(usage.isZero()).isTrue();
            assertThat(usage.estimatedOnly()).isTrue();
            assertThat(usage.totalTokens()).isZero();
        }

        @Test
        @DisplayName("withSource 保留用量只改来源")
        void withSourceKeepsUsage() {
            Usage original = Usage.of(100L, 50L);
            Usage derived = original.withSource(UsageSource.ESTIMATED, true);
            assertThat(derived.inputTokens()).isEqualTo(100L);
            assertThat(derived.totalTokens()).isEqualTo(150L);
            assertThat(derived.usageSource()).isEqualTo(UsageSource.ESTIMATED);
            assertThat(derived.streamUsageReported()).isTrue();
        }
    }
}
