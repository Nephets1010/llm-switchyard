/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.quota;

/**
 * 配额策略：周期、上限、超限行为。
 *
 * @param period 计量周期
 * @param limit 周期内允许的 token 上限；{@code <= 0} 表示不限额
 * @param onExceeded 超限行为
 */
public record QuotaPolicy(Period period, long limit, OverLimitBehavior onExceeded) {

    /** 不限额策略（本地开发与压测用）。 */
    public static QuotaPolicy unlimitedPolicy() {
        return new QuotaPolicy(Period.DAILY, 0L, OverLimitBehavior.FAIL_SOFT);
    }

    /** 紧凑构造器：填充默认值。 */
    public QuotaPolicy {
        if (period == null) {
            period = Period.DAILY;
        }
        if (onExceeded == null) {
            onExceeded = OverLimitBehavior.FAIL_SOFT;
        }
    }

    /**
     * 构造一个月度预算策略。
     *
     * @param limitToken 月度 token 上限
     * @return QuotaPolicy
     */
    public static QuotaPolicy monthly(long limitToken) {
        return new QuotaPolicy(Period.MONTHLY, limitToken, OverLimitBehavior.FAIL_SOFT);
    }

    /** 是否为不限额（{@code limit <= 0}）。 */
    public boolean unlimited() {
        return limit <= 0;
    }

    /** 配额计量周期。 */
    public enum Period {
        /** 每分钟重置。 */
        MINUTE,
        /** 每小时重置。 */
        HOURLY,
        /** 每天重置。 */
        DAILY,
        /** 每月重置（自然月）。 */
        MONTHLY
    }
}
