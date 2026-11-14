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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("路由决策结果的默认值与防御性复制")
class RouteDecisionTest {

    private static final ModelRef PRIMARY = ModelRef.of("openai", "gpt-5-mini");
    private static final ModelRef BACKUP = ModelRef.of("deepseek", "deepseek-chat");
    private static final ModelRef LAST = ModelRef.of("dashscope", "qwen-plus");

    @Test
    @DisplayName("非 null 的空白理由与空策略名保持原样")
    void routeDecisionKeepsBlankReasonAndEmptyStrategy() {
        RouteDecision decision = new RouteDecision(PRIMARY, List.of(), "  ", "");

        assertThat(decision.reason()).isEqualTo("  ");
        assertThat(decision.strategy()).isEqualTo("");
        assertThat(decision.fallbacks()).isEmpty();
        assertThat(decision.primary().text()).isEqualTo("openai/gpt-5-mini");
    }

    @Test
    @DisplayName("降级链在构造时被复制，外部改不动，返回值也不可改")
    void routeDecisionDefensivelyCopiesFallbacks() {
        List<ModelRef> mutable = new ArrayList<>();
        mutable.add(BACKUP);
        RouteDecision decision = new RouteDecision(PRIMARY, mutable, "原因", "static");

        mutable.add(LAST);

        assertThat(decision.fallbacks())
                .extracting(ModelRef::text)
                .containsExactly("deepseek/deepseek-chat");
        assertThatThrownBy(() -> decision.fallbacks().add(LAST))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("fullChain 返回完整链路的不可变快照")
    void fullChainReturnsImmutableSnapshot() {
        RouteDecision decision = new RouteDecision(PRIMARY, List.of(BACKUP), "原因", "static");

        assertThat(decision.fullChain())
                .extracting(ModelRef::text)
                .containsExactly("openai/gpt-5-mini", "deepseek/deepseek-chat");
        assertThatThrownBy(() -> decision.fullChain().add(LAST))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
