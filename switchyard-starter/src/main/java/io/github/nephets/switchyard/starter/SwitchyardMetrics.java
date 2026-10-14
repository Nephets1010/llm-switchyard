/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.usage.Usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内指标累加器：给控制台 {@code /overview} 提供真实的成本与 token 数字。
 *
 * <p><b>首版的诚实定位</b>：这是进程内累计值，<b>重启归零</b>，不是持久化存储。 控制台会显式标注这一点，而不是让人误以为这是账目数据。 首版做它的理由很实际：README
 * 承诺「30 秒看到控制台」， 如果控制台全是 0，读者就无法判断「是不是真的通了」。
 *
 * <p>要长期留存请接 P1 的持久化路径（或把数据导到自己的 BI）， 本类的接口形状（{@code add} + {@code snapshot}）是为那时准备的。
 */
public class SwitchyardMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AtomicReference<BigDecimal> totalCost = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong degraded = new AtomicLong();
    private final AtomicLong estimatedUsage = new AtomicLong();
    private final java.time.Clock clock;

    /** 使用系统时钟。 */
    public SwitchyardMetrics() {
        this(java.time.Clock.systemUTC());
    }

    /**
     * 构造。
     *
     * @param clock 时钟，便于测试注入
     */
    public SwitchyardMetrics(java.time.Clock clock) {
        this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
    }

    /**
     * 记录一次成功调用。
     *
     * @param usage 归一化后的用量
     * @param cost 成本
     * @param degraded 是否经过降级
     */
    public void recordSuccess(Usage usage, BigDecimal cost, boolean isDegraded) {
        calls.incrementAndGet();
        if (isDegraded) {
            degraded.incrementAndGet();
        }
        if (usage != null) {
            increment("tokens.input", usage.inputTokens());
            increment("tokens.output", usage.outputTokens());
            increment("tokens.cached", usage.cachedInputTokens());
            increment("tokens.reasoning", usage.reasoningTokens());
            increment("tokens.total", usage.totalTokens());
            // ★ 流式计量为 0 是必须被监控的信号：修复前所有流式请求都是 0
            if (usage.streamUsageReported()) {
                increment("usage.streamReported", 1L);
            } else {
                increment("usage.streamNotReported", 1L);
            }
            if (usage.estimatedOnly()) {
                estimatedUsage.incrementAndGet();
            }
        }
        if (cost != null) {
            totalCost.updateAndGet(current -> current.add(cost));
        }
    }

    /**
     * 记录一次失败调用。
     *
     * @param errorCode 错误码
     */
    public void recordFailure(String errorCode) {
        failures.incrementAndGet();
        if (errorCode != null) {
            increment("errors." + errorCode, 1L);
        }
    }

    /**
     * 记录一次配额拒绝。
     *
     * @param quotaKey 配额键
     */
    public void recordQuotaRejection(String quotaKey) {
        increment("quota.rejected", 1L);
        if (quotaKey != null) {
            increment("quota.rejected." + quotaKey, 1L);
        }
    }

    private void increment(String key, long delta) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
    }

    /**
     * 指标快照。
     *
     * @return 快照
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("capturedAt", Instant.now(clock).toString());
        result.put("today", LocalDate.ofInstant(Instant.now(clock), ZoneId.of("UTC")).toString());
        result.put("totalCalls", calls.get());
        result.put("totalFailures", failures.get());
        result.put("degradedCalls", degraded.get());
        result.put("estimatedUsageCalls", estimatedUsage.get());
        result.put(
                "totalCostCny",
                totalCost.get().setScale(8, java.math.RoundingMode.HALF_UP).toPlainString());
        Map<String, Long> countersSnapshot = new java.util.TreeMap<>();
        counters.forEach((k, v) -> countersSnapshot.put(k, v.get()));
        result.put("counters", countersSnapshot);
        result.put("scope", "进程内累计，重启归零；非账目数据。长期留存请接 P1 持久化或自建 BI");
        return result;
    }

    /** 清空（测试与 CLI 用）。 */
    public void reset() {
        counters.clear();
        totalCost.set(BigDecimal.ZERO);
        calls.set(0L);
        failures.set(0L);
        degraded.set(0L);
        estimatedUsage.set(0L);
    }

    /** 累计调用数。 */
    public long totalCalls() {
        return calls.get();
    }

    /** 累计成本。 */
    public BigDecimal totalCost() {
        return totalCost.get();
    }
}
