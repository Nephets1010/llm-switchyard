/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("字符启发式 Token 估算器")
class CharHeuristicTokenEstimatorTest {

    private final CharHeuristicTokenEstimator estimator = new CharHeuristicTokenEstimator();

    @Test
    @DisplayName("null 与空串返回 0")
    void emptyInput() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("")).isZero();
    }

    @Test
    @DisplayName("非空文本至少返回 1（宁可略高也不低估，避免配额被穿透）")
    void atLeastOne() {
        assertThat(estimator.estimate("a")).isEqualTo(1L);
        assertThat(estimator.estimate("。")).isEqualTo(1L);
    }

    @Test
    @DisplayName("纯中文约 1 字 1 token")
    void chinese() {
        // 20 个汉字 → 20 token
        String text = "你好世界这是一个用于测试的中文句子长度";
        assertThat(text.length()).isEqualTo(19);
        assertThat(estimator.estimate(text)).isBetween(19L, 22L);
    }

    @Test
    @DisplayName("纯 ASCII 约 4 字符 1 token")
    void ascii() {
        String text = "abcdefghijklmnopqrst"; // 20 个字符 → 5 token
        assertThat(estimator.estimate(text)).isEqualTo(5L);
    }

    @ParameterizedTest(name = "「{0}」→ {1} token")
    @DisplayName("中英混排能正确分段估算")
    @CsvSource({"'hello', 2", "'hello world', 3", "'你好world', 4", "'DeepSeek-V3 是通义', 7"})
    void mixedContent(String text, int expected) {
        assertThat(estimator.estimate(text)).isEqualTo((long) expected);
    }

    @Test
    @DisplayName("长数字串按 3 位一个 token 切（对齐 BPE 对数字的典型行为）")
    void digitRuns() {
        // 9 位数字 → 3 个 token
        assertThat(estimator.estimate("123456789")).isEqualTo(3L);
    }

    @Test
    @DisplayName("估算结果随文本长度单调递增")
    void monotonic() {
        long previous = 0;
        for (int i = 1; i <= 50; i++) {
            long current = estimator.estimate("数".repeat(i));
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("emoji 等增补平面字符不抛异常")
    void surrogatePairs() {
        // emoji 是代理对（2 个 char，1 个 codePoint），按 2 字符 ≈ 1 token 折算，不崩即可
        assertThat(estimator.estimate("你好😀世界")).isEqualTo(5L);
    }

    @Test
    @DisplayName("自定义参数生效")
    void customParameters() {
        CharHeuristicTokenEstimator strict = new CharHeuristicTokenEstimator(2.0d, 2.0d);
        // 10 个汉字，2 token/字 → 20
        assertThat(strict.estimate("一二三四五六七八九十")).isEqualTo(20L);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0d, -1.0d})
    @DisplayName("非法参数直接拒绝")
    void invalidParameters(double bad) {
        assertThatThrownBy(() -> new CharHeuristicTokenEstimator(bad, 4.0d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CharHeuristicTokenEstimator(1.0d, bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("可作为 TokenEstimator SPI 替换")
    void usableAsSpi() {
        TokenEstimator spi = estimator;
        DefaultUsageNormalizer normalizer = new DefaultUsageNormalizer();
        Usage usage =
                normalizer.normalize(
                        RawUsage.empty(), NormalizeContext.streaming("估算测试", 100, false), spi);

        assertThat(usage.usageSource()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(usage.inputTokens()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("自定义分词器可整体替换估算结果（SPI 可替换性验证）")
    void replaceableEstimator() {
        TokenEstimator always100 = text -> 100L;
        Usage usage =
                new DefaultUsageNormalizer()
                        .normalize(
                                RawUsage.empty(),
                                NormalizeContext.streaming("x", 999, false),
                                always100);

        assertThat(usage.inputTokens()).isEqualTo(100L);
        assertThat(usage.outputTokens()).isZero();
        // 没有 maxTokens 且估算器给了值 → ESTIMATED（不是 FALLBACK）
        assertThat(usage.usageSource()).isEqualTo(UsageSource.ESTIMATED);
    }
}
