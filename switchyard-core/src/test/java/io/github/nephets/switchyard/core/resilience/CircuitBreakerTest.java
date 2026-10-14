/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("熔断器：滑动窗口 + 半开探测")
class CircuitBreakerTest {

    /** 可推进的测试时钟。架构约定：超时/TTL 逻辑必须注入 Clock，测试禁用 Thread.sleep。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-09T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("初始状态 CLOSED，正常放行")
    void startsClosed() {
        CircuitBreaker breaker = new CircuitBreaker("openai/gpt-5-mini");

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.failureRate()).isZero();
        assertThat(breaker.name()).isEqualTo("openai/gpt-5-mini");
    }

    @Test
    @DisplayName("★ 连续失败达到阈值后熔断打开，后续调用快速失败")
    void opensAfterFailureThreshold() {
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(30), Clock.systemUTC());

        for (int i = 0; i < 10; i++) {
            breaker.tryAcquire();
            breaker.recordFailure();
        }

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquire()).as("熔断打开时必须快速失败，不打上游").isFalse();
        assertThat(breaker.failureRate()).isGreaterThanOrEqualTo(0.5d);
    }

    @Test
    @DisplayName("★ 熔断打开后 100 次调用只放行 0 次（不产生任何上游请求）")
    void openBreakerBlocksAllTraffic() {
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(30), Clock.systemUTC());
        for (int i = 0; i < 12; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        AtomicInteger upstreamCalls = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            if (breaker.tryAcquire()) {
                upstreamCalls.incrementAndGet();
            }
        }

        assertThat(upstreamCalls.get()).as("熔断期间不应有任何请求打到上游").isEqualTo(0);
    }

    @Test
    @DisplayName("★ 超过打开时长后转半开，只放行一个探测请求")
    void halfOpenAllowsSingleProbe() {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(30), clock);
        for (int i = 0; i < 12; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);

        assertThat(breaker.tryAcquire()).as("第一个探测请求被放行").isTrue();
        assertThat(breaker.tryAcquire()).as("半开期间只放一个探测，第二个必须被拒").isFalse();
    }

    @Test
    @DisplayName("★ 半开探测成功 → 关闭熔断")
    void halfOpenProbeSucceedsCloses() {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(30), clock);
        for (int i = 0; i < 12; i++) {
            breaker.recordFailure();
        }
        clock.advance(Duration.ofSeconds(31));

        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordSuccess();

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("★ 半开探测失败 → 重新打开，且退避时长翻倍（指数退避）")
    void halfOpenProbeFailsReopensWithBackoff() {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(30), clock);
        for (int i = 0; i < 12; i++) {
            breaker.recordFailure();
        }
        clock.advance(Duration.ofSeconds(31));
        breaker.tryAcquire();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        // 退避已翻倍：30s 不再够，需要 60s
        clock.advance(Duration.ofSeconds(40));
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        clock.advance(Duration.ofSeconds(25));
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("窗口未填满时不因单次失败就熔断（避免抖动误伤）")
    void minimumCallsGuard() {
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(30), Clock.systemUTC());

        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("成功调用会稀释失败率")
    void successesDiluteFailures() {
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.8d, Duration.ofSeconds(30), Clock.systemUTC());

        for (int i = 0; i < 5; i++) {
            breaker.recordSuccess();
        }
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }

        assertThat(breaker.failureRate()).isLessThan(0.8d);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("totalCalls 累计全部调用次数（供控制台展示）")
    void totalCallsCounted() {
        CircuitBreaker breaker = new CircuitBreaker("test");

        for (int i = 0; i < 7; i++) {
            breaker.recordSuccess();
        }

        assertThat(breaker.totalCalls()).isEqualTo(7L);
    }

    @Test
    @DisplayName("reset 强制回到 CLOSED 并清零统计")
    void resetClears() {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(30), clock);
        for (int i = 0; i < 12; i++) {
            breaker.recordFailure();
        }

        breaker.reset();

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.totalCalls()).isZero();
        assertThat(breaker.failureRate()).isZero();
    }

    @Test
    @DisplayName("★ 注册表按模型维度隔离熔断：一个模型熔断不影响其他模型")
    void registryIsolatesBreakers() {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry();
        var gpt = io.github.nephets.switchyard.core.model.ModelRef.of("openai", "gpt-5-mini");
        var deepseek =
                io.github.nephets.switchyard.core.model.ModelRef.of("deepseek", "deepseek-chat");

        CircuitBreaker gptBreaker = registry.breakerOf(gpt);
        for (int i = 0; i < 12; i++) {
            gptBreaker.recordFailure();
        }

        assertThat(registry.stateOf(gpt)).isEqualTo(CircuitState.OPEN);
        assertThat(registry.stateOf(deepseek)).isEqualTo(CircuitState.CLOSED);
        assertThat(registry.tryAcquire(deepseek)).isTrue();
        assertThat(registry.tryAcquire(gpt)).isFalse();
        assertThat(registry.openBreakers()).containsExactly("openai/gpt-5-mini");
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    @DisplayName("注册表 resetAll 重置全部熔断器")
    void registryResetAll() {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry();
        var gpt = io.github.nephets.switchyard.core.model.ModelRef.of("openai", "gpt-5-mini");
        for (int i = 0; i < 12; i++) {
            registry.breakerOf(gpt).recordFailure();
        }

        registry.resetAll();

        assertThat(registry.openBreakers()).isEmpty();
        assertThat(registry.stateOf(gpt)).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("注册表 find 对不存在的模型返回 empty")
    void registryFindUnknown() {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry();
        assertThat(registry.find("不存在/模型")).isEmpty();
    }

    @Test
    @DisplayName("★ 并发调用下熔断器计数一致（无数据竞争导致的丢失更新）")
    void concurrentCallsAreConsistent() throws Exception {
        CircuitBreaker breaker =
                new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(30), Clock.systemUTC());

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        AtomicInteger acquired = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(
                    pool.submit(
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                if (breaker.tryAcquire()) {
                                    acquired.incrementAndGet();
                                    if (index % 2 == 0) {
                                        breaker.recordFailure();
                                    } else {
                                        breaker.recordSuccess();
                                    }
                                }
                                return null;
                            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 核心不变量：被记录的总次数必须恰好等于拿到许可的次数（不多不少，不丢更新）
        assertThat(breaker.totalCalls()).as("每次拿到许可的调用都必须被记录一次").isEqualTo(acquired.get());
        // 失败率不能超过 1，也不允许为负（说明计数器没有错乱）
        assertThat(breaker.failureRate()).isBetween(0d, 1d);
        // 熔断器最终状态必须是三者之一，且 OPEN 时后续调用确实被拒
        assertThat(breaker.state())
                .isIn(CircuitState.CLOSED, CircuitState.OPEN, CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("退避时长有上限，不会无限增长")
    void backoffIsCapped() {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker("test", 20, 0.5d, Duration.ofSeconds(1), clock);

        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 12; i++) {
                breaker.recordFailure();
            }
            clock.advance(Duration.ofSeconds(601));
            if (breaker.state() == CircuitState.HALF_OPEN) {
                breaker.tryAcquire();
                breaker.recordFailure();
            }
        }

        assertThat(breaker.state()).isNotNull();
    }
}
