/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.quota;

import java.time.Instant;

/**
 * 配额预留凭据（PRD P0-4 预留-对账机制的第一半）。
 *
 * <p><b>为什么要「预留」而不是「事后扣费」</b>：事后扣费下，100 个并发请求会全部被放行 （因为请求发出时都还没扣），等它们全部回来结算时才发现超了 10 倍 —— 钱已经花掉了。
 * 预留把「拒绝」提前到请求发出之前，这才是配额能真正省钱的原因。
 *
 * <p><b>为什么要「对账」</b>：预留是按 {@code prompt估算 + max_tokens} 算的，必然大于实际。 如果只预留不结算，用户会发现「只用了几千
 * token，账上却少了整份 max_tokens」， 最终把配额调大到失去意义。多退少补让配额回到「按真实消耗计费」语义。
 *
 * @param reservationId 预留 ID，用于结算与释放时定位
 * @param granted 是否授予（false 表示配额不足，请求应当被 fail-soft 拒绝）
 * @param tokens 本次预留的 token 数
 * @param key 配额键
 * @param expireAt 预留过期时刻；超时未结算的预留由清理任务自动释放，防止泄漏
 * @param reason 未授予时的原因，可为 null
 */
public record Reservation(
        String reservationId,
        boolean granted,
        long tokens,
        QuotaKey key,
        Instant expireAt,
        String reason) {

    /** 预留有效期默认值。超过 10 分钟仍未结算的预留视为泄漏。 */
    public static final java.time.Duration DEFAULT_TTL = java.time.Duration.ofMinutes(10);

    /**
     * 授予的预留。
     *
     * @param id 预留 ID
     * @param tokens 预留 token 数
     * @param key 配额键
     * @param expireAt 过期时刻
     * @return Reservation
     */
    public static Reservation granted(String id, long tokens, QuotaKey key, Instant expireAt) {
        return new Reservation(id, true, tokens, key, expireAt, null);
    }

    /**
     * 被拒绝的预留。
     *
     * @param id 预留 ID
     * @param requested 申请预留的 token 数
     * @param key 配额键
     * @param reason 拒绝原因（面向用户可读）
     * @return Reservation
     */
    public static Reservation denied(String id, long requested, QuotaKey key, String reason) {
        return new Reservation(id, false, requested, key, Instant.now(), reason);
    }

    /** 是否已过期（用于清理泄漏的预留）。 */
    public boolean isExpired(Instant now) {
        return expireAt != null && now.isAfter(expireAt);
    }
}
