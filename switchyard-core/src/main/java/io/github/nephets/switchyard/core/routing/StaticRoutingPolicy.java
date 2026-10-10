/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.routing;

import io.github.nephets.switchyard.core.model.ModelRef;

import java.util.ArrayList;
import java.util.List;

/**
 * 静态路由策略（首版默认实现）：配置什么就是什么，主模型 + 有序降级链。
 *
 * <p><b>为什么首版不做「智能打分」</b>：PRD 把「按成本/延迟/质量三维打分动态选模」明确列在 P1。 更实际的原因是：没有真实流量数据时，任何打分权重都是拍脑袋的，
 * 一个「智能」但权重错误的路由比一个「笨但可预测」的路由更危险 —— 前者会让用户在半夜无法解释「为什么这个请求走了那个模型」。
 *
 * <p>首版把接口与上下文设计好，P1 只需新增一个 {@link RoutingPolicy} 实现，上层代码零改动。
 */
public class StaticRoutingPolicy implements RoutingPolicy {

    private final ModelRef primary;
    private final List<ModelRef> fallbacks;
    private final java.util.function.BiPredicate<RoutingContext, ModelRef> availabilityCheck;

    /**
     * 构造。
     *
     * @param primary 主模型
     * @param fallbacks 降级链；可为 null
     */
    public StaticRoutingPolicy(ModelRef primary, List<ModelRef> fallbacks) {
        this(primary, fallbacks, null);
    }

    /**
     * 带可用性检查的构造。
     *
     * <p>可用性检查用于「主模型熔断中时，直接从降级链里挑第一个可用的」， 避免每次都先失败一次再降级 —— 那会让 P99 延迟凭空多出一个 RTT。
     *
     * @param primary 主模型
     * @param fallbacks 降级链
     * @param availabilityCheck 可用性判定，入参为「上下文 + 待判定模型」，返回 false 表示该模型当前不可用； 为 null 时视为总是可用
     */
    public StaticRoutingPolicy(
            ModelRef primary,
            List<ModelRef> fallbacks,
            java.util.function.BiPredicate<RoutingContext, ModelRef> availabilityCheck) {
        if (primary == null) {
            throw new IllegalArgumentException("静态路由策略必须配置主模型");
        }
        this.primary = primary;
        this.fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        this.availabilityCheck = availabilityCheck;
    }

    @Override
    public RouteDecision decide(RoutingContext ctx) {
        RoutingContext context = ctx == null ? RoutingContext.simple("default") : ctx;

        // 主模型可用 → 正常路径
        if (isAvailable(context, primary)) {
            return new RouteDecision(primary, fallbacks, "主模型 " + primary.text() + " 可用", name());
        }

        // 主模型不可用 → 降级链里找第一个可用的
        List<ModelRef> remaining = new ArrayList<>();
        for (ModelRef candidate : fallbacks) {
            if (isAvailable(context, candidate)) {
                remaining.add(candidate);
            }
        }
        if (remaining.isEmpty()) {
            // 全部不可用：仍然返回主模型，让下游熔断器去做最终的快速失败决策并给出可读错误
            return new RouteDecision(primary, fallbacks, "所有候选模型当前均不可用，回退到主模型由熔断器统一决策", name());
        }
        ModelRef chosen = remaining.get(0);
        // 降级链 = 其余仍可用的候选 + 原降级链里尚未选中的项。
        // 必须排除 chosen 本身，否则主模型会在降级链里重复出现一次，
        // 让 FallbackChain 浪费一次「已尝试过」的空转。
        List<ModelRef> newFallbacks = new ArrayList<>();
        for (ModelRef ref : remaining) {
            if (!ref.equals(chosen)) {
                newFallbacks.add(ref);
            }
        }
        for (ModelRef ref : fallbacks) {
            if (!ref.equals(chosen) && !newFallbacks.contains(ref)) {
                newFallbacks.add(ref);
            }
        }
        return new RouteDecision(chosen, newFallbacks, "主模型不可用，降级到 " + chosen.text(), name());
    }

    @Override
    public String name() {
        return "static";
    }

    /** 主模型。 */
    public ModelRef primary() {
        return primary;
    }

    /** 配置的降级链。 */
    public List<ModelRef> fallbacks() {
        return fallbacks;
    }

    private boolean isAvailable(RoutingContext ctx, ModelRef ref) {
        return availabilityCheck == null || availabilityCheck.test(ctx, ref);
    }
}
