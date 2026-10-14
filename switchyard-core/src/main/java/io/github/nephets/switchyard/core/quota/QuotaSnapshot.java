/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.quota;

import java.time.Instant;

/**
 * 配额快照：某一时点的配额状态，同时作为控制台与 {@code X-Switchyard-Quota-Remaining} 响应头的数据源。
 *
 * @param key 配额键
 * @param used 已使用 token（含在途预留）
 * @param limit 上限；{@code <= 0} 表示不限额
 * @param remaining 剩余 token；不限额时为 {@link Long#MAX_VALUE}
 * @param resetAt 下次重置时刻
 * @param reservations 在途预留笔数
 */
public record QuotaSnapshot(
        QuotaKey key, long used, long limit, long remaining, Instant resetAt, int reservations) {

    /** 不限额时的剩余值。用 MAX_VALUE 而不是 -1：直接参与减法不会溢出成负数。 */
    public static final long UNLIMITED_REMAINING = Long.MAX_VALUE;

    /**
     * 是否已超限。
     *
     * @return 已用超过上限时返回 true；不限额恒为 false
     */
    public boolean exceeded() {
        return limit > 0 && used > limit;
    }

    /**
     * 使用率。
     *
     * @return 0~1 之间的小数；不限额返回 0
     */
    public double utilization() {
        if (limit <= 0) {
            return 0d;
        }
        return Math.min(1d, (double) used / (double) limit);
    }

    /** 是否不限额。 */
    public boolean unlimited() {
        return limit <= 0;
    }
}
