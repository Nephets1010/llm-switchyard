/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.nephets.switchyard.core.model.GatewayRequest;
import io.github.nephets.switchyard.core.model.GatewayResponse;
import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.usage.CharHeuristicTokenEstimator;
import io.github.nephets.switchyard.core.usage.DefaultUsageNormalizer;
import io.github.nephets.switchyard.core.usage.UsageNormalizer;
import io.github.nephets.switchyard.core.usage.UsageSource;
import io.github.nephets.switchyard.provider.anthropic.AnthropicProvider;
import io.github.nephets.switchyard.provider.ollama.OllamaProvider;
import io.github.nephets.switchyard.provider.openai.OpenAiCompatibleProvider;
import io.github.nephets.switchyard.provider.support.ProviderHttpClientFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ 三个适配器的 WireMock 契约测试。
 *
 * <p><b>验收价值</b>（架构 T03 第 2 条）：同一份抽象测试模板，只换 {@code baseUrl} 与响应样本， 就能保证「3 个适配器 / 9
 * 家供应商行为一致」不是一句口头承诺。
 *
 * <p>全部使用 WireMock，<b>不调用任何真实 API、不产生任何费用</b>（CI 硬约束）。
 */
@DisplayName("供应商适配器契约测试（WireMock，零真实 API）")
class ProviderContractIT {

    private WireMockServer server;
    private HttpClient httpClient;
    private UsageNormalizer normalizer;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        WireMock.configureFor("localhost", server.port());
        httpClient = ProviderHttpClientFactory.create(Duration.ofSeconds(5), null);
        normalizer = new DefaultUsageNormalizer();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    /** 参与契约测试的适配器：3 个协议族。 */
    static Stream<ProviderCase> adapters() {
        return Stream.of(
                new ProviderCase("openai", "gpt-5-mini"),
                new ProviderCase("dashscope", "qwen-plus"),
                new ProviderCase("deepseek", "deepseek-chat"),
                new ProviderCase("zhipu", "glm-4-plus"),
                new ProviderCase("moonshot", "moonshot-v1-128k"),
                new ProviderCase("doubao", "doubao-pro-32k"),
                new ProviderCase("siliconflow", "Qwen/Qwen3-8B"));
    }

    /** ★ 6 家 OpenAI 兼容供应商共用同一份用例模板 —— 只换 providerId 与模型名， 任何一家行为漂移都会立刻被发现。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("同步调用：usage 三层嵌套字段正确解析，total 由本仓重算")
    void openAiCompatibleNonStreaming(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "id": "chatcmpl-test",
                          "model": "%s",
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "你好，我是道岔。"},
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": 1000,
                            "completion_tokens": 200,
                            "total_tokens": 99999,
                            "prompt_tokens_details": {"cached_tokens": 400},
                            "completion_tokens_details": {"reasoning_tokens": 50}
                          }
                        }
                        """
                                                        .formatted(testCase.modelId()))));

        GatewayResponse response = newProvider(testCase).complete(simpleRequest(testCase));

        assertThat(response.content()).isEqualTo("你好，我是道岔。");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.usage().inputTokens()).isEqualTo(1000L);
        assertThat(response.usage().outputTokens()).isEqualTo(200L);
        assertThat(response.usage().cachedInputTokens()).as("缓存命中必须被解析出来").isEqualTo(400L);
        assertThat(response.usage().reasoningTokens()).as("推理 token 必须被解析出来").isEqualTo(50L);
        assertThat(response.usage().totalTokens())
                .as("★ total 必须由本仓重算（1000+200+50=1250），绝不是上游的 99999")
                .isEqualTo(1250L)
                .isNotEqualTo(99_999L);
        assertThat(response.usage().usageSource()).isEqualTo(UsageSource.VENDOR_REPORTED);
    }

    /** ★ PRD P0-3 的核心验收：流式请求必须注入 include_usage，且末尾 usage chunk 必须被解析。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("★ 流式调用：请求带 include_usage=true，末尾 usage chunk 被解析，计量不为 0")
    void openAiCompatibleStreamingIncludesUsage(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/event-stream")
                                        .withBody(
                                                """
                        data: {"id":"1","model":"%s","choices":[{"index":0,"delta":{"content":"你好"}}]}

                        data: {"id":"1","model":"%s","choices":[{"index":0,"delta":{"content":"，世界"}}]}

                        data: {"id":"1","model":"%s","choices":[{"index":0,"delta":{"content":"！"},"finish_reason":"stop"}]}

                        data: {"id":"1","model":"%s","choices":[],"usage":{"prompt_tokens":800,"completion_tokens":150,"total_tokens":8888,"prompt_tokens_details":{"cached_tokens":200}}}

                        data: [DONE]

                        """
                                                        .formatted(
                                                                testCase.modelId(),
                                                                testCase.modelId(),
                                                                testCase.modelId(),
                                                                testCase.modelId()))));

        GatewayResponse response = newProvider(testCase).stream(streamRequest(testCase));

        assertThat(response.content()).isEqualTo("你好，世界！");
        assertThat(response.usage().inputTokens()).isEqualTo(800L);
        assertThat(response.usage().outputTokens()).isEqualTo(150L);
        assertThat(response.usage().cachedInputTokens()).isEqualTo(200L);
        assertThat(response.usage().totalTokens())
                .as("★ 流式计量必须为 0 之外的真实值（800+150），且 total 由本仓重算")
                .isEqualTo(950L)
                .isNotEqualTo(8888L);
        assertThat(response.usage().streamUsageReported()).as("★ 真收到 usage 块时该标志必须为 true").isTrue();
        assertThat(response.usage().usageSource()).isEqualTo(UsageSource.VENDOR_REPORTED);

        // ★ 请求体必须真的带了 include_usage —— 这是流式计量不为 0 的根因修复点
        server.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(
                                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                                        "$.stream_options.include_usage",
                                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                                                "true"))));
    }

    /**
     * ★ PRD 痛点 4 的历史行为复现：vLLM / OpenAI 未开 include_usage 时不返回 usage 块。 断言此时计量<b>不为 0</b>，而是走估算并标
     * ESTIMATED。
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("★ 流式无 usage 块时回退估算，计量仍 > 0（修复前恒为 0）")
    void streamingWithoutUsageChunkStillMetered(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/event-stream")
                                        .withBody(
                                                """
                        data: {"id":"1","model":"%s","choices":[{"index":0,"delta":{"content":"这是一段用于测试的中文回复"}}]}

                        data: [DONE]

                        """
                                                        .formatted(testCase.modelId()))));

        GatewayResponse response = newProvider(testCase).stream(streamRequest(testCase));

        assertThat(response.usage().totalTokens()).as("★ 修复前这里恒为 0 —— 现在必须 > 0").isGreaterThan(0L);
        assertThat(response.usage().streamUsageReported()).as("如实标记未收到 usage 块").isFalse();
        assertThat(response.usage().usageSource()).isEqualTo(UsageSource.ESTIMATED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("工具调用被正确解析")
    void toolCalls(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "id":"chatcmpl-tool",
                          "model":"%s",
                          "choices":[{
                            "index":0,
                            "message":{
                              "role":"assistant",
                              "content":null,
                              "tool_calls":[{
                                "id":"call_1",
                                "type":"function",
                                "function":{"name":"search_kb","arguments":"{\\"q\\":\\"退款\\"}"}
                              }]
                            },
                            "finish_reason":"tool_calls"
                          }],
                          "usage":{"prompt_tokens":500,"completion_tokens":80}
                        }
                        """
                                                        .formatted(testCase.modelId()))));

        GatewayResponse response = newProvider(testCase).complete(simpleRequest(testCase));

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls().get(0).name()).isEqualTo("search_kb");
        assertThat(response.finishReason()).isEqualTo("tool_calls");
    }

    @ParameterizedTest(name = "{0} 结构化输出")
    @MethodSource("adapters")
    @DisplayName("结构化输出（json_object）被正确请求与解析")
    void structuredOutput(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {"id":"1","model":"%s","choices":[{"index":0,"message":{"role":"assistant",
                        "content":"{\\"summary\\":\\"已退款\\"}"},"finish_reason":"stop"}],
                        "usage":{"prompt_tokens":300,"completion_tokens":60}}
                        """
                                                        .formatted(testCase.modelId()))));

        GatewayRequest request =
                new GatewayRequest(
                        List.of(
                                new GatewayRequest.Message(
                                        GatewayRequest.MessageRole.USER, "总结这次工单")),
                        ModelRef.of(testCase.providerId(), testCase.modelId()),
                        256,
                        null,
                        null,
                        false,
                        List.of(),
                        "json_object");
        GatewayResponse response = newProvider(testCase).complete(request);

        assertThat(response.content()).contains("已退款");
        server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    /** Anthropic：system 独立字段 + 缓存双字段。 */
    @ParameterizedTest(name = "anthropic")
    @MethodSource("anthropicCases")
    @DisplayName("Anthropic：system 提到顶层、usage 字段名与 OpenAI 完全不同")
    void anthropicNonStreaming(String providerId) {
        server.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "id":"msg_test",
                          "type":"message",
                          "role":"assistant",
                          "model":"claude-haiku-4-5",
                          "content":[{"type":"text","text":"你好，我是 Claude。"}],
                          "stop_reason":"end_turn",
                          "usage":{"input_tokens":1500,"output_tokens":250,
                                   "cache_creation_input_tokens":200,"cache_read_input_tokens":1000}
                        }
                        """)));

        AnthropicProvider provider =
                new AnthropicProvider(
                        baseUrl(),
                        "test-key",
                        httpClient,
                        normalizer,
                        new CharHeuristicTokenEstimator(),
                        Duration.ofSeconds(10));

        GatewayRequest request =
                new GatewayRequest(
                        List.of(
                                new GatewayRequest.Message(
                                        GatewayRequest.MessageRole.SYSTEM, "你是客服助手"),
                                new GatewayRequest.Message(GatewayRequest.MessageRole.USER, "你好")),
                        ModelRef.of("anthropic", "claude-haiku-4-5"),
                        512,
                        null,
                        null,
                        false,
                        List.of(),
                        null);

        GatewayResponse response = provider.complete(request);

        assertThat(response.content()).isEqualTo("你好，我是 Claude。");
        assertThat(response.usage().inputTokens()).isEqualTo(1500L);
        assertThat(response.usage().outputTokens()).isEqualTo(250L);
        assertThat(response.usage().cachedInputTokens())
                .as("★ cache_read_input_tokens 必须映射到 cachedInputTokens")
                .isEqualTo(1000L);
        assertThat(response.usage().totalTokens())
                .as("★ Anthropic 无 total 字段，全靠本仓重算")
                .isEqualTo(1750L);
        assertThat(response.modelRef().modelId()).isEqualTo("claude-haiku-4-5");

        // system 必须被提到顶层而不是混在 messages 里
        server.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                        .withRequestBody(
                                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                                        "$.system",
                                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                                                "你是客服助手"))));
    }

    static Stream<String> anthropicCases() {
        return Stream.of("anthropic");
    }

    /** Anthropic 流式：usage 分两处到达（message_start + message_delta），必须合并。 */
    @ParameterizedTest(name = "anthropic-stream")
    @MethodSource("anthropicCases")
    @DisplayName("Anthropic 流式：两处 usage 合并，计量完整不为半残")
    void anthropicStreaming(String providerId) {
        server.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/event-stream")
                                        .withBody(
                                                """
                        data: {"type":"message_start","message":{"id":"msg_1","model":"claude-haiku-4-5","usage":{"input_tokens":1200,"output_tokens":1,"cache_read_input_tokens":600}}}

                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"世界"}}

                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":1200,"output_tokens":350}}

                        data: {"type":"message_stop"}

                        """)));

        AnthropicProvider provider =
                new AnthropicProvider(
                        baseUrl(),
                        "test-key",
                        httpClient,
                        normalizer,
                        new CharHeuristicTokenEstimator(),
                        Duration.ofSeconds(10));

        GatewayResponse response = provider.stream(streamAnthropicRequest());

        assertThat(response.content()).isEqualTo("你好世界");
        assertThat(response.usage().inputTokens()).isEqualTo(1200L);
        assertThat(response.usage().outputTokens())
                .as("★ 输出侧 usage 来自 message_delta，必须被合并进来而不是被输入侧覆盖")
                .isEqualTo(350L);
        assertThat(response.usage().totalTokens()).isEqualTo(1550L);
        assertThat(response.finishReason()).isEqualTo("end_turn");
    }

    /** Ollama：字段名与 OpenAI 相反，必须独立解析。 */
    @ParameterizedTest(name = "ollama")
    @MethodSource("ollamaCases")
    @DisplayName("Ollama：prompt_eval_count / eval_count 被正确解析（字段名与 OpenAI 相反）")
    void ollamaNonStreaming(String providerId) {
        server.stubFor(
                post(urlEqualTo("/api/chat"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {"model":"qwen3:8b","created_at":"2026-10-09T00:00:00Z",
                         "message":{"role":"assistant","content":"你好，我是本地模型。"},
                         "done":true,"done_reason":"stop",
                         "prompt_eval_count":777,"eval_count":123}
                        """)));

        OllamaProvider provider =
                new OllamaProvider(
                        baseUrl(),
                        httpClient,
                        normalizer,
                        new CharHeuristicTokenEstimator(),
                        Duration.ofSeconds(10));

        GatewayResponse response =
                provider.complete(
                        new GatewayRequest(
                                List.of(
                                        new GatewayRequest.Message(
                                                GatewayRequest.MessageRole.USER, "你好")),
                                ModelRef.of("ollama", "qwen3:8b"),
                                512,
                                null,
                                null,
                                false,
                                List.of(),
                                null));

        assertThat(response.content()).isEqualTo("你好，我是本地模型。");
        assertThat(response.usage().inputTokens())
                .as("★ prompt_eval_count → inputTokens（套用 OpenAI 解析器会得到 0）")
                .isEqualTo(777L);
        assertThat(response.usage().outputTokens()).isEqualTo(123L);
        assertThat(response.usage().totalTokens()).isEqualTo(900L);
    }

    static Stream<String> ollamaCases() {
        return Stream.of("ollama");
    }

    /** Ollama 流式：token 计数在末尾分片。 */
    @ParameterizedTest(name = "ollama-stream")
    @MethodSource("ollamaCases")
    @DisplayName("Ollama 流式：末尾分片的 token 计数被正确捕获")
    void ollamaStreaming(String providerId) {
        server.stubFor(
                post(urlEqualTo("/api/chat"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/x-ndjson")
                                        .withBody(
                                                """
                        {"model":"qwen3:8b","message":{"role":"assistant","content":"你好"},"done":false}

                        {"model":"qwen3:8b","message":{"role":"assistant","content":"世界"},"done":false}

                        {"model":"qwen3:8b","message":{"role":"assistant","content":"！"},"done":true,"done_reason":"stop","prompt_eval_count":600,"eval_count":90}

                        """)));

        OllamaProvider provider =
                new OllamaProvider(
                        baseUrl(),
                        httpClient,
                        normalizer,
                        new CharHeuristicTokenEstimator(),
                        Duration.ofSeconds(10));

        GatewayResponse response =
                provider.stream(
                        new GatewayRequest(
                                List.of(
                                        new GatewayRequest.Message(
                                                GatewayRequest.MessageRole.USER, "你好")),
                                ModelRef.of("ollama", "qwen3:8b"),
                                512,
                                null,
                                null,
                                true,
                                List.of(),
                                null));

        assertThat(response.content()).isEqualTo("你好世界！");
        assertThat(response.chunks()).hasSize(3);
        assertThat(response.usage().totalTokens()).as("★ 流式计量不为 0").isEqualTo(690L);
    }

    @ParameterizedTest(name = "{0} 429 可重试")
    @MethodSource("adapters")
    @DisplayName("★ 429 → 可重试错误（ErrorClassifier 能识别出状态码）")
    void rateLimitIsRetryable(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(429)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}")));

        Throwable thrown =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> newProvider(testCase).complete(simpleRequest(testCase)));

        assertThat(thrown).isNotNull();
        int status =
                new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier()
                        .httpStatus(thrown);
        assertThat(status).isEqualTo(429);
        assertThat(
                        new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier()
                                .isRetryable(thrown))
                .as("★ 429 必须被判为可重试")
                .isTrue();
    }

    @ParameterizedTest(name = "{0} 400 不可重试")
    @MethodSource("adapters")
    @DisplayName("★ 400 → 不可重试（不做无意义重试）")
    void badRequestIsNotRetryable(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"message\":\"Invalid parameter: max_tokens\"}}")));

        Throwable thrown =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> newProvider(testCase).complete(simpleRequest(testCase)));

        assertThat(thrown).isNotNull();
        var classifier = new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier();
        assertThat(classifier.isRetryable(thrown)).as("★ 参数错误绝不重试").isFalse();
        assertThat(classifier.errorCode(thrown))
                .isEqualTo(io.github.nephets.switchyard.core.ErrorCode.SW_BAD_REQUEST);
    }

    @ParameterizedTest(name = "{0} 500 可重试")
    @MethodSource("adapters")
    @DisplayName("500 → 可重试")
    void serverErrorIsRetryable(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(500)
                                        .withBody("{\"error\":{\"message\":\"internal\"}}")));

        Throwable thrown =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> newProvider(testCase).complete(simpleRequest(testCase)));

        var classifier = new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier();
        assertThat(classifier.isRetryable(thrown)).isTrue();
        assertThat(classifier.errorCode(thrown))
                .isEqualTo(io.github.nephets.switchyard.core.ErrorCode.SW_PROVIDER_UNAVAILABLE);
    }

    @ParameterizedTest(name = "{0} 校验 base-url")
    @MethodSource("adapters")
    @DisplayName("末尾斜杠被正确归一（避免拼出 //chat/completions）")
    void trailingSlashNormalized(ProviderCase testCase) {
        server.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"id\":\"1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                                        + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                                                        + "\"completion_tokens\":1}}")));

        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(
                        testCase.providerId(),
                        baseUrl() + "/v1/", // 刻意带末尾斜杠
                        "test-key",
                        httpClient,
                        normalizer,
                        new CharHeuristicTokenEstimator(),
                        Duration.ofSeconds(10),
                        null,
                        "Authorization");

        assertThat(provider.complete(simpleRequest(testCase)).content()).isEqualTo("ok");
        server.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
    }

    private OpenAiCompatibleProvider newProvider(ProviderCase testCase) {
        return new OpenAiCompatibleProvider(
                testCase.providerId(),
                baseUrl() + "/v1",
                "test-key",
                httpClient,
                normalizer,
                new CharHeuristicTokenEstimator(),
                Duration.ofSeconds(10),
                null,
                "Authorization");
    }

    private GatewayRequest simpleRequest(ProviderCase testCase) {
        return new GatewayRequest(
                List.of(new GatewayRequest.Message(GatewayRequest.MessageRole.USER, "你好")),
                ModelRef.of(testCase.providerId(), testCase.modelId()),
                512,
                0.7d,
                0.9d,
                false,
                List.of(),
                null);
    }

    private GatewayRequest streamRequest(ProviderCase testCase) {
        return new GatewayRequest(
                List.of(new GatewayRequest.Message(GatewayRequest.MessageRole.USER, "你好")),
                ModelRef.of(testCase.providerId(), testCase.modelId()),
                512,
                0.7d,
                0.9d,
                true,
                List.of(),
                null);
    }

    private GatewayRequest streamAnthropicRequest() {
        return new GatewayRequest(
                List.of(
                        new GatewayRequest.Message(GatewayRequest.MessageRole.SYSTEM, "你是客服助手"),
                        new GatewayRequest.Message(GatewayRequest.MessageRole.USER, "你好")),
                ModelRef.of("anthropic", "claude-haiku-4-5"),
                512,
                null,
                null,
                true,
                List.of(),
                null);
    }

    /** 一个「供应商 + 模型」的测试参数。 */
    record ProviderCase(String providerId, String modelId) {}
}
