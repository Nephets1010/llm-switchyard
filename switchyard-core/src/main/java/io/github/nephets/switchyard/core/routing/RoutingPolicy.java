/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.routing;

/**
 * 路由策略（SPI）。
 *
 * <p>抽象成接口是为了让「按成本/延迟/质量三维打分动态选模」（PRD P1）能平滑加进来， 而不影响调用方 —— 调用方只依赖 {@link #decide} 这一个方法。
 */
public interface RoutingPolicy {

    /**
     * 做出路由决策。
     *
     * @param ctx 路由上下文
     * @return 路由决策
     */
    RouteDecision decide(RoutingContext ctx);

    /** 策略名，写进审计与 {@code swy.route.strategy}。 */
    String name();
}
