/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("重试策略：指数退避 + 抖动")
class RetryPolicyTest {

    @Test
    @DisplayName("默认策略是 3 次尝试（对齐架构验收标准「429 连续 3 次后成功」）")
    void defaultsMatchAcceptance() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(2)).isTrue();
        assertThat(policy.canRetry(3)).isFalse();
    }

    @Test
    @DisplayName("退避时长按指数增长")
    void exponentialBackoff() {
        RetryPolicy policy =
                new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(10), 2.0d, 0d);

        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.backoffFor(4)).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    @DisplayName("退避时长有上限，不会无限增长")
    void backoffIsCapped() {
        RetryPolicy policy =
                new RetryPolicy(20, Duration.ofMillis(100), Duration.ofMillis(500), 2.0d, 0d);

        for (int attempt = 1; attempt <= 19; attempt++) {
            assertThat(policy.backoffFor(attempt)).isLessThanOrEqualTo(Duration.ofMillis(500));
        }
    }

    @Test
    @DisplayName("★ 抖动存在时退避会在基准值上下浮动（防重试风暴）")
    void jitterVariesBackoff() {
        RetryPolicy policy =
                new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(5), 2.0d, 0.2d);
        java.util.Random random = new java.util.Random(42L);

        long base = 100L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long actual = policy.backoffFor(1, random).toMillis();
            min = Math.min(min, actual);
            max = Math.max(max, actual);
        }

        assertThat(max).as("上浮不超过 (1+jitter)").isLessThanOrEqualTo((long) (base * 1.2) + 1);
        assertThat(min).as("下压不低于 (1-jitter)").isGreaterThanOrEqualTo((long) (base * 0.8) - 1);
        assertThat(max - min).as("抖动必须真的产生差异，否则 100 个并发请求会同时重试").isPositive();
    }

    @Test
    @DisplayName("jitter=0 时退避确定（便于精确断言）")
    void zeroJitterIsDeterministic() {
        RetryPolicy policy =
                new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(2), 2.0d, 0d);
        java.util.Random random = new java.util.Random(1L);

        assertThat(policy.backoffFor(2, random)).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    @DisplayName("非法参数被纠正或拒绝，不会让退避变成负数")
    void invalidParametersCorrected() {
        RetryPolicy corrected =
                new RetryPolicy(-1, Duration.ofMillis(-5), Duration.ofMillis(1), 0.5d, 3.0d);

        assertThat(corrected.maxAttempts()).isEqualTo(1);
        assertThat(corrected.initialBackoff()).isPositive();
        assertThat(corrected.maxBackoff()).isGreaterThanOrEqualTo(corrected.initialBackoff());
        assertThat(corrected.multiplier()).isEqualTo(1.0d);
        assertThat(corrected.jitter()).as("越界的抖动归零而不是抛异常").isZero();
    }

    @Test
    @DisplayName("noRetry 策略只尝试一次")
    void noRetryPolicy() {
        RetryPolicy policy = RetryPolicy.noRetry();

        assertThat(policy.maxAttempts()).isEqualTo(1);
        assertThat(policy.canRetry(1)).isFalse();
        assertThat(policy.backoffFor(1)).isZero();
    }

    @Test
    @DisplayName("attempt ≤ 0 时无退避")
    void nonPositiveAttempt() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.backoffFor(0)).isZero();
        assertThat(policy.backoffFor(-1)).isZero();
    }
}
