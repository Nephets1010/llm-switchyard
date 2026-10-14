/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.routing;

import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.provider.Capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("静态路由策略与路由决策")
class StaticRoutingPolicyTest {

    private static final ModelRef PRIMARY = ModelRef.of("openai", "gpt-5-mini");
    private static final ModelRef BACKUP = ModelRef.of("deepseek", "deepseek-chat");
    private static final ModelRef LAST = ModelRef.of("dashscope", "qwen-plus");

    @Test
    @DisplayName("可用时直接选主模型，降级链原样保留")
    void primaryWhenAvailable() {
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(PRIMARY, java.util.List.of(BACKUP, LAST));

        RouteDecision decision = policy.decide(RoutingContext.simple("default"));

        assertThat(decision.primary()).isEqualTo(PRIMARY);
        assertThat(decision.fallbacks()).containsExactly(BACKUP, LAST);
        assertThat(decision.reason()).contains("可用");
        assertThat(decision.strategy()).isEqualTo("static");
    }

    @Test
    @DisplayName("★ 主模型不可用时直接选第一个可用的备用模型（省掉一次失败 RTT）")
    void skipsUnavailablePrimary() {
        // 可用性检查：主模型熔断中，两个备用都健康
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(
                        PRIMARY,
                        java.util.List.of(BACKUP, LAST),
                        (ctx, ref) -> !PRIMARY.equals(ref));

        RouteDecision decision = policy.decide(RoutingContext.simple("default"));

        assertThat(decision.primary()).isEqualTo(BACKUP);
        assertThat(decision.reason()).contains("降级到").contains(BACKUP.text());
        // 主模型不能在降级链里重复出现；被选中的 BACKUP 被剔除，余下 LAST 保留
        assertThat(decision.fallbacks()).containsExactly(LAST);
        assertThat(decision.fallbacks()).doesNotContain(BACKUP);
        assertThat(decision.fullChain()).containsExactly(BACKUP, LAST);
    }

    @Test
    @DisplayName("全部候选不可用时回退到主模型，交由熔断器统一决策")
    void allUnavailableFallsBackToPrimary() {
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(PRIMARY, java.util.List.of(BACKUP), (ctx, ref) -> false);

        RouteDecision decision = policy.decide(RoutingContext.simple("default"));

        assertThat(decision.primary()).isEqualTo(PRIMARY);
        assertThat(decision.reason()).contains("均不可用");
    }

    @Test
    @DisplayName("null 上下文按 default 处理，不抛异常")
    void nullContextHandled() {
        StaticRoutingPolicy policy = new StaticRoutingPolicy(PRIMARY, java.util.List.of());
        assertThat(policy.decide(null).primary()).isEqualTo(PRIMARY);
    }

    @Test
    @DisplayName("必须配置主模型")
    void primaryRequired() {
        assertThatThrownBy(() -> new StaticRoutingPolicy(null, java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主模型");
    }

    @Test
    @DisplayName("null 降级链按空链处理")
    void nullFallbacks() {
        StaticRoutingPolicy policy = new StaticRoutingPolicy(PRIMARY, null);
        assertThat(policy.fallbacks()).isEmpty();
    }

    @Test
    @DisplayName("RouteDecision.fullChain 拼出完整链路")
    void fullChain() {
        RouteDecision decision =
                new RouteDecision(PRIMARY, java.util.List.of(BACKUP), "原因", "static");

        assertThat(decision.fullChain()).containsExactly(PRIMARY, BACKUP);
        assertThat(decision.fullChain()).hasSize(2);
    }

    @Test
    @DisplayName("RouteDecision 必须给出主模型，其余字段填充默认值")
    void routeDecisionValidation() {
        assertThatThrownBy(() -> new RouteDecision(null, java.util.List.of(), "", "static"))
                .isInstanceOf(IllegalArgumentException.class);

        RouteDecision minimal = new RouteDecision(PRIMARY, null, null, null);
        assertThat(minimal.fallbacks()).isEmpty();
        assertThat(minimal.reason()).isEmpty();
        assertThat(minimal.strategy()).isEqualTo("static");
    }

    @Test
    @DisplayName("RoutingContext 填充默认值并归一化不可变集合")
    void routingContextDefaults() {
        RoutingContext ctx = new RoutingContext(null, null, null, null, null, null, -1L);

        assertThat(ctx.routeName()).isEqualTo("default");
        assertThat(ctx.requiredCapabilities()).isEmpty();
        assertThat(ctx.preferredProviders()).isEmpty();
        assertThat(ctx.budgetTokensPerRequest()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("能力约束与供应商偏好被正确记录")
    void routingContextCapabilities() {
        RoutingContext ctx =
                new RoutingContext(
                        "customer",
                        "vip",
                        "customer-service",
                        "kb-search",
                        java.util.Set.of(Capability.STREAM, Capability.TOOL_CALL),
                        java.util.List.of("deepseek", "openai"),
                        8_000L);

        assertThat(ctx.routeName()).isEqualTo("customer");
        assertThat(ctx.requiredCapabilities())
                .containsExactlyInAnyOrder(Capability.STREAM, Capability.TOOL_CALL);
        assertThat(ctx.describe()).contains("customer").contains("vip").contains("requires");
    }

    @Test
    @DisplayName("供应商偏好按顺序打分（越靠前分越高）")
    void providerPreferenceScoring() {
        RoutingContext ctx =
                new RoutingContext(
                        "default",
                        "vip",
                        null,
                        null,
                        java.util.Set.of(),
                        java.util.List.of("deepseek", "openai"),
                        0L);

        assertThat(ctx.providerPreference("deepseek"))
                .isGreaterThan(ctx.providerPreference("openai"));
        assertThat(ctx.providerPreference("unknown")).isZero();
    }

    @Test
    @DisplayName("withTenant 返回换了租户维度的新上下文")
    void withTenant() {
        RoutingContext ctx = RoutingContext.simple("default");
        RoutingContext moved = ctx.withTenant("vip", "customer-service");

        assertThat(moved.tenantId()).isEqualTo("vip");
        assertThat(moved.bizLine()).isEqualTo("customer-service");
        assertThat(ctx.tenantId()).as("原上下文不可变").isEqualTo("_default");
    }

    @Test
    @DisplayName("firstPreferredModel 按偏好挑模型，无偏好时取第一个")
    void firstPreferredModel() {
        java.util.Map<ModelRef, String> available =
                java.util.Map.of(PRIMARY, "a", BACKUP, "b", LAST, "c");

        RoutingContext preferDeepseek =
                new RoutingContext(
                        "default",
                        "vip",
                        null,
                        null,
                        java.util.Set.of(),
                        java.util.List.of("deepseek"),
                        0L);
        assertThat(preferDeepseek.firstPreferredModel(available)).isEqualTo(BACKUP);

        RoutingContext noPreference = RoutingContext.simple("default");
        assertThat(noPreference.firstPreferredModel(available)).isNotNull();
    }

    @Test
    @DisplayName("不可变集合被防御性复制，外部修改不影响上下文")
    void collectionsAreDefensivelyCopied() {
        java.util.List<String> providers = new java.util.ArrayList<>(java.util.List.of("openai"));
        RoutingContext ctx =
                new RoutingContext("default", "vip", null, null, java.util.Set.of(), providers, 0L);

        providers.add("deepseek");

        assertThat(ctx.preferredProviders()).hasSize(1);
    }
}
