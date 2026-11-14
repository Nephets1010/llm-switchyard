/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.routing;

import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.provider.Capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("路由上下文的归一化、描述与供应商选择")
class RoutingContextTest {

    private static final ModelRef PRIMARY = ModelRef.of("openai", "gpt-5-mini");
    private static final ModelRef BACKUP = ModelRef.of("deepseek", "deepseek-chat");
    private static final ModelRef LAST = ModelRef.of("dashscope", "qwen-plus");

    @Test
    @DisplayName("空白路由名回落到 default，带空格的名字去掉首尾空格")
    void blankRouteNameFallsBackToDefaultAndPaddedNameIsTrimmed() {
        assertThat(new RoutingContext("   ", "t", null, null, null, null, 0L).routeName())
                .isEqualTo("default");
        assertThat(new RoutingContext("  chat  ", "t", null, null, null, null, 0L).routeName())
                .isEqualTo("chat");
        assertThat(new RoutingContext("", "t", null, null, null, null, 0L).routeName())
                .isEqualTo("default");
        assertThat(new RoutingContext("chat", "t", null, null, null, null, 0L).routeName())
                .isEqualTo("chat");
    }

    @Test
    @DisplayName("能力集合被防御性复制且不可修改")
    void requiredCapabilitiesIsImmutableAndDefensivelyCopied() {
        Set<Capability> mutable = new HashSet<>();
        mutable.add(Capability.STREAM);
        RoutingContext ctx = new RoutingContext("chat", "t1", null, null, mutable, List.of(), 0L);

        mutable.add(Capability.METERING);

        assertThat(ctx.requiredCapabilities()).containsExactly(Capability.STREAM);
        assertThatThrownBy(() -> ctx.requiredCapabilities().add(Capability.METERING))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("simple 构造出的上下文各字段是固定默认值")
    void simpleContextSnapshot() {
        RoutingContext ctx = RoutingContext.simple("r");

        assertThat(ctx.routeName()).isEqualTo("r");
        assertThat(ctx.tenantId()).isEqualTo("_default");
        assertThat(ctx.bizLine()).isNull();
        assertThat(ctx.feature()).isNull();
        assertThat(ctx.requiredCapabilities()).isEmpty();
        assertThat(ctx.preferredProviders()).isEmpty();
        assertThat(ctx.budgetTokensPerRequest()).isEqualTo(0L);
        assertThat(RoutingContext.simple(null).routeName()).isEqualTo("default");
    }

    @Test
    @DisplayName("withTenant 只换租户和业务线，其余维度原样保留")
    void withTenantKeepsAllOtherDimensions() {
        RoutingContext ctx =
                new RoutingContext(
                        "chat",
                        "t1",
                        "bl",
                        "kb",
                        Set.of(Capability.STREAM),
                        List.of("deepseek"),
                        8000L);

        RoutingContext moved = ctx.withTenant("vip", "cs");

        assertThat(moved.tenantId()).isEqualTo("vip");
        assertThat(moved.bizLine()).isEqualTo("cs");
        assertThat(moved.routeName()).isEqualTo("chat");
        assertThat(moved.feature()).isEqualTo("kb");
        assertThat(moved.requiredCapabilities()).containsExactly(Capability.STREAM);
        assertThat(moved.preferredProviders()).containsExactly("deepseek");
        assertThat(moved.budgetTokensPerRequest()).isEqualTo(8000L);
        assertThat(ctx.tenantId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("describe 按条件拼出四组固定文本，不输出功能、偏好和预算")
    void describeRendersExactConditionalFields() {
        RoutingContext noConditions = new RoutingContext("chat", "t1", null, null, null, null, 0L);
        RoutingContext onlyBizLine = new RoutingContext("chat", "t1", "bl", null, null, null, 0L);
        RoutingContext onlyCaps =
                new RoutingContext(
                        "chat",
                        "t1",
                        null,
                        null,
                        Set.of(Capability.STREAM, Capability.TOOL_CALL),
                        null,
                        0L);
        RoutingContext both =
                new RoutingContext(
                        "chat",
                        "t1",
                        "bl",
                        "kb",
                        Set.of(Capability.STREAM, Capability.TOOL_CALL),
                        List.of("deepseek"),
                        8000L);

        assertThat(noConditions.describe()).isEqualTo("route=chat, tenant=t1");
        assertThat(onlyBizLine.describe()).isEqualTo("route=chat, tenant=t1, bizLine=bl");
        assertThat(onlyCaps.describe())
                .isEqualTo("route=chat, tenant=t1, requires=[STREAM, TOOL_CALL]");
        assertThat(both.describe())
                .isEqualTo("route=chat, tenant=t1, bizLine=bl, requires=[STREAM, TOOL_CALL]");
        assertThat(both.describe()).doesNotContain("kb", "deepseek", "8000");
    }

    @Test
    @DisplayName("供应商偏好分值是写死的 3、2、1、0")
    void providerPreferenceReturnsExactScores() {
        RoutingContext ctx =
                new RoutingContext("r", "t", null, null, null, List.of("a", "b", "c"), 0L);

        assertThat(ctx.providerPreference("a")).isEqualTo(3);
        assertThat(ctx.providerPreference("b")).isEqualTo(2);
        assertThat(ctx.providerPreference("c")).isEqualTo(1);
        assertThat(ctx.providerPreference("x")).isEqualTo(0);
    }

    @Test
    @DisplayName("偏好表有重复元素时只认首次位置，空表一律 0 分")
    void providerPreferenceHandlesDuplicatesAndEmpty() {
        RoutingContext duplicated =
                new RoutingContext("r", "t", null, null, null, List.of("a", "a", "b"), 0L);
        RoutingContext empty = new RoutingContext("r", "t", null, null, null, List.of(), 0L);

        assertThat(duplicated.providerPreference("a")).isEqualTo(3);
        assertThat(duplicated.providerPreference("b")).isEqualTo(1);
        assertThat(empty.providerPreference("a")).isEqualTo(0);
        assertThat(empty.providerPreference("b")).isEqualTo(0);
    }

    @Test
    @DisplayName("按偏好顺序找模型，优先于映射的放入顺序")
    void firstPreferredModelFollowsPreferenceOrderNotMapOrder() {
        Map<ModelRef, String> available = new LinkedHashMap<>();
        available.put(PRIMARY, "a");
        available.put(BACKUP, "b");
        available.put(LAST, "c");
        RoutingContext ctx =
                new RoutingContext("r", "t", null, null, null, List.of("dashscope", "openai"), 0L);

        assertThat(ctx.firstPreferredModel(available)).isEqualTo(LAST);
        assertThat(ctx.firstPreferredModel(available).text()).isEqualTo("dashscope/qwen-plus");
    }

    @Test
    @DisplayName("偏好没有命中时回退到映射里的第一个键")
    void firstPreferredModelFallsBackToFirstKeyWhenNoPreferenceMatches() {
        Map<ModelRef, String> available = new LinkedHashMap<>();
        available.put(PRIMARY, "a");
        available.put(BACKUP, "b");
        available.put(LAST, "c");
        RoutingContext ctx = new RoutingContext("r", "t", null, null, null, List.of("nope"), 0L);

        assertThat(ctx.firstPreferredModel(available)).isEqualTo(PRIMARY);
        assertThat(ctx.firstPreferredModel(available).text()).isEqualTo("openai/gpt-5-mini");
    }

    @Test
    @DisplayName("可用映射为空时返回 null")
    void firstPreferredModelReturnsNullForEmptyAvailable() {
        RoutingContext ctx = RoutingContext.simple("r");

        assertThat(ctx.firstPreferredModel(new LinkedHashMap<>())).isNull();
    }
}
