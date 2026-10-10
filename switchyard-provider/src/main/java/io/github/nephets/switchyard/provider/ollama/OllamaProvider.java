/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.core.model.GatewayRequest;
import io.github.nephets.switchyard.core.model.GatewayResponse;
import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.provider.Capability;
import io.github.nephets.switchyard.core.provider.ChatProvider;
import io.github.nephets.switchyard.core.provider.ProviderCapabilities;
import io.github.nephets.switchyard.core.usage.NormalizeContext;
import io.github.nephets.switchyard.core.usage.RawUsage;
import io.github.nephets.switchyard.core.usage.TokenEstimator;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageNormalizer;
import io.github.nephets.switchyard.provider.support.ProviderExceptionTranslator;
import io.github.nephets.switchyard.provider.support.ProviderHttpClientFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ollama 原生协议适配器（本地部署）。
 *
 * <p><b>本地部署是很多企业落地 LLM 的第一步</b>（数据不出内网），因此这个适配器不是可有可无的： 有了它，路由可以把「敏感数据走本地、常规数据走云端」这条策略真正落下去，
 * 而用户仍只需要换一处配置。
 */
public class OllamaProvider implements ChatProvider.StreamingChatProvider {

    private static final String CHAT_PATH = "/api/chat";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final UsageNormalizer normalizer;
    private final TokenEstimator estimator;
    private final Duration requestTimeout;

    /**
     * 构造。
     *
     * @param baseUrl base-url
     * @param httpClient HTTP 客户端
     * @param normalizer 归一化器
     * @param estimator 估算器
     * @param requestTimeout 超时；本地模型往往更慢，默认给足
     */
    public OllamaProvider(
            String baseUrl,
            HttpClient httpClient,
            UsageNormalizer normalizer,
            TokenEstimator estimator,
            Duration requestTimeout) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.httpClient = httpClient;
        this.mapper = OllamaWire.mapper();
        this.normalizer = normalizer;
        this.estimator = estimator;
        // 本地模型首 token 延迟明显高于云端，超时给 300s
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(300) : requestTimeout;
    }

    @Override
    public String providerId() {
        return "ollama";
    }

    @Override
    public ProviderCapabilities capabilities() {
        // Ollama 不返回缓存命中与推理 token，因此不声明 CACHED_INPUT / REASONING
        return new ProviderCapabilities(
                Set.of(Capability.STREAM, Capability.TOOL_CALL, Capability.METERING));
    }

    @Override
    public String name() {
        return "OllamaProvider";
    }

    @Override
    public GatewayResponse complete(GatewayRequest request) {
        OllamaWire.ChatRequest wire = toWireRequest(request, false);

        try {
            String responseBody =
                    ProviderHttpClientFactory.send(
                            httpClient,
                            ProviderHttpClientFactory.postJson(
                                    baseUrl + CHAT_PATH,
                                    null,
                                    null,
                                    serialize(wire),
                                    requestTimeout));
            OllamaWire.ChatResponse response =
                    OllamaWire.parse(responseBody, OllamaWire.ChatResponse.class);

            String content = response.message == null ? "" : nullToEmpty(response.message.content);
            RawUsage raw = OllamaWire.toRawUsage(response);
            Usage usage =
                    normalizer.normalize(
                            raw, NormalizeContext.nonStreaming(request.maxTokens()), estimator);

            ModelRef ref =
                    response.model == null
                            ? request.modelRef()
                            : new ModelRef("ollama", response.model, request.modelRef().region());

            return new GatewayResponse(
                    content, List.of(), usage, ref, response.doneReason, 0L, false, 1, List.of());
        } catch (java.io.IOException ex) {
            throw ProviderExceptionTranslator.wrap(-1, ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ProviderExceptionTranslator.wrap(504, "调用被中断", ex);
        }
    }

    @Override
    public GatewayResponse stream(GatewayRequest request) {
        OllamaWire.ChatRequest wire = toWireRequest(request, true);

        StringBuilder content = new StringBuilder();
        List<String> chunks = new ArrayList<>();
        // 用一个可变累加器承载流式状态：lambda 里不能直接给外层局部变量赋值
        // （要求 effectively final），用累加器是 WebFlux/SSE 场景下的标准写法。
        StreamAccumulator acc = new StreamAccumulator(content, chunks);
        long startedAt = System.nanoTime();

        try {
            ProviderHttpClientFactory.sendStream(
                    httpClient,
                    ProviderHttpClientFactory.postJson(
                            baseUrl + CHAT_PATH, null, null, serialize(wire), requestTimeout),
                    payload -> {
                        try {
                            OllamaWire.StreamChunk chunk =
                                    OllamaWire.parse(payload, OllamaWire.StreamChunk.class);
                            if (chunk.model != null) {
                                acc.model = chunk.model;
                            }
                            if (chunk.message != null && chunk.message.content != null) {
                                content.append(chunk.message.content);
                                chunks.add(chunk.message.content);
                            }
                            // Ollama 的 token 计数通常出现在最后一个分片上
                            if (chunk.promptEvalCount != null || chunk.evalCount != null) {
                                acc.usage = OllamaWire.toRawUsage(chunk);
                                acc.usageReported = true;
                            }
                            if (chunk.doneReason != null) {
                                acc.finishReason = chunk.doneReason;
                            }
                        } catch (RuntimeException ex) {
                            // 单个分片解析失败不中断整条流：丢弃该分片但保留已收到的内容与 usage
                            return true;
                        }
                        return true;
                    });
        } catch (java.io.IOException ex) {
            throw ProviderExceptionTranslator.wrap(-1, ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ProviderExceptionTranslator.wrap(504, "流式调用被中断", ex);
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        Usage usage =
                normalizer.normalize(
                        acc.usage,
                        new NormalizeContext(
                                true,
                                request.renderForEstimation(),
                                content.toString(),
                                request.maxTokens(),
                                acc.usageReported),
                        estimator);

        ModelRef ref =
                acc.model == null
                        ? request.modelRef()
                        : new ModelRef("ollama", acc.model, request.modelRef().region());

        return new GatewayResponse(
                content.toString(),
                chunks,
                usage,
                ref,
                acc.finishReason,
                elapsedMs,
                false,
                1,
                List.of());
    }

    /** 流式状态的累加器。 */
    private static final class StreamAccumulator {
        private final StringBuilder content;
        private final List<String> chunks;
        private RawUsage usage = RawUsage.empty();
        private boolean usageReported;
        private String model;
        private String finishReason;

        StreamAccumulator(StringBuilder content, List<String> chunks) {
            this.content = content;
            this.chunks = chunks;
        }
    }

    /** 转成 wire 请求。 */
    OllamaWire.ChatRequest toWireRequest(GatewayRequest request, boolean stream) {
        OllamaWire.ChatRequest wire = new OllamaWire.ChatRequest();
        wire.model = request.modelRef().modelId();
        wire.stream = stream;

        wire.messages =
                request.messages().stream()
                        .map(
                                m ->
                                        new OllamaWire.ChatRequest.Message(
                                                m.role().name().toLowerCase(java.util.Locale.ROOT),
                                                m.content()))
                        .toList();

        if (request.temperature() != null
                || request.topP() != null
                || request.maxTokens() != null) {
            wire.options = new OllamaWire.ChatRequest.Options();
            wire.options.temperature = request.temperature();
            wire.options.topP = request.topP();
            // ★ Ollama 用 num_predict 表示最大输出 token，与 OpenAI 的 max_tokens 语义一致但名字不同
            wire.options.numPredict = request.maxTokens();
        }

        if (!request.tools().isEmpty()) {
            wire.tools =
                    request.tools().stream()
                            .map(
                                    t ->
                                            new OllamaWire.ChatRequest.Tool(
                                                    t.name(), t.description(), t.parametersJson()))
                            .toList();
        }
        if (request.responseFormat() != null && !request.responseFormat().isBlank()) {
            wire.format = "json";
        }
        return wire;
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("序列化请求体失败", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
