/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm;

import io.github.nephets.switchyard.mockllm.admin.Scenario;
import io.github.nephets.switchyard.mockllm.admin.ScenarioEngine;
import io.github.nephets.switchyard.mockllm.support.MockUsageFactory;
import io.github.nephets.switchyard.mockllm.support.RequestDigest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Mock LLM：场景引擎与可预测 usage")
class ScenarioEngineTest {

    @Test
    @DisplayName("★ 4 个关键故障场景都能生效且可 reset（429 / 500 / timeout / empty-usage-stream）")
    void criticalScenariosWork() {
        ScenarioEngine engine = new ScenarioEngine();
        assertThat(engine.current().name()).isEqualTo("ok");

        for (String name :
                new String[] {"rate-limit", "server-error", "timeout", "empty-usage-stream"}) {
            Scenario scenario = engine.switchTo(name);
            assertThat(scenario.name()).isEqualTo(name);
            assertThat(engine.current().name()).isEqualTo(name);
            assertThat(scenario.description()).as("%s 必须有可读描述", name).isNotBlank();
        }

        engine.reset();

        assertThat(engine.current().name()).isEqualTo("ok");
        assertThat(engine.requestCount()).isZero();
        assertThat(engine.errorCount()).isZero();
    }

    @Test
    @DisplayName("全部内置场景都能被切换（README 承诺的场景清单要真的可用）")
    void allBuiltinScenariosAreSelectable() {
        ScenarioEngine engine = new ScenarioEngine();

        for (Scenario scenario : Scenario.builtin()) {
            assertThat(engine.switchTo(scenario.name())).isEqualTo(scenario);
        }
        assertThat(engine.registered()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(engine.registered().keySet())
                .contains("ok", "rate-limit", "server-error", "timeout");
    }

    @Test
    @DisplayName("未知场景明确报错并提示可用清单（配置错误要立刻暴露）")
    void unknownScenarioRejected() {
        ScenarioEngine engine = new ScenarioEngine();

        assertThatThrownBy(() -> engine.switchTo("不存在的场景"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的场景");
    }

    @Test
    @DisplayName("★ failTimes 语义：前 N 次注入故障，之后自动恢复正常（让重试可被确定性断言）")
    void failTimesExhaustsThenRecovers() {
        ScenarioEngine engine = new ScenarioEngine();
        engine.switchTo("rate-limit-retryable"); // failTimes = 2

        // 前 2 次返回故障场景
        assertThat(engine.next("/v1/chat/completions").name()).isEqualTo("rate-limit-retryable");
        assertThat(engine.next("/v1/chat/completions").name()).isEqualTo("rate-limit-retryable");
        // 第 3 次恢复正常 → 重试链路可以断言「最终成功」
        assertThat(engine.next("/v1/chat/completions").name()).isEqualTo("ok");
        assertThat(engine.errorCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("永久故障场景每次都注入（failTimes <= 0）")
    void permanentFailureAlwaysFails() {
        ScenarioEngine engine = new ScenarioEngine();
        engine.switchTo("rate-limit");

        for (int i = 0; i < 5; i++) {
            assertThat(engine.next("/x").httpStatus()).isEqualTo(429);
        }
        assertThat(engine.errorCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("请求与错误计数被正确累计（供 /__admin/stats 使用）")
    void countersAreTracked() {
        ScenarioEngine engine = new ScenarioEngine();
        engine.switchTo("server-error");

        engine.next("/a");
        engine.next("/b");
        engine.reset();
        engine.next("/c");

        assertThat(engine.requestCount()).isEqualTo(1);
        assertThat(engine.errorCount()).isZero();
    }

    @Test
    @DisplayName("health 快照含全部场景名，供 docker healthcheck 判断就绪")
    void healthSnapshot() {
        ScenarioEngine engine = new ScenarioEngine();
        engine.switchTo("slow");
        engine.next("/x");

        var health = engine.health();

        assertThat(health).containsEntry("status", "UP").containsEntry("scenario", "slow");
        assertThat(health).containsEntry("requestCount", 1L);
        @SuppressWarnings("unchecked")
        java.util.Set<String> names = (java.util.Set<String>) health.get("scenarios");
        assertThat(names).contains("ok", "rate-limit");
    }

    @Test
    @DisplayName("★ 可预测 usage：同一 prompt 恒得同一结果（让测试能写精确断言）")
    void usageIsDeterministic() {
        String prompt = "请分析第123号工单的处理流程";

        long first = MockUsageFactory.inputTokens(prompt);
        long second = MockUsageFactory.inputTokens(prompt);

        assertThat(first).isEqualTo(second).isPositive();
        assertThat(MockUsageFactory.outputTokens("固定长度的响应文本"))
                .isEqualTo(MockUsageFactory.outputTokens("固定长度的响应文本"));
        assertThat(MockUsageFactory.inputTokens(null)).isEqualTo(16L);
        assertThat(MockUsageFactory.inputTokens("")).isEqualTo(16L);
        assertThat(MockUsageFactory.outputTokens(null)).isEqualTo(8L);
    }

    @Test
    @DisplayName("缓存与推理 token 由输入输出确定性推导，且不超过各自上限")
    void derivedTokensAreBounded() {
        long input = MockUsageFactory.inputTokens("一段用于测试的中文 prompt");
        long cached = MockUsageFactory.cachedInputTokens(input);
        long output = MockUsageFactory.outputTokens("一段用于测试的响应");
        long reasoning = MockUsageFactory.reasoningTokens(output);

        assertThat(cached).isPositive().isLessThan(input);
        assertThat(reasoning).isPositive().isLessThanOrEqualTo(output);
        // 4 的倍数让「缓存比例是整数」，断言才有确定期望值
        assertThat(cached % 4).isZero();
    }

    @Test
    @DisplayName("分片切分：数量可控且拼接后等于原文（流式测试的基础）")
    void splitIsLossless() {
        String content = MockUsageFactory.reply("你好", "gpt-5-mini");
        var chunks = MockUsageFactory.split(content, 3);

        assertThat(chunks).hasSize(3);
        assertThat(String.join("", chunks)).isEqualTo(content);
        assertThat(MockUsageFactory.split("", 3)).isEmpty();
        assertThat(MockUsageFactory.split(null, 3)).isEmpty();
    }

    @Test
    @DisplayName("响应文本包含模型名与 prompt 摘要（便于 demo 里肉眼确认走了哪个模型）")
    void replyIsDescriptive() {
        String reply = MockUsageFactory.reply("退款流程", "deepseek-chat");

        assertThat(reply).contains("deepseek-chat").contains("退款");
        assertThat(MockUsageFactory.reply(null, "m")).contains("m");
    }

    @Test
    @DisplayName("请求摘要：同内容同摘要（确定性回放的基础）")
    void digestIsStable() {
        String payload = "请帮我核对订单 12345";

        assertThat(RequestDigest.shortDigest(payload))
                .isEqualTo(RequestDigest.shortDigest(payload));
        assertThat(RequestDigest.shortDigest(payload)).hasSize(8);
        assertThat(RequestDigest.shortDigest(payload + "！"))
                .isNotEqualTo(RequestDigest.shortDigest(payload));
        assertThat(RequestDigest.shortDigest(null)).isEqualTo("00000000");
        assertThat(RequestDigest.shortDigest("")).isEqualTo("00000000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"中文", "english", "混合 mixed 123", "😀 emoji"})
    @DisplayName("中英/emoji 输入都能得到可用的 token 数")
    void estimatorHandlesAllScripts(String text) {
        assertThat(MockUsageFactory.inputTokens(text)).isPositive();
        assertThat(MockUsageFactory.outputTokens(text)).isPositive();
    }

    @Test
    @DisplayName("场景的流式相关开关按名字对应")
    void scenarioFlags() {
        assertThat(Scenario.emptyUsageStream().omitStreamUsage()).isTrue();
        assertThat(Scenario.emptyUsageStream().httpStatus()).isZero();
        assertThat(Scenario.streamDropped().dropStreamMidway()).isTrue();
        assertThat(Scenario.streamDropped().httpStatus()).isZero();
        assertThat(Scenario.ok().omitStreamUsage()).isFalse();
        assertThat(Scenario.ok().dropStreamMidway()).isFalse();
        assertThat(Scenario.invalidRequest().httpStatus()).isEqualTo(400);
        assertThat(Scenario.timeout().latencyMillis()).isEqualTo(30_000L);
        assertThat(Scenario.slow().latencyMillis()).isEqualTo(2_000L);
        assertThat(Scenario.rateLimit().httpStatus()).isEqualTo(429);
        assertThat(Scenario.rateLimitTimes(2).failTimes()).isEqualTo(2);
    }
}
