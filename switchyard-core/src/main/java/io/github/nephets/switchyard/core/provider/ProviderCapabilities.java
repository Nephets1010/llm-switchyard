/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.provider;

import java.util.EnumSet;
import java.util.Set;

/**
 * 供应商能力描述。
 *
 * <p>把能力做成不可变值对象而不是散落的 boolean，是为了让「路由策略按能力过滤」成为可能： 用户可以把「必须支持工具调用」作为一条路由约束，而不必去猜某个供应商支不支持。
 *
 * @param capabilities 支持的能力集合
 */
public record ProviderCapabilities(Set<Capability> capabilities) {

    /** 紧凑构造器：防御性复制 + 不可变。 */
    public ProviderCapabilities {
        // ⚠️ EnumSet.copyOf(Collection) 对空集合会抛 IllegalArgumentException，
        // 因为空集合无法推断枚举类型。这里必须显式区分空/非空。
        capabilities =
                capabilities == null || capabilities.isEmpty()
                        ? java.util.Collections.unmodifiableSet(EnumSet.noneOf(Capability.class))
                        : java.util.Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    /** 空能力集。 */
    public static ProviderCapabilities none() {
        return new ProviderCapabilities(Set.of());
    }

    /**
     * 是否支持某能力。
     *
     * @param capability 能力
     * @return 支持返回 true
     */
    public boolean supports(Capability capability) {
        return capabilities.contains(capability);
    }

    /**
     * 是否支持全部给定能力（路由约束用）。
     *
     * @param required 必需能力集合
     * @return 全部支持返回 true
     */
    public boolean supportsAll(Set<Capability> required) {
        return required == null || required.isEmpty() || capabilities.containsAll(required);
    }

    /**
     * 能力名列表，供控制台 JSON 与 README 表格渲染。
     *
     * @return 按枚举声明顺序排列的能力名
     */
    public java.util.List<String> names() {
        return capabilities.stream().map(Enum::name).sorted().toList();
    }
}
