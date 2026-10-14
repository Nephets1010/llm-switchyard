/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * provider 层共用的 HTTP 客户端工厂。
 *
 * <p>用 JDK 内置 {@link HttpClient} 而非 Spring 的 RestClient/WebClient，理由有三：
 *
 * <ul>
 *   <li><b>零框架依赖</b>：provider 的 wire 解析不被 Spring 的版本升级节奏绑架（ADR-0006）；
 *   <li><b>完整控制 SSE</b>：{@code HttpResponse.BodyHandlers.ofLines()} 直接给出行， 而 Spring 的 WebClient
 *       需要额外的 decoder 配置才能拿到裸 SSE 行；
 *   <li><b>体积</b>：嵌入式网关的卖点之一是「不显著增加镜像体积」，少一个 HTTP 客户端是一份实在收益。
 * </ul>
 */
public final class ProviderHttpClientFactory {

    private ProviderHttpClientFactory() {
        // 工具类不允许实例化
    }

    /**
     * 创建 HTTP 客户端。
     *
     * @param connectTimeout 连接超时
     * @param proxy 可选代理；为 null 时直连
     * @return HTTP 客户端
     */
    public static HttpClient create(Duration connectTimeout, java.net.ProxySelector proxy) {
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder.build();
    }

    /**
     * 构建 POST 请求。
     *
     * @param url 完整 URL
     * @param apiKey API Key；可为 null（本地 Ollama 不需要）
     * @param apiKeyHeader API Key 的头名（OpenAI 系用 {@code Authorization}，Anthropic 用 {@code
     *     x-api-key}）
     * @param body 请求体 JSON
     * @param timeout 请求超时
     * @return HttpRequest
     */
    public static HttpRequest postJson(
            String url, String apiKey, String apiKeyHeader, String body, Duration timeout) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (apiKey != null && !apiKey.isBlank() && apiKeyHeader != null) {
            // Anthropic 用 x-api-key + anthropic-version，OpenAI 系用 Authorization: Bearer
            if ("Authorization".equalsIgnoreCase(apiKeyHeader)) {
                builder.header(apiKeyHeader, "Bearer " + apiKey);
            } else {
                builder.header(apiKeyHeader, apiKey);
                // Anthropic 强制要求版本头，缺失会直接 400
                builder.header("anthropic-version", ANTHROPIC_VERSION);
            }
        }
        return builder.build();
    }

    /** Anthropic 必需的版本头。 */
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * 发送非流式请求。
     *
     * @param client HTTP 客户端
     * @param request 请求
     * @return 响应体文本
     * @throws IOException 传输失败
     * @throws InterruptedException 被中断
     */
    public static String send(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body();
        if (response.statusCode() >= 400) {
            throw ProviderExceptionTranslator.wrap(response.statusCode(), truncate(body));
        }
        return body;
    }

    /**
     * 发送流式请求并逐行回调数据载荷。
     *
     * <p><b>同时兼容两种流式格式</b>（这是一个必须显式处理的协议差异）：
     *
     * <ul>
     *   <li><b>SSE</b>（OpenAI / Anthropic）：每行形如 {@code data: {...}}，以 {@code [DONE]} 结束；
     *   <li><b>ndjson</b>（Ollama）：每行就是一个裸 JSON 对象，<b>没有 {@code data:} 前缀</b>， 以 {@code done:true}
     *       的分片结束。
     * </ul>
     *
     * <p>若按 SSE 单一格式解析 Ollama 的流，会得到<b>空响应</b>且<b>不报错</b> ——
     * 这类「静默错误」正是本项目最需要防的。统一在这里做格式识别，调用方无需各自实现。
     *
     * @param client HTTP 客户端
     * @param request 请求
     * @param lineConsumer 每收到一个数据行回调一次；返回 false 表示提前终止
     * @throws IOException 传输失败
     * @throws InterruptedException 被中断
     */
    public static void sendStream(HttpClient client, HttpRequest request, LineConsumer lineConsumer)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            try (InputStream errorStream = response.body()) {
                throw ProviderExceptionTranslator.wrap(
                        response.statusCode(), truncate(readAll(errorStream)));
            }
        }
        try (InputStream in = response.body();
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("data:")) {
                    // SSE 分支
                    String payload = trimmed.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        return;
                    }
                    if (!lineConsumer.accept(payload)) {
                        return;
                    }
                } else if (trimmed.startsWith("{")) {
                    // ndjson 分支（Ollama）：裸 JSON 直接透传
                    if (!lineConsumer.accept(trimmed)) {
                        return;
                    }
                }
                // 其他 SSE 字段（event / id / retry / 注释行）对本项目无意义，忽略
            }
        }
    }

    /** SSE 行消费者。 @FunctionalInterface */
    @FunctionalInterface
    public interface LineConsumer {
        /**
         * 处理一行 SSE 数据。
         *
         * @param payload {@code data:} 之后的内容
         * @return 返回 false 可提前终止流
         * @throws IOException 消费过程中出错
         */
        boolean accept(String payload) throws IOException;
    }

    /** 便捷回调适配：忽略返回值。 */
    public static void forEachLine(
            HttpClient client, HttpRequest request, Consumer<String> consumer)
            throws IOException, InterruptedException {
        sendStream(
                client,
                request,
                payload -> {
                    consumer.accept(payload);
                    return true;
                });
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
}
