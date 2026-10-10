/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 线程安全的场景注册表与故障注入引擎。
 *
 * <p><b>为什么需要它</b>：本项目最需要被验证的恰恰是<b>故障路径</b> —— 429 之后重试成功、400 之后不重试、熔断打开后不打上游、流式不返回 usage。 这些在真实
 * API 上要么复现不了（要碰运气），要么代价太高（真花钱）。
 *
 * <p>{@code failTimes} 机制是这里的关键：它能让「前 2 次 429、第 3 次成功」这种 <b>时序敏感</b>的场景变得可确定复现，从而让重试逻辑可以被精确断言。
 */
@Component
public class ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEngine.class);

    private final AtomicReference<Scenario> current = new AtomicReference<>(Scenario.ok());
    private final Map<String, Scenario> registered = new ConcurrentHashMap<>();
    private final AtomicInteger remainingFailures = new AtomicInteger(0);
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();

    public ScenarioEngine() {
        for (Scenario scenario : Scenario.builtin()) {
            registered.put(scenario.name(), scenario);
        }
    }

    /** 当前场景。 */
    public Scenario current() {
        return current.get();
    }

    /** 切换到指定场景。 */
    public Scenario switchTo(String name) {
        Scenario scenario = registered.get(name);
        if (scenario == null) {
            throw new IllegalArgumentException("未知的场景：" + name + "，可用场景：" + registered.keySet());
        }
        current.set(scenario);
        remainingFailures.set(Math.max(0, scenario.failTimes()));
        log.info("Mock LLM 场景切换为：{}（{}）", name, scenario.description());
        return scenario;
    }

    /** 重置为正常场景并清空统计。 */
    public void reset() {
        current.set(Scenario.ok());
        remainingFailures.set(0);
        requestCount.set(0);
        errorCount.set(0);
        log.info("Mock LLM 已重置为正常场景");
    }

    /** 已注册场景。 */
    public Map<String, Scenario> registered() {
        return Map.copyOf(registered);
    }

    /** 累计请求数。 */
    public long requestCount() {
        return requestCount.get();
    }

    /** 累计错误数。 */
    public long errorCount() {
        return errorCount.get();
    }

    /**
     * 处理一次请求，返回本次应生效的场景。
     *
     * <p>对 {@code failTimes > 0} 的场景，只在剩余次数内注入故障，耗尽后自动恢复正常 —— 这让「重试 N 次后成功」可以被确定地断言。
     *
     * @param path 请求路径
     * @return 本次生效的场景
     */
    public Scenario next(String path) {
        requestCount.incrementAndGet();
        Scenario scenario = current.get();

        if (scenario.failTimes() <= 0) {
            if (scenario.httpStatus() >= 400) {
                errorCount.incrementAndGet();
            }
            return scenario;
        }

        if (remainingFailures.getAndDecrement() > 0) {
            errorCount.incrementAndGet();
            return scenario;
        }
        // 故障次数已耗尽 → 本次恢复正常（但不改动全局场景，便于断言切换时机）
        return Scenario.ok();
    }

    /** 供 /__admin/health 使用的健康快照。 */
    public java.util.Map<String, Object> health() {
        return java.util.Map.of(
                "status", "UP",
                "scenario", current.get().name(),
                "scenarios", registered.keySet(),
                "requestCount", requestCount.get(),
                "errorCount", errorCount.get());
    }
}
