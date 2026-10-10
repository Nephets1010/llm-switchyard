/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.quota;

import io.github.nephets.switchyard.core.usage.Usage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单实例内存配额存储（默认实现）。
 *
 * <p><b>原子性怎么保证</b>：按配额键分桶，每个桶一把 {@link java.util.concurrent.locks.ReentrantLock}， 在锁内完成「检查上限 → 记账
 * → 生成预留」三步。分桶加锁而不是全局锁，是为了让 不同租户的请求互不阻塞 —— 一个大租户打满自己的额度时，不该拖慢其他租户。
 *
 * <p><b>为什么不用 AtomicLong 的 CAS 循环</b>：CAS 需要「读-改-写」三次操作且无法在 「检查 +
 * 记账」之间保持一致；而我们的判断依据不止一个数字（还有策略表、预留数）， 用锁把这段临界区圈起来更直接，也更容易证明正确。
 *
 * <p><b>泄漏防护</b>：预留带 TTL，超过 TTL 未结算的预留由 {@link #purgeExpired()} 回收。 没有它，一次进程崩溃就会让租户的额度被永久扣光 ——
 * 这是嵌入式网关最容易被投诉的 bug。
 */
public class InMemoryQuotaStore implements QuotaStore {

    private final Map<QuotaKey, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<QuotaKey, QuotaPolicy> policies = new ConcurrentHashMap<>();
    private final Clock clock;
    private final java.time.Duration reservationTtl;
    private final AtomicLong reservationSeq = new AtomicLong();

    /** 使用系统时钟与默认 TTL（10 分钟）。 */
    public InMemoryQuotaStore() {
        this(Clock.systemUTC(), Reservation.DEFAULT_TTL);
    }

    /**
     * 使用指定时钟与 TTL（测试注入可复现时钟）。
     *
     * @param clock 时钟
     * @param reservationTtl 预留有效期
     */
    public InMemoryQuotaStore(Clock clock, java.time.Duration reservationTtl) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.reservationTtl = reservationTtl == null ? Reservation.DEFAULT_TTL : reservationTtl;
    }

    @Override
    public Reservation reserve(QuotaKey key, long tokens) {
        if (key == null) {
            throw new IllegalArgumentException("配额键不可为 null");
        }
        long requested = Math.max(0L, tokens);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        Instant now = clock.instant();
        String id =
                "rsv-"
                        + reservationSeq.incrementAndGet()
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8);

        bucket.lock.lock();
        try {
            rollPeriodIfNeeded(bucket, key, now);
            QuotaPolicy policy = policies.get(key);
            long limit = policy == null ? 0L : policy.limit();

            if (limit > 0) {
                long used = bucket.used.get();
                // 剩余额度 < 申请量 → 拒绝。这就是「请求前拒绝」，PRD P0-4 的核心。
                if (used + requested > limit) {
                    long remaining = Math.max(0L, limit - used);
                    return Reservation.denied(
                            id,
                            requested,
                            key,
                            "配额已用尽（已用 "
                                    + used
                                    + " / 上限 "
                                    + limit
                                    + " tokens，本次需要 "
                                    + requested
                                    + "，剩余 "
                                    + remaining
                                    + "）");
                }
            }

            bucket.used.addAndGet(requested);
            bucket.activeReservations.put(id, new ActiveReservation(requested, now));
            return Reservation.granted(id, requested, key, now.plus(reservationTtl));
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public QuotaSnapshot settle(Reservation reservation, Usage actual) {
        if (reservation == null || !reservation.granted()) {
            return reservation == null ? emptySnapshot(null) : snapshot(reservation.key());
        }
        QuotaKey key = reservation.key();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        long reserved = reservation.tokens();
        long real = actual == null ? 0L : Math.max(0L, actual.totalTokens());

        bucket.lock.lock();
        try {
            Instant now = clock.instant();
            rollPeriodIfNeeded(bucket, key, now);
            bucket.activeReservations.remove(reservation.reservationId());

            // 多退：实际用量小于预留 → 退回差额
            // 少补：实际用量大于预留（估算偏低）→ 补扣差额，此时可能把额度用成负数（透支），
            //       这是刻意保留的：配额宁可短暂透支也不能让真实消耗「不算钱」，否则对账就成了摆设。
            long delta = real - reserved;
            if (delta != 0L) {
                bucket.used.addAndGet(delta);
            }
            if (bucket.used.get() < 0) {
                bucket.used.set(0L);
            }
            return snapshotOf(key, bucket, now);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public void release(Reservation reservation) {
        if (reservation == null || !reservation.granted()) {
            return;
        }
        QuotaKey key = reservation.key();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return;
        }
        bucket.lock.lock();
        try {
            // 幂等：同一笔预留重复 release 只生效一次
            if (bucket.activeReservations.remove(reservation.reservationId()) != null) {
                long restored = Math.min(bucket.used.get(), Math.max(0L, reservation.tokens()));
                bucket.used.addAndGet(-restored);
                if (bucket.used.get() < 0) {
                    bucket.used.set(0L);
                }
            }
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public QuotaSnapshot snapshot(QuotaKey key) {
        if (key == null) {
            return emptySnapshot(null);
        }
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return emptySnapshot(key);
        }
        Instant now = clock.instant();
        bucket.lock.lock();
        try {
            rollPeriodIfNeeded(bucket, key, now);
            return snapshotOf(key, bucket, now);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public void putPolicy(QuotaKey key, QuotaPolicy policy) {
        if (key == null) {
            throw new IllegalArgumentException("配额键不可为 null");
        }
        policies.put(key, policy);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        bucket.lock.lock();
        try {
            // ★ 设置策略时必须重置计数。
            //   运维把上限从 1000 调到 10000 时，若沿用旧计数，新额度要等一整个周期才能用满；
            //   更糟的是「把上限调到 100 以止损」后，因为已用量 > 新上限，
            //   所有请求会永久被拒 —— 止损操作反而让服务彻底不可用。
            //   「设置配额」的语义是「从现在开始按新规则计费」，所以清零是唯一正确的行为。
            bucket.used.set(0L);
            bucket.activeReservations.clear();
            bucket.periodBoundary.set(Long.MIN_VALUE);
            rollPeriodIfNeeded(bucket, key, clock.instant());
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public QuotaPolicy policyOf(QuotaKey key) {
        return key == null ? null : policies.get(key);
    }

    @Override
    public int purgeExpired() {
        Instant now = clock.instant();
        int purged = 0;
        for (Bucket bucket : buckets.values()) {
            bucket.lock.lock();
            try {
                for (Map.Entry<String, ActiveReservation> entry :
                        new ArrayList<>(bucket.activeReservations.entrySet())) {
                    ActiveReservation active = entry.getValue();
                    if (active.createdAt().plus(reservationTtl).isBefore(now)) {
                        // 先移除再退账，保证与 release() 的幂等语义一致：同一笔预留只会被回收一次
                        if (bucket.activeReservations.remove(entry.getKey()) != null) {
                            bucket.used.addAndGet(
                                    -Math.min(bucket.used.get(), Math.max(0L, active.tokens())));
                            if (bucket.used.get() < 0) {
                                bucket.used.set(0L);
                            }
                            purged++;
                        }
                    }
                }
            } finally {
                bucket.lock.unlock();
            }
        }
        return purged;
    }

    @Override
    public void reset() {
        buckets.clear();
        policies.clear();
    }

    /** 全部配额键快照，供控制台列举。 */
    public Collection<QuotaKey> keys() {
        return List.copyOf(buckets.keySet());
    }

    /** 周期滚动：跨周期时清零用量。周期判断基于自然时间边界。 */
    private void rollPeriodIfNeeded(Bucket bucket, QuotaKey key, Instant now) {
        QuotaPolicy policy = policies.get(key);
        QuotaPolicy.Period period = policy == null ? QuotaPolicy.Period.DAILY : policy.period();
        long boundary = boundaryOf(period, now);
        if (bucket.periodBoundary.get() != boundary) {
            bucket.used.set(0L);
            bucket.activeReservations.clear();
            bucket.periodBoundary.set(boundary);
        }
    }

    private static long boundaryOf(QuotaPolicy.Period period, Instant now) {
        Instant truncated =
                switch (period) {
                    case MINUTE -> now.truncatedTo(ChronoUnit.MINUTES);
                    case HOURLY -> now.truncatedTo(ChronoUnit.HOURS);
                    case DAILY -> now.truncatedTo(ChronoUnit.DAYS);
                    case MONTHLY ->
                            now.atZone(ZoneOffset.UTC)
                                    .truncatedTo(ChronoUnit.DAYS)
                                    .toLocalDate()
                                    .withDayOfMonth(1)
                                    .atStartOfDay(ZoneOffset.UTC)
                                    .toInstant();
                };
        return truncated.toEpochMilli();
    }

    private QuotaSnapshot snapshotOf(QuotaKey key, Bucket bucket, Instant now) {
        QuotaPolicy policy = policies.get(key);
        long limit = policy == null ? 0L : policy.limit();
        long used = Math.max(0L, bucket.used.get());
        long remaining =
                limit <= 0 ? QuotaSnapshot.UNLIMITED_REMAINING : Math.max(0L, limit - used);
        Instant resetAt =
                Instant.ofEpochMilli(
                                boundaryOf(
                                        policy == null ? QuotaPolicy.Period.DAILY : policy.period(),
                                        now))
                        .plus(reservationTtl);
        return new QuotaSnapshot(
                key, used, limit, remaining, resetAt, bucket.activeReservations.size());
    }

    private QuotaSnapshot emptySnapshot(QuotaKey key) {
        return new QuotaSnapshot(
                key,
                0L,
                0L,
                QuotaSnapshot.UNLIMITED_REMAINING,
                clock.instant().plusSeconds(3600),
                0);
    }

    /** 单个配额键的状态。锁只保护本桶。 */
    private static final class Bucket {
        private final java.util.concurrent.locks.ReentrantLock lock =
                new java.util.concurrent.locks.ReentrantLock();
        private final AtomicLong used = new AtomicLong();
        private final Map<String, ActiveReservation> activeReservations =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong periodBoundary =
                new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
    }

    /** 在途预留的内部记录：预留了多少、什么时候预留的。 */
    private record ActiveReservation(long tokens, Instant createdAt) {}
}
