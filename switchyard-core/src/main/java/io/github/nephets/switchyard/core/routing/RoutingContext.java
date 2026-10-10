/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.routing;

import io.github.nephets.switchyard.core.model.ModelRef;

import java.util.Set;

/**
 * 路由上下文：做一次决策所需的全部输入。
 *
 * @param routeName 路由名（对应配置里的 route）
 * @param tenantId 租户
 * @param bizLine 业务线
 * @param feature 业务功能
 * @param requiredCapabilities 必需的供应商能力（路由约束）
 * @param preferredProviders 偏好供应商，按优先级排列；为空表示不偏好
 * @param budgetTokensPerRequest 单请求可接受的 token 预算；{@code <= 0} 表示不限
 */
public record RoutingContext(
        String routeName,
        String tenantId,
        String bizLine,
        String feature,
        Set<io.github.nephets.switchyard.core.provider.Capability> requiredCapabilities,
        java.util.List<String> preferredProviders,
        long budgetTokensPerRequest) {

    /** 紧凑构造器：填充默认值。 */
    public RoutingContext {
        routeName = routeName == null || routeName.isBlank() ? "default" : routeName.trim();
        // ⚠️ 不能用 EnumSet.copyOf(Collection)：它对空集合会抛 IllegalArgumentException
        // （copyOf 要求非空，因为空集合无法推断元素类型）。这里显式区分空/非空两种情况。
        requiredCapabilities =
                requiredCapabilities == null || requiredCapabilities.isEmpty()
                        ? java.util.Collections.unmodifiableSet(
                                java.util.EnumSet.noneOf(
                                        io.github.nephets.switchyard.core.provider.Capability
                                                .class))
                        : java.util.Collections.unmodifiableSet(
                                java.util.EnumSet.copyOf(requiredCapabilities));
        preferredProviders =
                preferredProviders == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(preferredProviders);
    }

    /** 最简上下文（无约束）。 */
    public static RoutingContext simple(String routeName) {
        return new RoutingContext(
                routeName, "_default", null, null, Set.of(), java.util.List.of(), 0L);
    }

    /**
     * 返回一个换了租户/业务线的副本。
     *
     * @param newTenantId 新租户
     * @param newBizLine 新业务线
     * @return 新上下文
     */
    public RoutingContext withTenant(String newTenantId, String newBizLine) {
        return new RoutingContext(
                routeName,
                newTenantId,
                newBizLine,
                feature,
                requiredCapabilities,
                preferredProviders,
                budgetTokensPerRequest);
    }

    /** 是否给某供应商打过偏好分（越大越优先）。 */
    public int providerPreference(String providerId) {
        int idx = preferredProviders.indexOf(providerId);
        return idx < 0 ? 0 : preferredProviders.size() - idx;
    }

    /** 人类可读描述，供控制台与日志展示。 */
    public String describe() {
        return "route="
                + routeName
                + ", tenant="
                + tenantId
                + (bizLine == null ? "" : ", bizLine=" + bizLine)
                + (requiredCapabilities.isEmpty() ? "" : ", requires=" + requiredCapabilities);
    }

    /** 便捷类型别名，避免调用方反复写全限定名。 */
    public ModelRef firstPreferredModel(java.util.Map<ModelRef, ?> available) {
        for (String provider : preferredProviders) {
            for (ModelRef ref : available.keySet()) {
                if (ref.providerId().equals(provider)) {
                    return ref;
                }
            }
        }
        return available.keySet().stream().findFirst().orElse(null);
    }
}
