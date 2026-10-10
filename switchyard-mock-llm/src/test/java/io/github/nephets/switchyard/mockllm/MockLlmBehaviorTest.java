/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm;

import io.github.nephets.switchyard.mockllm.admin.Scenario;
import io.github.nephets.switchyard.mockllm.admin.ScenarioEngine;
import io.github.nephets.switchyard.mockllm.support.MockUsageFactory;
import io.github.nephets.switchyard.mockllm.support.RequestDigest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * mock-llm 的行为测试。
 *
 * <p>这个模块的价值全在「可预测」：usage 由 prompt 长度确定性推导， 于是任何针对 mock-llm 的测试都能写出精确断言，而不是 {@code
 * assertThat(x).isPositive()}。 本类锁住这份「可预测性」，防止有人把它改成随机数后全世界的测试都失去意义。
 */
@DisplayName("mock-llm：可预测的 usage 与可控的故障场景")
class MockLlmBehaviorTest {

    private ScenarioEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ScenarioEngine();
    }

    @Nested
    @DisplayName("usage 确定性（这是整个 mock 体系的地基）")
    class DeterministicUsage {

        @Test
        @DisplayName("★ 同一 prompt 永远得到同一 token 数")
        void samePromptSameTokens() {
            String prompt = "请帮我核对一下这个订单的物流状态";
            long first = MockUsageFactory.inputTokens(prompt);
            long second = MockUsageFactory.inputTokens(prompt);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("★ token 数随 prompt 长度单调递增（不出现「更长的文本反而更少 token」）")
        void monotonicWithLength() {
            long previous = 0L;
            for (int i = 1; i <= 30; i++) {
                String prompt = "字".repeat(i);
                long tokens = MockUsageFactory.inputTokens(prompt);
                assertThat(tokens).isGreaterThanOrEqualTo(previous);
                previous = tokens;
            }
        }

        @Test
        @DisplayName("★ 输出按 4 的倍数对齐，让缓存比例是整数（便于精确断言）")
        void alignedToFour() {
            for (int i = 1; i <= 20; i++) {
                assertThat(MockUsageFactory.inputTokens("字".repeat(i)) % 4)
                        .as("长度为 %d 时 token 数应是 4 的倍数", i)
                        .isZero();
            }
        }

        @Test
        @DisplayName("★ 缓存命中按 40% 推导且同样对齐（缓存不重复计费的断言需要确定值）")
        void cachedTokensDeterministic() {
            long input = 1000L;
            long cached = MockUsageFactory.cachedInputTokens(input);

            assertThat(cached).isEqualTo(400L);
            assertThat(cached).isLessThan(input);
            assertThat(cached).isEqualTo(MockUsageFactory.cachedInputTokens(input));
        }

        @Test
        @DisplayName("★ 推理 token 按输出量的 1/4 推导")
        void reasoningTokensDeterministic() {
            assertThat(MockUsageFactory.reasoningTokens(400L)).isEqualTo(100L);
            assertThat(MockUsageFactory.reasoningTokens(400L))
                    .isEqualTo(MockUsageFactory.reasoningTokens(400L));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("空 prompt 退化为最小值而非异常")
        void emptyPromptDegrades(String prompt) {
            assertThat(MockUsageFactory.inputTokens(prompt)).isPositive();
            assertThat(MockUsageFactory.inputTokens(null)).isPositive();
            assertThat(MockUsageFactory.outputTokens(prompt)).isPositive();
        }
    }

    @Nested
    @DisplayName("分片切分")
    class ChunkSplitting {

        @Test
        @DisplayName("★ 切分后拼回原文（分片不能丢字符，否则流式内容校验会失败）")
        void splitPreservesContent() {
            String content = "这是一段用于测试流式分片的中文内容，长度足够触发多次切分。";
            List<String> chunks = MockUsageFactory.split(content, 3);

            assertThat(chunks).hasSize(3);
            assertThat(String.join("", chunks)).isEqualTo(content);
        }

        @Test
        @DisplayName("分片数大于内容长度时也不会产生空串")
        void noEmptyChunks() {
            List<String> chunks = MockUsageFactory.split("短", 10);

            assertThat(chunks).isNotEmpty().allSatisfy(c -> assertThat(c).isNotEmpty());
            assertThat(String.join("", chunks)).isEqualTo("短");
        }

        @Test
        @DisplayName("空内容切出空列表（不是一串空串）")
        void emptyContentYieldsEmptyList() {
            assertThat(MockUsageFactory.split("", 3)).isEmpty();
            assertThat(MockUsageFactory.split(null, 3)).isEmpty();
        }
    }

    @Nested
    @DisplayName("故障场景引擎")
    class ScenarioEngineBehavior {

        @Test
        @DisplayName("★ 场景可切换，统计随之归零")
        void switchResetsStats() {
            engine.next("/x");
            engine.next("/x");
            assertThat(engine.requestCount()).isEqualTo(2L);

            Scenario slow = engine.switchTo("slow");
            assertThat(slow.name()).isEqualTo("slow");
            assertThat(slow.latencyMillis()).isEqualTo(2000L);
            assertThat(engine.current().name()).isEqualTo("slow");

            engine.reset();
            assertThat(engine.current().name()).isEqualTo("ok");
            assertThat(engine.requestCount()).isZero();
        }

        @Test
        @DisplayName("★ 切换到未知场景立即报错并列出可用场景（配置错误要立刻暴露）")
        void unknownScenarioRejected() {
            assertThatThrownBy(() -> engine.switchTo("不存在"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未知的场景")
                    .hasMessageContaining("rate-limit");
        }

        @Test
        @DisplayName("★ 9 个内置场景齐备（demo 与 CI 都依赖它们）")
        void allBuiltinScenariosRegistered() {
            assertThat(engine.registered())
                    .containsKeys(
                            "ok",
                            "rate-limit",
                            "rate-limit-retryable",
                            "server-error",
                            "invalid-req",
                            "slow",
                            "timeout",
                            "empty-usage-stream",
                            "drop-stream");
        }

        @Test
        @DisplayName("★ failTimes 场景：前 N 次失败后自动恢复正常（让「重试成功」可被确定断言）")
        void failTimesExhausts() {
            engine.switchTo("rate-limit-retryable");

            // 前 2 次应失败
            assertThat(engine.next("/x").httpStatus()).isEqualTo(429);
            assertThat(engine.next("/x").httpStatus()).isEqualTo(429);
            // 第 3 次开始恢复正常
            assertThat(engine.next("/x").httpStatus()).isZero();
            assertThat(engine.next("/x").httpStatus()).isZero();
        }

        @Test
        @DisplayName("永久失败场景每次都失败（用于验证「重试耗尽后仍失败」）")
        void permanentFailure() {
            engine.switchTo("rate-limit");

            for (int i = 0; i < 5; i++) {
                assertThat(engine.next("/x").httpStatus()).isEqualTo(429);
            }
            assertThat(engine.errorCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("健康快照包含场景名与统计")
        void healthSnapshot() {
            engine.switchTo("server-error");
            engine.next("/x");

            var health = engine.health();
            assertThat(health).containsEntry("status", "UP");
            assertThat(health).containsEntry("scenario", "server-error");
            assertThat(health).containsEntry("errorCount", 1L);
            // Map.copyOf 的迭代顺序不确定，用 containsAnyOf 而不是 contains（后者要求连续顺序）
            @SuppressWarnings("unchecked")
            java.util.Collection<String> scenarios =
                    (java.util.Collection<String>) health.get("scenarios");
            assertThat(scenarios).contains("ok", "rate-limit", "slow", "empty-usage-stream");
        }
    }

    @Nested
    @DisplayName("请求摘要")
    class RequestDigestBehavior {

        @Test
        @DisplayName("★ 摘要是 8 位十六进制且稳定（可用于把响应关联回请求）")
        void digestStable() {
            String payload = "请帮我核对订单 20260909-00123";
            String digest = RequestDigest.shortDigest(payload);

            assertThat(digest).hasSize(8).matches("[0-9a-f]{8}");
            assertThat(RequestDigest.shortDigest(payload)).isEqualTo(digest);
        }

        @Test
        @DisplayName("不同内容得到不同摘要")
        void differentContentDifferentDigest() {
            assertThat(RequestDigest.shortDigest("a")).isNotEqualTo(RequestDigest.shortDigest("b"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("空载荷返回全 0 摘要而非抛异常")
        void emptyPayloadDegrades(String payload) {
            assertThat(RequestDigest.shortDigest(payload)).isEqualTo("00000000");
        }
    }

    @Nested
    @DisplayName("场景语义")
    class ScenarioSemantics {

        @Test
        @DisplayName("★ empty-usage-stream 专门用于复现「流式计量恒为 0」的历史行为")
        void emptyUsageStreamScenario() {
            Scenario scenario = Scenario.emptyUsageStream();

            assertThat(scenario.omitStreamUsage()).as("★ 这个场景是 PRD 痛点 4 的复现开关，不能被改掉").isTrue();
            assertThat(scenario.httpStatus()).isZero();
        }

        @Test
        @DisplayName("★ drop-stream 场景不发送 [DONE]，用于验证异常处理")
        void dropStreamScenario() {
            Scenario scenario = Scenario.streamDropped();

            assertThat(scenario.dropStreamMidway()).isTrue();
        }

        @Test
        @DisplayName("invalid-req 是 400（不可重试），rate-limit 是 429（可重试）")
        void retryabilityDistinction() {
            assertThat(Scenario.invalidRequest().httpStatus()).isEqualTo(400);
            assertThat(Scenario.rateLimit().httpStatus()).isEqualTo(429);
            assertThat(Scenario.serverError().httpStatus()).isEqualTo(500);
        }
    }
}
