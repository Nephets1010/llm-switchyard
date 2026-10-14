/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.core.model.GatewayRequest;
import io.github.nephets.switchyard.core.model.GatewayResponse;
import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.provider.Capability;
import io.github.nephets.switchyard.core.provider.ChatProvider;
import io.github.nephets.switchyard.core.provider.ProviderCapabilities;
import io.github.nephets.switchyard.core.provider.ProviderCatalogEntry;
import io.github.nephets.switchyard.core.usage.NormalizeContext;
import io.github.nephets.switchyard.core.usage.RawUsage;
import io.github.nephets.switchyard.core.usage.TokenEstimator;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageNormalizer;
import io.github.nephets.switchyard.provider.support.ProviderExceptionTranslator;
import io.github.nephets.switchyard.provider.support.ProviderHttpClientFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * OpenAI 兼容协议适配器 —— <b>一个适配器覆盖 7 家供应商</b>。
 *
 * <p>覆盖：OpenAI / 通义千问 / 智谱 GLM / DeepSeek / Kimi(月之暗面) / 豆包(火山方舟) / 硅基流动。
 *
 * <p><b>这是首版最关键的工程取舍</b>（架构决策）：这 7 家的 API 都是 OpenAI 兼容协议， 请求体与响应体结构一致，唯一差别是 {@code base-url} 与模型名。
 * 为每家写一个适配器意味着 6 份几乎完全相同的代码 —— 收益极低、维护成本极高、 且六份实现在细节上必然漂移（6 份实现里总有一份忘了映射 cached_tokens）。
 *
 * <p><b>本适配器保证行为一致的三件事</b>：
 *
 * <ol>
 *   <li>流式请求<b>无条件</b>注入 {@code stream_options.include_usage=true}；
 *   <li>非流式与流式走<b>同一套</b> usage 字段映射（{@link OpenAiStreamUsageCollector#toRawUsage}）；
 *   <li>{@code totalTokens} 一律由 core 重算，绝不拷贝上游 {@code total_tokens}。
 * </ol>
 */
public class OpenAiCompatibleProvider implements ChatProvider.StreamingChatProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final String providerId;
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final UsageNormalizer normalizer;
    private final TokenEstimator estimator;
    private final Duration requestTimeout;
    private final ProviderCapabilities capabilities;
    private final String apiKeyHeader;

    /** 用默认能力集构造（面向 OpenAI 兼容的通用场景）。 */
    public OpenAiCompatibleProvider(
            String providerId,
            String baseUrl,
            String apiKey,
            HttpClient httpClient,
            UsageNormalizer normalizer,
            TokenEstimator estimator) {
        this(
                providerId,
                baseUrl,
                apiKey,
                httpClient,
                normalizer,
                estimator,
                Duration.ofSeconds(120),
                new ProviderCapabilities(
                        Set.of(
                                Capability.STREAM,
                                Capability.STRUCTURED_OUTPUT,
                                Capability.TOOL_CALL,
                                Capability.METERING,
                                Capability.CACHED_INPUT)),
                "Authorization");
    }

    /**
     * 完整构造。
     *
     * @param providerId 供应商标识
     * @param baseUrl base-url（不含末尾斜杠）
     * @param apiKey API Key，可为 null（本地部署）
     * @param httpClient HTTP 客户端
     * @param normalizer usage 归一化器
     * @param estimator token 估算器
     * @param requestTimeout 请求超时
     * @param capabilities 能力声明
     * @param apiKeyHeader API Key 头名
     */
    public OpenAiCompatibleProvider(
            String providerId,
            String baseUrl,
            String apiKey,
            HttpClient httpClient,
            UsageNormalizer normalizer,
            TokenEstimator estimator,
            Duration requestTimeout,
            ProviderCapabilities capabilities,
            String apiKeyHeader) {
        this.providerId =
                providerId == null ? "openai" : providerId.toLowerCase(java.util.Locale.ROOT);
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.mapper = OpenAiWire.mapper();
        this.normalizer = normalizer;
        this.estimator = estimator;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(120) : requestTimeout;
        this.capabilities = capabilities == null ? ProviderCapabilities.none() : capabilities;
        this.apiKeyHeader = apiKeyHeader == null ? "Authorization" : apiKeyHeader;
    }

    /**
     * 按目录条目创建适配器（starter 装配用）。
     *
     * @param entry 目录条目
     * @param apiKey API Key
     * @param httpClient HTTP 客户端
     * @param normalizer 归一化器
     * @param estimator 估算器
     * @param requestTimeout 超时
     * @return 适配器实例
     */
    public static OpenAiCompatibleProvider fromCatalog(
            ProviderCatalogEntry entry,
            String apiKey,
            HttpClient httpClient,
            UsageNormalizer normalizer,
            TokenEstimator estimator,
            Duration requestTimeout) {
        return new OpenAiCompatibleProvider(
                entry.providerId(),
                entry.defaultBaseUrl(),
                apiKey,
                httpClient,
                normalizer,
                estimator,
                requestTimeout,
                entry.capabilities(),
                "Authorization");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public String name() {
        return "OpenAiCompatibleProvider[" + providerId + "]";
    }

    @Override
    public GatewayResponse complete(GatewayRequest request) {
        log.info(
                "OpenAI 兼容非流式请求：provider={} model={} baseUrl={}",
                providerId,
                request.modelRef().modelId(),
                baseUrl);
        OpenAiWire.ChatRequest wire = toWireRequest(request, false);
        String body = serialize(wire);

        try {
            String responseBody =
                    ProviderHttpClientFactory.send(
                            httpClient,
                            ProviderHttpClientFactory.postJson(
                                    baseUrl + CHAT_COMPLETIONS_PATH,
                                    apiKey,
                                    apiKeyHeader,
                                    body,
                                    requestTimeout));
            return toGatewayResponse(request, responseBody, false, request.renderForEstimation());
        } catch (java.io.IOException ex) {
            log.error("provider={} 非流式调用 IO 失败", providerId, ex);
            throw ProviderExceptionTranslator.wrap(-1, ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("provider={} 非流式调用被中断", providerId, ex);
            throw ProviderExceptionTranslator.wrap(504, "调用被中断", ex);
        }
    }

    @Override
    public GatewayResponse stream(GatewayRequest request) {
        // ★ 流式：强制开启 stream 与 include_usage。vLLM/OpenAI 不带 include_usage 就不回 usage，
        //   那正是 PRD 痛点 4「流式计量恒为 0」的根因。
        log.info(
                "OpenAI 兼容流式请求：provider={} model={} baseUrl={}",
                providerId,
                request.modelRef().modelId(),
                baseUrl);
        OpenAiWire.ChatRequest wire = toWireRequest(request, true);
        String body = serialize(wire);

        OpenAiStreamUsageCollector collector = new OpenAiStreamUsageCollector();
        long startedAt = System.nanoTime();

        try {
            ProviderHttpClientFactory.sendStream(
                    httpClient,
                    ProviderHttpClientFactory.postJson(
                            baseUrl + CHAT_COMPLETIONS_PATH,
                            apiKey,
                            apiKeyHeader,
                            body,
                            requestTimeout),
                    payload -> {
                        try {
                            collector.accept(
                                    OpenAiWire.parse(payload, OpenAiWire.StreamChunk.class));
                        } catch (RuntimeException ex) {
                            // 单个分片解析失败不应中断整条流：丢弃该分片但保留已收到的内容与 usage
                            return true;
                        }
                        return true;
                    });
        } catch (java.io.IOException ex) {
            log.error("provider={} 流式调用 IO 失败", providerId, ex);
            throw ProviderExceptionTranslator.wrap(-1, ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("provider={} 流式调用被中断", providerId, ex);
            throw ProviderExceptionTranslator.wrap(504, "流式调用被中断", ex);
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        NormalizeContext ctx =
                NormalizeContext.streaming(
                        request.renderForEstimation(), request.maxTokens(), true);
        Usage usage = collector.toUsage(normalizer, ctx, estimator);

        ModelRef ref = request.modelRef();
        String responseModel = collector.model();
        if (responseModel != null && !responseModel.isBlank()) {
            ref = new ModelRef(providerId, responseModel, ref.region());
        }

        return new GatewayResponse(
                collector.content(),
                List.of(collector.content()),
                usage,
                ref,
                collector.finishReason(),
                elapsedMs,
                false,
                1,
                toToolCalls(collector.toolCalls()));
    }

    /** 把统一请求转成 OpenAI wire 请求。 */
    OpenAiWire.ChatRequest toWireRequest(GatewayRequest request, boolean stream) {
        OpenAiWire.ChatRequest wire = new OpenAiWire.ChatRequest();
        wire.model = request.modelRef().modelId();
        wire.stream = stream;
        wire.maxTokens = request.maxTokens();
        wire.temperature = request.temperature();
        wire.topP = request.topP();

        wire.messages =
                request.messages().stream()
                        .map(
                                m ->
                                        new OpenAiWire.ChatRequest.Message(
                                                m.role().name().toLowerCase(java.util.Locale.ROOT),
                                                m.content()))
                        .toList();

        if (stream) {
            // ★ 无条件注入 include_usage —— 这是流式计量不为 0 的唯一保障
            wire.streamOptions = OpenAiWire.ChatRequest.StreamOptions.withUsage();
        }
        if (!request.tools().isEmpty()) {
            wire.tools =
                    request.tools().stream()
                            .map(
                                    t ->
                                            new OpenAiWire.ChatRequest.ToolDef(
                                                    t.name(), t.description(), t.parametersJson()))
                            .toList();
            wire.toolChoice = "auto";
        }
        if (request.responseFormat() != null && !request.responseFormat().isBlank()) {
            wire.responseFormat = OpenAiWire.ChatRequest.ResponseFormat.jsonObject();
        }
        return wire;
    }

    /** 解析非流式响应。 */
    GatewayResponse toGatewayResponse(
            GatewayRequest request, String responseBody, boolean streaming, String promptText) {
        OpenAiWire.ChatResponse response =
                OpenAiWire.parse(responseBody, OpenAiWire.ChatResponse.class);

        StringBuilder content = new StringBuilder();
        String finishReason = null;
        List<OpenAiWire.ChatResponse.ToolCall> toolCalls = List.of();
        if (response.choices != null && !response.choices.isEmpty()) {
            OpenAiWire.ChatResponse.Choice first = response.choices.get(0);
            if (first.message != null) {
                if (first.message.content != null) {
                    content.append(first.message.content);
                }
                // 工具调用在 message 内部（见 OpenAiWire.Message.toolCalls 的说明）
                if (first.message.toolCalls != null) {
                    toolCalls = first.message.toolCalls;
                }
            }
            finishReason = first.finishReason;
        }

        RawUsage raw = OpenAiStreamUsageCollector.toRawUsage(response.usage);
        NormalizeContext ctx = NormalizeContext.nonStreaming(request.maxTokens());
        // 非流式：usage 就在响应体里，streamUsageReported 恒为 false（core 内部处理）
        Usage usage = normalizer.normalize(raw, ctx, estimator);

        ModelRef ref = request.modelRef();
        if (response.model != null && !response.model.isBlank()) {
            ref = new ModelRef(providerId, response.model, ref.region());
        }

        return new GatewayResponse(
                content.toString(),
                List.of(),
                usage,
                ref,
                finishReason,
                0L,
                false,
                1,
                toToolCalls(toolCalls));
    }

    private List<GatewayResponse.ToolCall> toToolCalls(
            List<OpenAiWire.ChatResponse.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        return calls.stream()
                .map(
                        c ->
                                new GatewayResponse.ToolCall(
                                        c.id,
                                        c.function == null ? null : c.function.name,
                                        c.function == null ? null : c.function.arguments))
                .toList();
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("序列化请求体失败", ex);
        }
    }

    /** 供测试断言使用：确认流式请求确实带了 include_usage。 */
    public boolean streamIncludesUsage(GatewayRequest request) {
        return toWireRequest(request, true).streamOptions != null
                && Boolean.TRUE.equals(toWireRequest(request, true).streamOptions.includeUsage);
    }

    static String trimTrailingSlash(String url) {
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
