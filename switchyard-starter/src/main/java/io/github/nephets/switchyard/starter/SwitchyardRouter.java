/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.pricing.PriceEntry;
import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.provider.ProviderCatalog;
import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaSnapshot;
import io.github.nephets.switchyard.core.resilience.CircuitBreaker;
import io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry;
import io.github.nephets.switchyard.core.resilience.CircuitState;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 门面：业务侧唯一需要接触的类型。
 *
 * <p><b>用法（这就是 README 的「30 秒上手」）</b>：
 *
 * <pre>{@code
 * @Bean
 * ChatClient chatClient(SwitchyardRouter router) {
 *   return ChatClient.builder(router.route("default")).build();
 * }
 * }</pre>
 *
 * <p>路由结果按名字缓存：一个路由只会创建一个装饰器实例， 否则每次注入都会重建熔断器状态与 provider 连接，指标与熔断都会失真。
 */
@Component
public class SwitchyardRouter {

    private final SwitchyardProperties properties;
    private final Map<String, RoutedChatModel> cache = new ConcurrentHashMap<>();
    private final RoutedChatModelFactory factory;
    private final QuotaSnapshotProvider quotaSnapshots;
    private final CircuitBreakerRegistry breakers;
    private final PricingTable pricingTable;

    /**
     * 构造。
     *
     * @param properties 配置
     * @param factory 装饰器工厂
     * @param quotaSnapshots 配额快照读取器
     * @param breakers 熔断器注册表
     * @param pricingTable 价格表
     */
    public SwitchyardRouter(
            SwitchyardProperties properties,
            RoutedChatModelFactory factory,
            QuotaSnapshotProvider quotaSnapshots,
            CircuitBreakerRegistry breakers,
            PricingTable pricingTable) {
        this.properties = properties;
        this.factory = factory;
        this.quotaSnapshots = quotaSnapshots;
        this.breakers = breakers;
        this.pricingTable = pricingTable;
    }

    /**
     * 取路由。
     *
     * @param routeName 路由名；为空时用默认路由
     * @return 装饰后的 {@link ChatModel}，可直接喂给 {@code ChatClient.builder(...)}
     */
    public ChatModel route(String routeName) {
        String name =
                routeName == null || routeName.isBlank() ? properties.getDefaultRoute() : routeName;
        return cache.computeIfAbsent(name, factory::create);
    }

    /** 取默认路由。 */
    public ChatModel route() {
        return route(properties.getDefaultRoute());
    }

    /**
     * 取路由的内部装饰器（需要读取计量/配额上下文时用）。
     *
     * @param routeName 路由名
     * @return 装饰器
     */
    public RoutedChatModel routedModel(String routeName) {
        return (RoutedChatModel) route(routeName);
    }

    /**
     * 查配额快照。
     *
     * @param key 配额键
     * @return 配额快照
     */
    public QuotaSnapshot quotaOf(QuotaKey key) {
        return quotaSnapshots.snapshotOf(key);
    }

    /**
     * 模型健康视图。
     *
     * @return 模型标识 → 健康状态
     */
    public Map<String, ModelHealth> health() {
        Map<String, ModelHealth> result = new LinkedHashMap<>();
        for (SwitchyardProperties.RouteConfig route : properties.getRoutes().values()) {
            if (route.getPrimary() == null) {
                continue;
            }
            ModelRef ref = ModelRef.parse(route.getPrimary());
            CircuitBreaker breaker = breakers.breakerOf(ref);
            CircuitState state = breaker.state();
            result.put(
                    ref.text(),
                    new ModelHealth(
                            ref.text(),
                            state.name(),
                            state != CircuitState.OPEN,
                            breaker.totalCalls(),
                            breaker.failureRate(),
                            pricingTable
                                    .lookup(ref.providerId(), ref.modelId())
                                    .map(PriceEntry::key)
                                    .orElse("(无价格条目)")));
        }
        return result;
    }

    /** 已装配的路由名。 */
    public List<String> routeNames() {
        return new ArrayList<>(properties.getRoutes().keySet());
    }

    /** 配置属性（控制台与 CLI 读取用）。 */
    public SwitchyardProperties properties() {
        return properties;
    }

    /** 内置供应商目录（控制台展示能力矩阵用）。 */
    public ProviderCatalog catalog() {
        return ProviderCatalog.builtin();
    }

    /** 模型健康视图项。 */
    public record ModelHealth(
            String model,
            String circuitState,
            boolean available,
            long totalCalls,
            double failureRate,
            String priceEntryKey) {}
}
