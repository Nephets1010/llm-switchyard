/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import java.time.Duration;

/**
 * 重试策略（PRD P0-6 语义化重试）。
 *
 * <p><b>退避算法</b>：指数退避 + 抖动（{@code base * 2^(attempt-1) * random(1-jitter, 1+jitter)}）。
 * 抖动不是可选项：没有抖动时，100 个并发请求遇到 429 会同时在同一毫秒重试， 把刚恢复的配额瞬间再次打爆，形成「重试风暴」。
 *
 * @param maxAttempts 最大尝试次数（含首次）。{@code <= 1} 表示不重试
 * @param initialBackoff 首次退避时长
 * @param maxBackoff 退避上限
 * @param multiplier 退避倍数
 * @param jitter 抖动比例，{@code 0~1}。0 表示无抖动
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double multiplier,
        double jitter) {

    /** 架构文档验收标准里的「429 连续 3 次后成功」对应的默认策略。 */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(2), 2.0d, 0.2d);
    }

    /** 紧凑构造器：填充默认值并做范围校验。 */
    public RetryPolicy {
        if (maxAttempts <= 0) {
            maxAttempts = 1;
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            initialBackoff = Duration.ofMillis(200);
        }
        if (maxBackoff == null || maxBackoff.isNegative()) {
            maxBackoff = Duration.ofSeconds(2);
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            maxBackoff = initialBackoff;
        }
        if (multiplier < 1.0d) {
            multiplier = 1.0d;
        }
        if (jitter < 0.0d || jitter > 1.0d) {
            jitter = 0.0d;
        }
    }

    /** 不重试的策略。 */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0d, 0.0d);
    }

    /**
     * 计算第 {@code attempt} 次失败后应等待的时长（attempt 从 1 开始，即第一次失败后 attempt=1）。
     *
     * @param attempt 第几次失败
     * @param random 抖动随机源，便于测试可复现
     * @return 退避时长
     */
    public Duration backoffFor(int attempt, java.util.random.RandomGenerator random) {
        if (attempt <= 0 || jitter == 0.0d) {
            return rawBackoff(attempt);
        }
        double base = rawBackoff(attempt).toMillis();
        double factor = 1.0d + (random.nextDouble() * 2.0d - 1.0d) * jitter;
        long jittered = Math.round(base * factor);
        return Duration.ofMillis(Math.max(0L, Math.min(jittered, maxBackoff.toMillis())));
    }

    /** 计算退避时长（不抖动）。 */
    public Duration backoffFor(int attempt) {
        return rawBackoff(attempt);
    }

    /** 是否还能再试。 */
    public boolean canRetry(int attempt) {
        return attempt < maxAttempts;
    }

    private Duration rawBackoff(int attempt) {
        if (attempt <= 0) {
            return Duration.ZERO;
        }
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1);
        return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
    }
}
