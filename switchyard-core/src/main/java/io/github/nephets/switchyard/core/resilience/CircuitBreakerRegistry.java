/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import io.github.nephets.switchyard.core.model.ModelRef;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表：按模型引用管理熔断器实例。
 *
 * <p>熔断粒度是<b>单个模型</b>而不是整个供应商。理由：一个供应商的 gpt-5-mini 被限流， 不代表它的另一个模型也不可用；按供应商粒度熔断会把「部分降级」变成「整体不可用」。
 */
public class CircuitBreakerRegistry {

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final CircuitBreakerFactory factory;

    /** 用默认参数（窗口 20、失败率 50%、打开 30s）构造。 */
    public CircuitBreakerRegistry() {
        this(CircuitBreaker::new);
    }

    /**
     * 用自定义工厂构造（starter 层用带配置值的工厂）。
     *
     * @param factory 熔断器工厂
     */
    public CircuitBreakerRegistry(CircuitBreakerFactory factory) {
        this.factory = factory == null ? CircuitBreaker::new : factory;
    }

    /**
     * 取某个模型的熔断器，不存在则创建。
     *
     * @param ref 模型引用
     * @return 熔断器，永不返回 null
     */
    public CircuitBreaker breakerOf(ModelRef ref) {
        String key = key(ref);
        return breakers.computeIfAbsent(key, k -> factory.create(k));
    }

    /**
     * 取某个模型的熔断器，不存在则创建。
     *
     * @param modelText {@code providerId/modelId} 字符串
     * @return 熔断器
     */
    public CircuitBreaker breakerOf(String modelText) {
        String key = modelText == null ? "unknown" : modelText;
        return breakers.computeIfAbsent(key, k -> factory.create(k));
    }

    /**
     * 尝试获取执行许可。
     *
     * @param ref 模型引用
     * @return 允许返回 true
     */
    public boolean tryAcquire(ModelRef ref) {
        return breakerOf(ref).tryAcquire();
    }

    /**
     * 查询熔断状态。
     *
     * @param ref 模型引用
     * @return 当前状态
     */
    public CircuitState stateOf(ModelRef ref) {
        return breakerOf(ref).state();
    }

    /** 全部熔断器（供控制台与 {@code doctor} 命令展示）。 */
    public Collection<CircuitBreaker> all() {
        return List.copyOf(breakers.values());
    }

    /** 已熔断（OPEN）的模型名。 */
    public List<String> openBreakers() {
        return breakers.values().stream()
                .filter(b -> b.state() == CircuitState.OPEN)
                .map(CircuitBreaker::name)
                .sorted()
                .toList();
    }

    /**
     * 查找已存在的熔断器。
     *
     * @param modelText 模型标识
     * @return 熔断器；不存在返回 {@link Optional#empty()}
     */
    public Optional<CircuitBreaker> find(String modelText) {
        return Optional.ofNullable(breakers.get(modelText));
    }

    /** 重置全部熔断器。 */
    public void resetAll() {
        breakers.values().forEach(CircuitBreaker::reset);
    }

    private static String key(ModelRef ref) {
        return ref == null ? "unknown" : ref.text();
    }

    /** 熔断器工厂。 */
    @FunctionalInterface
    public interface CircuitBreakerFactory {
        /**
         * 创建一个熔断器。
         *
         * @param name 熔断器名
         * @return 熔断器实例
         */
        CircuitBreaker create(String name);
    }
}
