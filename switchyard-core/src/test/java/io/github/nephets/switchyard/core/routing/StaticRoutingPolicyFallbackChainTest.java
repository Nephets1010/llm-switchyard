/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.routing;

import io.github.nephets.switchyard.core.model.ModelRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("静态路由降级链重组与上下文传递")
class StaticRoutingPolicyFallbackChainTest {

    private static final ModelRef PRIMARY = ModelRef.of("openai", "gpt-5-mini");
    private static final ModelRef BACKUP = ModelRef.of("deepseek", "deepseek-chat");
    private static final ModelRef LAST = ModelRef.of("dashscope", "qwen-plus");
    private static final ModelRef FOURTH = ModelRef.of("zhipu", "glm-4");

    @Test
    @DisplayName("主模型不可用时可用候选排在前面，不可用候选补回降级链")
    void degradeSkipsUnavailableCandidateInMiddle() {
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(
                        PRIMARY,
                        List.of(BACKUP, LAST, FOURTH),
                        (ctx, ref) -> ref.equals(LAST) || ref.equals(FOURTH));

        RouteDecision decision = policy.decide(RoutingContext.simple("default"));

        assertThat(decision.primary().text()).isEqualTo("dashscope/qwen-plus");
        assertThat(decision.fallbacks())
                .extracting(ModelRef::text)
                .containsExactly("zhipu/glm-4", "deepseek/deepseek-chat");
        assertThat(decision.fullChain())
                .extracting(ModelRef::text)
                .containsExactly("dashscope/qwen-plus", "zhipu/glm-4", "deepseek/deepseek-chat");
        assertThat(decision.strategy()).isEqualTo("static");
    }

    @Test
    @DisplayName("重复的不可用候选在降级链里只保留一个")
    void degradeDeduplicatesRepeatedUnavailableCandidate() {
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(
                        PRIMARY, List.of(BACKUP, LAST, BACKUP), (ctx, ref) -> ref.equals(LAST));

        RouteDecision decision = policy.decide(RoutingContext.simple("default"));

        assertThat(decision.primary().text()).isEqualTo("dashscope/qwen-plus");
        assertThat(decision.fallbacks())
                .extracting(ModelRef::text)
                .containsExactly("deepseek/deepseek-chat");
        assertThat(decision.fallbacks()).hasSize(1);
        assertThat(decision.strategy()).isEqualTo("static");
    }

    @Test
    @DisplayName("被选中的候选出现多次时，降级链里一次都不留")
    void degradeExcludesChosenEvenWhenItAppearsTwice() {
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(
                        PRIMARY, List.of(LAST, BACKUP, LAST), (ctx, ref) -> !ref.equals(PRIMARY));

        RouteDecision decision = policy.decide(RoutingContext.simple("default"));

        assertThat(decision.primary().text()).isEqualTo("dashscope/qwen-plus");
        assertThat(decision.fallbacks())
                .extracting(ModelRef::text)
                .containsExactly("deepseek/deepseek-chat");
        assertThat(decision.fallbacks())
                .extracting(ModelRef::text)
                .doesNotContain("dashscope/qwen-plus");
    }

    @Test
    @DisplayName("全部候选不可用时，降级链原样保留并给出完整理由")
    void allUnavailableKeepsOriginalFallbackChain() {
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(PRIMARY, List.of(BACKUP, LAST), (ctx, ref) -> false);

        RouteDecision decision = policy.decide(RoutingContext.simple("default"));

        assertThat(decision.primary().text()).isEqualTo("openai/gpt-5-mini");
        assertThat(decision.fallbacks())
                .extracting(ModelRef::text)
                .containsExactly("deepseek/deepseek-chat", "dashscope/qwen-plus");
        assertThat(decision.reason()).isEqualTo("所有候选模型当前均不可用，回退到主模型由熔断器统一决策");
        assertThat(decision.strategy()).isEqualTo("static");
    }

    @Test
    @DisplayName("主模型可用时理由与策略名使用完整字面量")
    void primaryAvailableReasonIsExactLiteral() {
        StaticRoutingPolicy policy = new StaticRoutingPolicy(PRIMARY, List.of(BACKUP, LAST));

        RouteDecision decision = policy.decide(RoutingContext.simple("default"));

        assertThat(decision.reason()).isEqualTo("主模型 openai/gpt-5-mini 可用");
        assertThat(decision.strategy()).isEqualTo("static");
        assertThat(decision.fallbacks())
                .extracting(ModelRef::text)
                .containsExactly("deepseek/deepseek-chat", "dashscope/qwen-plus");
    }

    @Test
    @DisplayName("null 上下文先归一化成 default，再传给每次可用性检查")
    void nullContextIsNormalizedBeforeAvailabilityCheck() {
        List<ModelRef> checkedRefs = new ArrayList<>();
        List<RoutingContext> receivedContexts = new ArrayList<>();
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(
                        PRIMARY,
                        List.of(BACKUP, LAST),
                        (ctx, ref) -> {
                            checkedRefs.add(ref);
                            receivedContexts.add(ctx);
                            return false;
                        });

        RouteDecision decision = policy.decide(null);

        assertThat(checkedRefs)
                .extracting(ModelRef::text)
                .containsExactly(
                        "openai/gpt-5-mini", "deepseek/deepseek-chat", "dashscope/qwen-plus");
        assertThat(receivedContexts).hasSize(3);
        assertThat(receivedContexts.get(0).routeName()).isEqualTo("default");
        assertThat(receivedContexts.get(0).tenantId()).isEqualTo("_default");
        assertThat(receivedContexts.get(1).routeName()).isEqualTo("default");
        assertThat(receivedContexts.get(1).tenantId()).isEqualTo("_default");
        assertThat(receivedContexts.get(2).routeName()).isEqualTo("default");
        assertThat(receivedContexts.get(2).tenantId()).isEqualTo("_default");
        assertThat(decision.primary().text()).isEqualTo("openai/gpt-5-mini");
    }

    @Test
    @DisplayName("自定义上下文原样传给每次可用性检查，未被复制")
    void customContextIsPassedThroughToEveryAvailabilityCheck() {
        RoutingContext custom =
                new RoutingContext("myroute", "vip", "bl", "kb", Set.of(), List.of(), 8000L);
        List<RoutingContext> receivedContexts = new ArrayList<>();
        StaticRoutingPolicy policy =
                new StaticRoutingPolicy(
                        PRIMARY,
                        List.of(BACKUP, LAST),
                        (ctx, ref) -> {
                            receivedContexts.add(ctx);
                            return false;
                        });

        policy.decide(custom);

        assertThat(receivedContexts).hasSize(3);
        assertThat(receivedContexts.get(0)).isSameAs(custom);
        assertThat(receivedContexts.get(1)).isSameAs(custom);
        assertThat(receivedContexts.get(2)).isSameAs(custom);
        assertThat(receivedContexts.get(0).routeName()).isEqualTo("myroute");
        assertThat(receivedContexts.get(0).tenantId()).isEqualTo("vip");
        assertThat(receivedContexts.get(0).budgetTokensPerRequest()).isEqualTo(8000L);
        assertThat(receivedContexts.get(2).budgetTokensPerRequest()).isEqualTo(8000L);
    }

    @Test
    @DisplayName("策略名、主模型与配置的降级链由 getter 直接给出")
    void policyExposesStaticNameAndPrimary() {
        StaticRoutingPolicy policy = new StaticRoutingPolicy(PRIMARY, List.of(BACKUP, LAST));

        assertThat(policy.name()).isEqualTo("static");
        assertThat(policy.primary().text()).isEqualTo("openai/gpt-5-mini");
        assertThat(policy.fallbacks())
                .extracting(ModelRef::text)
                .containsExactly("deepseek/deepseek-chat", "dashscope/qwen-plus");
    }

    @Test
    @DisplayName("构造时降级链被防御性复制，外部修改不影响且返回值不可改")
    void fallbacksAreDefensivelyCopiedOnConstruction() {
        List<ModelRef> mutable = new ArrayList<>();
        mutable.add(BACKUP);
        StaticRoutingPolicy policy = new StaticRoutingPolicy(PRIMARY, mutable);

        mutable.add(LAST);

        assertThat(policy.fallbacks())
                .extracting(ModelRef::text)
                .containsExactly("deepseek/deepseek-chat");
        assertThatThrownBy(() -> policy.fallbacks().add(LAST))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
