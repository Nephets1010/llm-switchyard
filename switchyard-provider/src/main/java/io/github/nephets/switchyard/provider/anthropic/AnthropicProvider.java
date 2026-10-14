/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.anthropic;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Anthropic 原生协议适配器。
 *
 * <p><b>为什么不能复用 OpenAI 兼容适配器</b>：协议有三处结构性差异 （system 独立字段、缓存双字段、{@code x-api-key} 认证 + 必填 {@code
 * max_tokens}）， 硬套会在三处都出错，且错得很隐蔽 —— 比如 system 提示被当成 user 消息发出去， 模型会「听话」但语义完全跑偏。
 */
public class AnthropicProvider implements ChatProvider.StreamingChatProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private static final String MESSAGES_PATH = "/v1/messages";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final UsageNormalizer normalizer;
    private final TokenEstimator estimator;
    private final Duration requestTimeout;

    /**
     * 构造。
     *
     * @param baseUrl base-url
     * @param apiKey API Key
     * @param httpClient HTTP 客户端
     * @param normalizer 归一化器
     * @param estimator 估算器
     * @param requestTimeout 超时
     */
    public AnthropicProvider(
            String baseUrl,
            String apiKey,
            HttpClient httpClient,
            UsageNormalizer normalizer,
            TokenEstimator estimator,
            Duration requestTimeout) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.mapper = AnthropicWire.mapper();
        this.normalizer = normalizer;
        this.estimator = estimator;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(120) : requestTimeout;
    }

    @Override
    public String providerId() {
        return "anthropic";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                Set.of(
                        Capability.STREAM,
                        Capability.TOOL_CALL,
                        Capability.METERING,
                        Capability.REASONING,
                        Capability.CACHED_INPUT));
    }

    @Override
    public String name() {
        return "AnthropicProvider";
    }

    @Override
    public GatewayResponse complete(GatewayRequest request) {
        log.info("Anthropic 非流式请求：model={} baseUrl={}", request.modelRef().modelId(), baseUrl);
        AnthropicWire.MessagesRequest wire = toWireRequest(request, false);

        try {
            String responseBody =
                    ProviderHttpClientFactory.send(
                            httpClient,
                            ProviderHttpClientFactory.postJson(
                                    baseUrl + MESSAGES_PATH,
                                    apiKey,
                                    "x-api-key",
                                    serialize(wire),
                                    requestTimeout));
            AnthropicWire.MessagesResponse response =
                    AnthropicWire.parse(responseBody, AnthropicWire.MessagesResponse.class);

            StringBuilder content = new StringBuilder();
            List<GatewayResponse.ToolCall> toolCalls = new ArrayList<>();
            if (response.content != null) {
                for (AnthropicWire.MessagesResponse.ContentBlock block : response.content) {
                    if (block.type != null && "tool_use".equals(block.type)) {
                        toolCalls.add(
                                new GatewayResponse.ToolCall(
                                        block.id,
                                        block.name,
                                        block.input == null ? null : block.input.toString()));
                    } else if (block.text != null) {
                        content.append(block.text);
                    }
                }
            }

            RawUsage raw = AnthropicWire.toRawUsage(response.usage);
            Usage usage =
                    normalizer.normalize(
                            raw, NormalizeContext.nonStreaming(request.maxTokens()), estimator);

            ModelRef ref = new ModelRef("anthropic", response.model, request.modelRef().region());
            return new GatewayResponse(
                    content.toString(),
                    List.of(),
                    usage,
                    ref,
                    response.stopReason,
                    0L,
                    false,
                    1,
                    toolCalls);
        } catch (java.io.IOException ex) {
            log.error("Anthropic 非流式调用 IO 失败：model={}", request.modelRef().modelId(), ex);
            throw ProviderExceptionTranslator.wrap(-1, ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Anthropic 非流式调用被中断：model={}", request.modelRef().modelId(), ex);
            throw ProviderExceptionTranslator.wrap(504, "调用被中断", ex);
        }
    }

    @Override
    public GatewayResponse stream(GatewayRequest request) {
        log.info("Anthropic 流式请求：model={} baseUrl={}", request.modelRef().modelId(), baseUrl);
        AnthropicWire.MessagesRequest wire = toWireRequest(request, true);
        AnthropicStreamUsageCollector collector = new AnthropicStreamUsageCollector();
        long startedAt = System.nanoTime();

        try {
            ProviderHttpClientFactory.sendStream(
                    httpClient,
                    ProviderHttpClientFactory.postJson(
                            baseUrl + MESSAGES_PATH,
                            apiKey,
                            "x-api-key",
                            serialize(wire),
                            requestTimeout),
                    payload -> {
                        try {
                            collector.accept(
                                    AnthropicWire.parse(payload, AnthropicWire.StreamEvent.class));
                        } catch (RuntimeException ex) {
                            // 单个事件解析失败不中断整条流
                            return true;
                        }
                        return true;
                    });
        } catch (java.io.IOException ex) {
            log.error("Anthropic 流式调用 IO 失败：model={}", request.modelRef().modelId(), ex);
            throw ProviderExceptionTranslator.wrap(-1, ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Anthropic 流式调用被中断：model={}", request.modelRef().modelId(), ex);
            throw ProviderExceptionTranslator.wrap(504, "流式调用被中断", ex);
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        Usage usage =
                collector.toUsage(
                        normalizer,
                        NormalizeContext.streaming(
                                request.renderForEstimation(), request.maxTokens(), true),
                        estimator);

        String responseModel = collector.model();
        ModelRef ref =
                responseModel == null
                        ? request.modelRef()
                        : new ModelRef("anthropic", responseModel, request.modelRef().region());

        return new GatewayResponse(
                collector.content(),
                collector.chunks(),
                usage,
                ref,
                collector.stopReason(),
                elapsedMs,
                false,
                1,
                List.of());
    }

    /**
     * 转成 wire 请求。
     *
     * <p>★ 关键：把 {@code system} 消息<b>抽到顶层独立字段</b>， 这是 Anthropic 与 OpenAI 最容易踩错的协议差异。
     */
    AnthropicWire.MessagesRequest toWireRequest(GatewayRequest request, boolean stream) {
        AnthropicWire.MessagesRequest wire = new AnthropicWire.MessagesRequest();
        wire.model = request.modelRef().modelId();
        wire.stream = stream;
        wire.temperature = request.temperature();
        wire.topP = request.topP();
        // Anthropic 的 max_tokens 是必填字段，不像 OpenAI 可以省略
        wire.maxTokens = request.maxTokens() == null ? 4096 : request.maxTokens();

        StringBuilder system = new StringBuilder();
        List<AnthropicWire.MessagesRequest.Message> messages = new ArrayList<>();
        for (GatewayRequest.Message message : request.messages()) {
            if (message.role() == GatewayRequest.MessageRole.SYSTEM) {
                if (system.length() > 0) {
                    system.append('\n');
                }
                system.append(message.content());
            } else {
                // Anthropic 只接受 user / assistant 两种角色
                String role =
                        message.role() == GatewayRequest.MessageRole.ASSISTANT
                                ? "assistant"
                                : "user";
                messages.add(new AnthropicWire.MessagesRequest.Message(role, message.content()));
            }
        }
        if (system.length() > 0) {
            wire.system = system.toString();
        }
        wire.messages = messages;

        if (!request.tools().isEmpty()) {
            wire.tools =
                    request.tools().stream()
                            .map(
                                    t -> {
                                        AnthropicWire.MessagesRequest.Tool tool =
                                                new AnthropicWire.MessagesRequest.Tool();
                                        tool.name = t.name();
                                        tool.description = t.description();
                                        tool.inputSchema = parseSchema(t.parametersJson());
                                        return tool;
                                    })
                            .collect(Collectors.toList());
            wire.toolChoice = new AnthropicWire.MessagesRequest.ToolChoice("auto");
        }
        return wire;
    }

    private com.fasterxml.jackson.databind.JsonNode parseSchema(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("工具参数 JSON 非法", ex);
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("序列化请求体失败", ex);
        }
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
