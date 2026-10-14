/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.quota;

import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("内存配额存储：预留-对账-释放")
class InMemoryQuotaStoreTest {

    private static final QuotaKey KEY =
            new QuotaKey("vip", "customer-service", "openai", "gpt-5-mini");

    @Test
    @DisplayName("★ 100 线程并发抢 1000 token 配额、每次预留 100 token → 恰好 10 个通过（不多不少）")
    void concurrentReservationIsExact() throws Exception {
        int threads = 100;
        long perRequest = 100L;
        long limit = 1_000L;

        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY, new QuotaPolicy(QuotaPolicy.Period.DAILY, limit, OverLimitBehavior.FAIL_SOFT));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(
                    () -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        Reservation reservation = store.reserve(KEY, perRequest);
                        if (reservation.granted()) {
                            granted.incrementAndGet();
                        } else {
                            denied.incrementAndGet();
                        }
                        return null;
                    });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(granted.get()).as("通过的请求数必须恰好等于配额/tokenPerRequest").isEqualTo(10);
        assertThat(denied.get()).isEqualTo(90);
        assertThat(store.snapshot(KEY).used()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("★ 预留 1000 实际用 300 → 剩余额度回到预留前的值 +700（多退）")
    void settleRefundsUnusedReservation() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 10_000L, OverLimitBehavior.FAIL_SOFT));

        long before = store.snapshot(KEY).remaining();
        Reservation reservation = store.reserve(KEY, 1_000L);
        assertThat(store.snapshot(KEY).remaining()).isEqualTo(before - 1_000L);

        Usage actual = Usage.of(300L, 0L);
        QuotaSnapshot snapshot = store.settle(reservation, actual);

        // 预留 1000 实际用 300 → 退掉 700，used 回到真实消耗
        assertThat(snapshot.used()).isEqualTo(300L);
        // remaining = limit - used = 10000 - 300，恰好是「按真实消耗计费」而非「按预留计费」
        assertThat(snapshot.remaining()).isEqualTo(10_000L - 300L);
        assertThat(snapshot.remaining()).isEqualTo(before - 300L);
        assertThat(snapshot.reservations()).isZero();
    }

    @Test
    @DisplayName("实际用量大于预留时补扣（少补），保证真实消耗不被漏记")
    void settleChargesOverage() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 10_000L, OverLimitBehavior.FAIL_SOFT));

        Reservation reservation = store.reserve(KEY, 500L);
        QuotaSnapshot snapshot = store.settle(reservation, Usage.of(1_500L, 0L));

        assertThat(snapshot.used()).isEqualTo(1_500L);
    }

    @Test
    @DisplayName("请求失败时释放预留，额度原样归还")
    void releaseRestoresQuota() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 1_000L, OverLimitBehavior.FAIL_SOFT));

        long before = store.snapshot(KEY).remaining();
        Reservation reservation = store.reserve(KEY, 1_000L);
        assertThat(store.snapshot(KEY).remaining()).isZero();

        store.release(reservation);
        assertThat(store.snapshot(KEY).remaining()).isEqualTo(before);
    }

    @Test
    @DisplayName("重复 release 同一笔预留是幂等的，不会把额度退成负数")
    void releaseIsIdempotent() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 10_000L, OverLimitBehavior.FAIL_SOFT));

        Reservation first = store.reserve(KEY, 1_000L);
        store.settle(first, Usage.of(1_000L, 0L));
        // 结算后再 release 不应重复退账
        store.release(first);
        store.release(first);

        assertThat(store.snapshot(KEY).used()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("超出配额返回 denied 且带可读原因（fail-soft 的前提）")
    void overLimitReturnsReadableReason() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY, new QuotaPolicy(QuotaPolicy.Period.DAILY, 500L, OverLimitBehavior.FAIL_SOFT));

        assertThat(store.reserve(KEY, 400L).granted()).isTrue();
        Reservation denied = store.reserve(KEY, 200L);

        assertThat(denied.granted()).isFalse();
        assertThat(denied.reason()).contains("配额已用尽").contains("500");
    }

    @Test
    @DisplayName("精确用满配额允许通过（边界：used + requested == limit）")
    void exactFitIsGranted() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY, new QuotaPolicy(QuotaPolicy.Period.DAILY, 100L, OverLimitBehavior.FAIL_SOFT));

        assertThat(store.reserve(KEY, 60L).granted()).isTrue();
        assertThat(store.reserve(KEY, 40L).granted()).isTrue();
        assertThat(store.reserve(KEY, 1L).granted()).isFalse();
    }

    @Test
    @DisplayName("不限额时任何请求都通过，remaining 为 Long.MAX_VALUE")
    void unlimitedAlwaysGrants() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(KEY, QuotaPolicy.unlimitedPolicy());

        assertThat(store.reserve(KEY, Long.MAX_VALUE / 2).granted()).isTrue();
        QuotaSnapshot snapshot = store.snapshot(KEY);
        assertThat(snapshot.unlimited()).isTrue();
        assertThat(snapshot.remaining()).isEqualTo(QuotaSnapshot.UNLIMITED_REMAINING);
        assertThat(snapshot.exceeded()).isFalse();
    }

    @Test
    @DisplayName("未配置策略的键按不限额处理")
    void unconfiguredKeyIsUnlimited() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        assertThat(store.reserve(KEY, 999_999L).granted()).isTrue();
        assertThat(store.snapshot(KEY).unlimited()).isTrue();
    }

    @Test
    @DisplayName("★ 过期预留被自动回收，防止额度被泄漏")
    void expiredReservationsArePurged() {
        // 注入时钟：reserve 之后再把时间往前推 11 分钟（TTL 为 10 分钟）
        MutableClock clock = new MutableClock(Instant.parse("2026-10-09T00:00:00Z"));
        InMemoryQuotaStore store = new InMemoryQuotaStore(clock, Reservation.DEFAULT_TTL);
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.MONTHLY, 10_000L, OverLimitBehavior.FAIL_SOFT));

        store.reserve(KEY, 2_000L);
        assertThat(store.snapshot(KEY).used()).isEqualTo(2_000L);

        clock.advance(java.time.Duration.ofMinutes(11));
        int purged = store.purgeExpired();

        assertThat(purged).isEqualTo(1);
        assertThat(store.snapshot(KEY).used()).isZero();
    }

    @Test
    @DisplayName("★ 零用量且无策略的空桶被回收，多租户长跑不再无界堆积（按需重建）")
    void emptyBucketsAreReclaimed() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();

        // 无策略键：预留后按 0 结算 → 桶内状态全零
        Reservation reservation = store.reserve(KEY, 500L);
        assertThat(reservation.granted()).isTrue();
        store.settle(reservation, Usage.of(0L, 0L));
        assertThat(store.keys()).contains(KEY);

        store.purgeExpired();

        assertThat(store.keys()).as("空桶应被回收").doesNotContain(KEY);
        // 回收后快照仍自洽：无限额、零用量
        QuotaSnapshot snapshot = store.snapshot(KEY);
        assertThat(snapshot.used()).isZero();
        assertThat(snapshot.unlimited()).isTrue();
        // 按需重建：下次预留照常工作
        assertThat(store.reserve(KEY, 100L).granted()).isTrue();
        assertThat(store.snapshot(KEY).used()).isEqualTo(100L);
    }

    @Test
    @DisplayName("有策略的键即使零用量也不回收（运维显式配置的状态要保留）")
    void policyBackedBucketsAreRetained() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 1_000L, OverLimitBehavior.FAIL_SOFT));

        Reservation reservation = store.reserve(KEY, 500L);
        store.settle(reservation, Usage.of(0L, 0L));

        store.purgeExpired();

        assertThat(store.keys()).contains(KEY);
        assertThat(store.policyOf(KEY)).isNotNull();
    }

    @Test
    @DisplayName("周期内有用量的桶不被回收")
    void bucketsWithUsageAreRetained() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();

        store.reserve(KEY, 500L); // 无策略但用量 500，未结算

        store.purgeExpired();

        assertThat(store.keys()).contains(KEY);
        assertThat(store.snapshot(KEY).used()).isEqualTo(500L);
    }

    @Test
    @DisplayName("跨周期后用量自动清零（周期滚动）")
    void usageResetsOnPeriodRollover() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-09T23:59:00Z"));
        InMemoryQuotaStore store = new InMemoryQuotaStore(clock, Reservation.DEFAULT_TTL);
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 1_000L, OverLimitBehavior.FAIL_SOFT));

        store.reserve(KEY, 900L);
        assertThat(store.snapshot(KEY).used()).isEqualTo(900L);

        clock.advance(java.time.Duration.ofMinutes(2)); // 跨过 UTC 零点
        assertThat(store.snapshot(KEY).used()).isZero();
        assertThat(store.reserve(KEY, 900L).granted()).isTrue();
    }

    @Test
    @DisplayName("settle 传 null usage 时按 0 结算，不抛异常")
    void settleWithNullUsage() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 1_000L, OverLimitBehavior.FAIL_SOFT));

        Reservation reservation = store.reserve(KEY, 500L);
        QuotaSnapshot snapshot = store.settle(reservation, null);

        assertThat(snapshot.used()).isZero();
    }

    @Test
    @DisplayName("Estimated 类型的 Usage 也参与结算（估算值同样要计费）")
    void estimatedUsageStillCharged() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 10_000L, OverLimitBehavior.FAIL_SOFT));

        Reservation reservation = store.reserve(KEY, 100L);
        Usage estimated = new Usage(120L, 80L, 0L, 0L, 0L, UsageSource.ESTIMATED, true, false);
        assertThat(store.settle(reservation, estimated).used()).isEqualTo(200L);
    }

    @Test
    @DisplayName("reset 清空全部状态")
    void resetClearsState() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        store.putPolicy(
                KEY,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 1_000L, OverLimitBehavior.FAIL_SOFT));
        store.reserve(KEY, 500L);

        store.reset();

        assertThat(store.snapshot(KEY).used()).isZero();
        assertThat(store.policyOf(KEY)).isNull();
    }

    @Test
    @DisplayName("配额键四元组隔离：不同租户/业务线/模型互不影响")
    void quotaKeysAreIsolated() {
        InMemoryQuotaStore store = new InMemoryQuotaStore();
        QuotaKey a = new QuotaKey("vip", null, "openai", "gpt-5-mini");
        QuotaKey b = new QuotaKey("free", null, "openai", "gpt-5-mini");
        QuotaKey c = new QuotaKey("vip", null, "deepseek", "deepseek-chat");
        store.putPolicy(
                a, new QuotaPolicy(QuotaPolicy.Period.DAILY, 100L, OverLimitBehavior.FAIL_SOFT));
        store.putPolicy(
                b, new QuotaPolicy(QuotaPolicy.Period.DAILY, 100L, OverLimitBehavior.FAIL_SOFT));
        store.putPolicy(
                c, new QuotaPolicy(QuotaPolicy.Period.DAILY, 100L, OverLimitBehavior.FAIL_SOFT));

        store.reserve(a, 100L);

        assertThat(store.reserve(a, 1L).granted()).isFalse();
        assertThat(store.reserve(b, 100L).granted()).isTrue();
        assertThat(store.reserve(c, 100L).granted()).isTrue();
    }

    /** 可推进的测试时钟。架构约定：超时/TTL 逻辑必须支持注入 Clock，测试禁用 Thread.sleep。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
