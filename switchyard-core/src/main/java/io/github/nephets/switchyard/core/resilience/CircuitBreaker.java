/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 滑动窗口计数熔断器（不依赖任何框架，纯 JDK 实现）。
 *
 * <p><b>状态机</b>：
 *
 * <pre>
 *   CLOSED --失败数达到 failureThreshold--> OPEN
 *   OPEN --经过 openDuration--> HALF_OPEN
 *   HALF_OPEN --探测成功--> CLOSED
 *   HALF_OPEN --探测失败--> OPEN（并重新计时，退避加倍）
 * </pre>
 *
 * <p><b>为什么用「滑动窗口 + 计数」而不是 Resilience4j 的默认实现</b>： 上游故障往往是<b>突发的</b>（30 秒内 429
 * 爆发然后恢复）。固定窗口的计数法在窗口边界会出现 「明明已经恢复了，窗口还没翻页所以继续拒绝」或「窗口刚翻页，累积的失败被一次清零而漏掉突发」两类问题。 滑动窗口只统计「最近 N
 * 次调用」的结果，语义上更贴近「上游现在健康吗」这个问题。
 *
 * <p><b>半开只放一个探测</b>：用 {@link AtomicInteger} 做 CAS 保证同一时刻只有一个探测请求进入。 若半开时放 10 个请求，等于用 10
 * 次真实调用去试一个已经明确不健康的上游。
 */
public class CircuitBreaker {

    /** 默认滑动窗口大小。 */
    public static final int DEFAULT_WINDOW_SIZE = 20;

    /** 默认失败阈值：窗口内失败达到该比例即打开。 */
    public static final double DEFAULT_FAILURE_THRESHOLD = 0.5d;

    /** 默认打开时长。 */
    public static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(30);

    private final String name;
    private final int windowSize;
    private final int minimumCalls;
    private final double failureThreshold;
    private final Clock clock;

    private final AtomicInteger windowIndex = new AtomicInteger();
    private final boolean[] failures;
    private final AtomicInteger filled = new AtomicInteger();
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicInteger halfOpenInFlight = new AtomicInteger();
    private volatile CircuitState state = CircuitState.CLOSED;
    private volatile long openedAtMillis;
    private volatile long currentOpenDurationMillis;
    private final java.util.concurrent.atomic.AtomicLong totalCalls = new AtomicLong();

    /** 用默认参数构造。 */
    public CircuitBreaker(String name) {
        this(
                name,
                DEFAULT_WINDOW_SIZE,
                DEFAULT_FAILURE_THRESHOLD,
                DEFAULT_OPEN_DURATION,
                Clock.systemUTC());
    }

    /**
     * 构造。
     *
     * @param name 熔断器名（通常是 {@code providerId/modelId}）
     * @param windowSize 滑动窗口大小
     * @param failureThreshold 失败率阈值，{@code 0~1}
     * @param openDuration 打开时长
     * @param clock 时钟（测试可注入可复现时钟）
     */
    public CircuitBreaker(
            String name,
            int windowSize,
            double failureThreshold,
            Duration openDuration,
            Clock clock) {
        this.name = name == null ? "unknown" : name;
        this.windowSize = Math.max(4, windowSize);
        this.minimumCalls = Math.max(1, (int) Math.ceil(this.windowSize * 0.25));
        this.failureThreshold =
                failureThreshold <= 0 || failureThreshold > 1
                        ? DEFAULT_FAILURE_THRESHOLD
                        : failureThreshold;
        this.currentOpenDurationMillis =
                (openDuration == null ? DEFAULT_OPEN_DURATION : openDuration).toMillis();
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.failures = new boolean[this.windowSize];
    }

    /**
     * 尝试获取一个执行许可。
     *
     * @return 允许调用上游返回 true；熔断打开中返回 false
     */
    public boolean tryAcquire() {
        transitionIfNeeded();
        if (state == CircuitState.OPEN) {
            return false;
        }
        if (state == CircuitState.HALF_OPEN) {
            // 半开期只放一个探测请求：CAS 抢占，抢不到就快速失败
            return halfOpenInFlight.compareAndSet(0, 1);
        }
        return true;
    }

    /** 记录一次成功调用。 */
    public void recordSuccess() {
        if (state == CircuitState.HALF_OPEN && halfOpenInFlight.compareAndSet(1, 0)) {
            close();
            return;
        }
        record(true);
    }

    /** 记录一次失败调用。 */
    public void recordFailure() {
        if (state == CircuitState.HALF_OPEN && halfOpenInFlight.compareAndSet(1, 0)) {
            // 探测失败 → 重新打开，并让退避时长翻倍（指数退避，避免反复冲击一个彻底挂掉的上游）
            currentOpenDurationMillis = Math.min(currentOpenDurationMillis * 2, 600_000L);
            open();
            return;
        }
        record(false);
    }

    /** 当前状态。 */
    public CircuitState state() {
        transitionIfNeeded();
        return state;
    }

    /** 熔断器名。 */
    public String name() {
        return name;
    }

    /** 当前窗口的失败率。窗口未填满时返回 0。 */
    public double failureRate() {
        int total = filled.get();
        if (total == 0) {
            return 0d;
        }
        return (double) failureCount.get() / (double) total;
    }

    /** 累计调用次数。 */
    public long totalCalls() {
        return totalCalls.get();
    }

    /** 强制回到 CLOSED（CLI 的 {@code doctor --reset} 与测试用）。 */
    public void reset() {
        close();
        totalCalls.set(0);
        filled.set(0);
        failureCount.set(0);
        successCount.set(0);
        windowIndex.set(0);
        java.util.Arrays.fill(failures, false);
    }

    /** OPEN 超过指定时长后转 HALF_OPEN。 */
    private void transitionIfNeeded() {
        if (state != CircuitState.OPEN) {
            return;
        }
        long elapsed = clock.millis() - openedAtMillis;
        if (elapsed >= currentOpenDurationMillis) {
            synchronized (this) {
                if (state == CircuitState.OPEN
                        && clock.millis() - openedAtMillis >= currentOpenDurationMillis) {
                    state = CircuitState.HALF_OPEN;
                    halfOpenInFlight.set(0);
                }
            }
        }
    }

    private void record(boolean success) {
        int index = windowIndex.getAndUpdate(v -> (v + 1) % windowSize);
        synchronized (failures) {
            boolean wasFilled = index < filled.get();
            failures[index] = !success;
            if (!wasFilled) {
                filled.incrementAndGet();
            }
            if (success) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }
        }
        totalCalls.incrementAndGet();
        if (state == CircuitState.CLOSED
                && filled.get() >= minimumCalls
                && failureRate() >= failureThreshold) {
            open();
        }
    }

    private synchronized void open() {
        state = CircuitState.OPEN;
        openedAtMillis = clock.millis();
    }

    private synchronized void close() {
        state = CircuitState.CLOSED;
        currentOpenDurationMillis =
                Math.max(DEFAULT_OPEN_DURATION.toMillis(), currentOpenDurationMillis / 2);
        filled.set(0);
        failureCount.set(0);
        successCount.set(0);
        windowIndex.set(0);
        java.util.Arrays.fill(failures, false);
    }
}
