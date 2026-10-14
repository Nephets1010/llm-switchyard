/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import io.github.nephets.switchyard.core.ErrorCode;
import io.github.nephets.switchyard.core.SwitchyardException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("错误分类：4xx 不重试 / 429·5xx·超时 重试")
class ErrorClassifierTest {

    private final DefaultErrorClassifier classifier = new DefaultErrorClassifier();

    /** 携带 HTTP 状态码的测试异常，走 HttpStatusCarrier 路径。 */
    private static final class StatusException extends RuntimeException
            implements DefaultErrorClassifier.HttpStatusCarrier {
        private final int status;

        StatusException(int status) {
            super("上游返回 status: " + status);
            this.status = status;
        }

        @Override
        public int httpStatus() {
            return status;
        }
    }

    @ParameterizedTest(name = "HTTP {0} → retryable={1}, code={2}")
    @DisplayName("★ 429/5xx/超时 可重试；400/401/403/422 不可重试")
    @CsvSource({
        "429, true,  SW_PROVIDER_UNAVAILABLE",
        "500, true,  SW_PROVIDER_UNAVAILABLE",
        "502, true,  SW_PROVIDER_UNAVAILABLE",
        "503, true,  SW_PROVIDER_UNAVAILABLE",
        "504, true,  SW_PROVIDER_TIMEOUT",
        "408, true,  SW_PROVIDER_TIMEOUT",
        "400, false, SW_BAD_REQUEST",
        "401, false, SW_BAD_REQUEST",
        "403, false, SW_BAD_REQUEST",
        "422, false, SW_CONTENT_FILTERED",
        "404, false, SW_BAD_REQUEST"
    })
    void retryabilityByStatus(int status, boolean retryable, ErrorCode expectedCode) {
        RuntimeException ex = new StatusException(status);

        assertThat(classifier.isRetryable(ex)).as("HTTP %d 的可重试性", status).isEqualTo(retryable);
        assertThat(classifier.errorCode(ex)).isEqualTo(expectedCode);
    }

    @ParameterizedTest(name = "{0} 判为超时且可重试")
    @DisplayName("超时类异常一律可重试")
    @MethodSource("timeoutExceptions")
    void timeoutsAreRetryable(Throwable ex) {
        assertThat(classifier.isRetryable(ex)).isTrue();
        assertThat(classifier.errorCode(ex)).isEqualTo(ErrorCode.SW_PROVIDER_TIMEOUT);
    }

    static java.util.stream.Stream<Throwable> timeoutExceptions() {
        // 刻意手工构造而非反射：HttpTimeoutException 没有无参构造，反射会失败
        return java.util.stream.Stream.of(
                new HttpTimeoutException("请求超时"),
                new TimeoutException("等待超时"),
                new java.net.SocketTimeoutException("读超时"));
    }

    @Test
    @DisplayName("IOException 视为瞬时网络故障，可重试")
    void ioExceptionIsRetryable() {
        assertThat(classifier.isRetryable(new IOException("connection reset"))).isTrue();
        assertThat(classifier.errorCode(new IOException("connection reset")))
                .isEqualTo(ErrorCode.SW_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("★ 业务参数错误（IllegalArgumentException）不可重试 —— 无意义重试只会白烧配额")
    void businessErrorIsNotRetryable() {
        RuntimeException ex = new IllegalArgumentException("model 不存在");
        assertThat(classifier.isRetryable(ex)).isFalse();
        assertThat(classifier.errorCode(ex)).isEqualTo(ErrorCode.SW_INTERNAL);
    }

    @Test
    @DisplayName("SwitchyardException 直接采用其自带的错误码，不被二次翻译")
    void switchyardExceptionPassesThrough() {
        SwitchyardException ex = new SwitchyardException(ErrorCode.SW_BAD_REQUEST, "参数错误", "abc");

        assertThat(classifier.errorCode(ex)).isEqualTo(ErrorCode.SW_BAD_REQUEST);
        assertThat(classifier.isRetryable(ex)).isFalse();
    }

    @Test
    @DisplayName("异常被层层包装时仍能向上追溯出状态码（深度 6 层内）")
    void statusSurvivesWrapping() {
        Throwable deep =
                new RuntimeException(
                        "外层",
                        new RuntimeException(
                                "中层", new RuntimeException("内层", new StatusException(429))));

        assertThat(classifier.httpStatus(deep)).isEqualTo(429);
        assertThat(classifier.isRetryable(deep)).isTrue();
    }

    @Test
    @DisplayName("null 异常归为内部错误且不可重试")
    void nullThrowable() {
        assertThat(classifier.errorCode(null)).isEqualTo(ErrorCode.SW_INTERNAL);
        assertThat(classifier.isRetryable(null)).isFalse();
    }

    @Test
    @DisplayName("cause 链很深也能安全遍历（不会因层数超限而漏判，也不会死循环）")
    void deepCauseChainTerminates() {
        Throwable deep = new RuntimeException("层0");
        for (int i = 1; i <= 10; i++) {
            deep = new RuntimeException("层" + i, deep);
        }

        assertThat(classifier.errorCode(deep)).isNotNull();
        assertThat(classifier.httpStatus(deep)).isEqualTo(-1);
    }

    @Test
    @DisplayName("错误码到 HTTP 状态码的映射与契约 ERR-001 一致")
    void errorCodeHttpStatusMatchesContract() {
        assertThat(ErrorCode.SW_QUOTA_EXCEEDED.httpStatus()).isEqualTo(429);
        assertThat(ErrorCode.SW_BAD_REQUEST.httpStatus()).isEqualTo(400);
        assertThat(ErrorCode.SW_PROVIDER_UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(ErrorCode.SW_PROVIDER_TIMEOUT.httpStatus()).isEqualTo(504);
        assertThat(ErrorCode.SW_CONTENT_FILTERED.httpStatus()).isEqualTo(422);
        assertThat(ErrorCode.SW_INTERNAL.httpStatus()).isEqualTo(500);
    }

    @Test
    @DisplayName("重试执行器：注入 429 可重试成功")
    void retryExecutorRetriesOnRetryable() {
        RetryExecutor executor =
                new RetryExecutor(RetryPolicy.defaults(), classifier, millis -> {});
        int[] calls = {0};

        String result =
                executor.execute(
                        () -> {
                            calls[0]++;
                            if (calls[0] < 3) {
                                throw new StatusException(429);
                            }
                            return "ok";
                        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls[0]).as("必须真的重试到第 3 次").isEqualTo(3);
    }

    @Test
    @DisplayName("★ 重试执行器：注入 400 只发 1 次请求，不做无意义重试")
    void retryExecutorDoesNotRetryOn400() {
        RetryExecutor executor =
                new RetryExecutor(RetryPolicy.defaults(), classifier, millis -> {});
        int[] calls = {0};

        assertThatThrownBy(
                        () ->
                                executor.execute(
                                        () -> {
                                            calls[0]++;
                                            throw new StatusException(400);
                                        }))
                .isInstanceOf(StatusException.class);

        assertThat(calls[0]).as("参数错误必须只尝试 1 次").isEqualTo(1);
    }

    @Test
    @DisplayName("重试次数耗尽后抛出最后一次异常")
    void retryExhausts() {
        RetryExecutor executor =
                new RetryExecutor(RetryPolicy.defaults(), classifier, millis -> {});
        int[] calls = {0};

        assertThatThrownBy(
                        () ->
                                executor.execute(
                                        () -> {
                                            calls[0]++;
                                            throw new StatusException(503);
                                        }))
                .isInstanceOf(StatusException.class);

        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    @DisplayName("重试回调带上 attempt 与错误码，供日志与埋点使用")
    void retryObserverReceivesContext() {
        RetryExecutor executor =
                new RetryExecutor(RetryPolicy.defaults(), classifier, millis -> {});
        List<String> observations = new java.util.ArrayList<>();
        int[] calls = {0};

        executor.execute(
                () -> {
                    calls[0]++;
                    if (calls[0] < 2) {
                        throw new StatusException(429);
                    }
                    return "ok";
                },
                (attempt, code, backoffMillis) ->
                        observations.add(attempt + ":" + code + ":" + backoffMillis));

        assertThat(observations)
                .hasSize(1)
                .allSatisfy(o -> assertThat(o).startsWith("1:SW_PROVIDER_UNAVAILABLE:"));
    }

    @Test
    @DisplayName("降级链按顺序推进且去重")
    void fallbackChainProgression() {
        var chain =
                new FallbackChain(
                        List.of(
                                io.github.nephets.switchyard.core.model.ModelRef.of(
                                        "openai", "gpt-5-mini"),
                                io.github.nephets.switchyard.core.model.ModelRef.of(
                                        "deepseek", "deepseek-chat"),
                                io.github.nephets.switchyard.core.model.ModelRef.of(
                                        "deepseek", "deepseek-chat"),
                                io.github.nephets.switchyard.core.model.ModelRef.of(
                                        "dashscope", "qwen-plus")));

        assertThat(chain.chain()).hasSize(3).as("重复项应被去重");
        assertThat(chain.isSingle()).isFalse();
        assertThat(chain.describe())
                .isEqualTo("openai/gpt-5-mini → deepseek/deepseek-chat → dashscope/qwen-plus");
    }

    @Test
    @DisplayName("降级链耗尽时抛 SW_MODEL_UNAVAILABLE（可重试）")
    void fallbackChainExhausted() {
        var only = io.github.nephets.switchyard.core.model.ModelRef.of("openai", "gpt-5-mini");
        var chain = new FallbackChain(List.of(only));

        assertThat(chain.next(null)).contains(only);
        assertThatThrownBy(() -> chain.next(only))
                .isInstanceOf(SwitchyardException.class)
                .extracting(ex -> ((SwitchyardException) ex).errorCode())
                .isEqualTo(ErrorCode.SW_MODEL_UNAVAILABLE);
    }

    @Test
    @DisplayName("降级过程可查（PRD：降级链路有日志可查）")
    void fallbackTrailIsRecorded() {
        var primary = io.github.nephets.switchyard.core.model.ModelRef.of("openai", "gpt-5-mini");
        var backup =
                io.github.nephets.switchyard.core.model.ModelRef.of("deepseek", "deepseek-chat");
        var chain = new FallbackChain(List.of(primary, backup));

        chain.next(primary);

        assertThat(chain.degradationTrail(primary)).containsExactly(primary, backup);
        chain.resetTrail();
        assertThat(chain.degradationTrail(primary)).isEmpty();
    }
}
