/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.pricing.PricingTable;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 网关健康指示器。
 *
 * <p><b>刻意不让「有模型熔断」直接报 DOWN</b>：熔断是网关在替上游兜底， 是网关<b>正常工作</b>的表现，不是网关自身故障。把它报成 DOWN 会让业务方的 K8s
 * 把网关实例摘掉， 结果是「上游限流 → 网关被摘 → 全站不可用」—— 比不熔断还糟。
 *
 * <p>真正报 DOWN 的只有两类：价格表为空（无法算成本）、所有路由都没有可用模型。
 */
public class SwitchyardHealthIndicator implements HealthIndicator {

    private final SwitchyardRouter router;
    private final PricingTable pricingTable;

    /**
     * 构造。
     *
     * @param router 门面
     * @param pricingTable 价格表
     */
    public SwitchyardHealthIndicator(SwitchyardRouter router, PricingTable pricingTable) {
        this.router = router;
        this.pricingTable = pricingTable;
    }

    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();
        long available = router.health().values().stream().filter(h -> h.available()).count();
        long total = router.health().size();
        long open =
                router.health().values().stream()
                        .filter(h -> "OPEN".equals(h.circuitState()))
                        .count();

        builder.withDetail("routes", router.routeNames())
                .withDetail("modelsTotal", total)
                .withDetail("modelsAvailable", available)
                .withDetail("modelsCircuitOpen", open)
                .withDetail("priceEntries", pricingTable.size());

        if (total == 0) {
            return builder.down().withDetail("reason", "未配置任何路由").build();
        }
        if (pricingTable.isEmpty()) {
            return builder.down().withDetail("reason", "价格表为空，无法计算成本").build();
        }
        if (available == 0) {
            return builder.down().withDetail("reason", "所有模型均处于熔断状态").build();
        }
        // 有模型熔断但仍有可用模型 → UP，只是 degraded 状态
        return (open > 0 ? builder.up().withDetail("degraded", true) : builder.up()).build();
    }
}
