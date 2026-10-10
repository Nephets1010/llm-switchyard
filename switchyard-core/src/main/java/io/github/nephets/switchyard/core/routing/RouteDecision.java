/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.routing;

import io.github.nephets.switchyard.core.model.ModelRef;

import java.util.List;

/**
 * 路由决策结果。
 *
 * @param primary 主模型
 * @param fallbacks 降级链（有序，首项为次优）
 * @param reason 决策理由（写进审计与 span，便于回答「为什么走了这个模型」）
 * @param strategy 决策策略名
 */
public record RouteDecision(
        ModelRef primary, List<ModelRef> fallbacks, String reason, String strategy) {

    /** 紧凑构造器：默认值与防御性复制。 */
    public RouteDecision {
        if (primary == null) {
            throw new IllegalArgumentException("路由决策必须给出主模型");
        }
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        reason = reason == null ? "" : reason;
        strategy = strategy == null ? "static" : strategy;
    }

    /** 完整链路：主模型 + 降级链。 */
    public List<ModelRef> fullChain() {
        List<ModelRef> all = new java.util.ArrayList<>();
        all.add(primary);
        all.addAll(fallbacks);
        return List.copyOf(all);
    }
}
