/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.openai;

import io.github.nephets.switchyard.core.usage.NormalizeContext;
import io.github.nephets.switchyard.core.usage.RawUsage;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageNormalizer;

/**
 * 流式 usage 收集器：累积分片 + 捕获末尾 usage 块（PRD 痛点 4 的核心）。
 *
 * <p><b>问题是怎么发生的</b>：OpenAI / vLLM 在流式响应中，只有当请求带了 {@code stream_options.include_usage=true}
 * 时，才会在最后一个分片里附带 {@code usage}。 不带这个参数时，<b>整条流没有任何 usage 信息</b>，于是所有流式请求的账单被记成 0 token ——
 * 而且是<b>静默</b>的：没有报错，日志一切正常，月底对账才发现账单对不上。
 *
 * <p><b>本类的三件事</b>：
 *
 * <ol>
 *   <li>{@link OpenAiWire.ChatRequest.StreamOptions#withUsage()} —— 请求侧无条件注入 {@code
 *       include_usage}（由适配器负责，不是可选的）；
 *   <li>累积所有分片的文本与工具调用；
 *   <li>捕获末尾 usage 块；若没捕获到，明确把 {@code streamUsageReported=false} 暴露出去， 让「流式计量丢失」能被监控与告警，而不是继续静默。
 * </ol>
 */
public class OpenAiStreamUsageCollector {

    private final StringBuilder content = new StringBuilder();
    private final java.util.List<OpenAiWire.ChatResponse.ToolCall> toolCalls =
            new java.util.ArrayList<>();
    private RawUsage rawUsage;
    private boolean usageChunkReceived;
    private int chunkCount;
    private String model;
    private String finishReason;
    private String responseId;

    /**
     * 处理一个 SSE 分片。
     *
     * @param chunk 分片
     */
    public void accept(OpenAiWire.StreamChunk chunk) {
        if (chunk == null) {
            return;
        }
        chunkCount++;
        if (chunk.model != null) {
            this.model = chunk.model;
        }
        if (chunk.id != null) {
            this.responseId = chunk.id;
        }

        // ★ usage 块可能出现在任何分片（实践中在最后一个），见到就抓住
        if (chunk.usage != null) {
            this.rawUsage = toRawUsage(chunk.usage);
            this.usageChunkReceived = true;
        }

        if (chunk.choices != null) {
            for (OpenAiWire.StreamChoice choice : chunk.choices) {
                // ★ 流式增量在 delta 字段下（非流式才是 message）
                if (choice.delta != null && choice.delta.content != null) {
                    content.append(choice.delta.content);
                }
                if (choice.toolCalls != null) {
                    toolCalls.addAll(choice.toolCalls);
                }
                if (choice.finishReason != null) {
                    this.finishReason = choice.finishReason;
                }
            }
        }
    }

    /**
     * 收集结束后产出归一化结果。
     *
     * @param normalizer 归一化器
     * @param ctx 归一化上下文（流式标记与 usageChunkReceived 由本类提供真相）
     * @param estimator 估算器；{@code usageChunkReceived=false} 时的兜底
     * @return 归一化后的 Usage
     */
    public Usage toUsage(
            UsageNormalizer normalizer,
            NormalizeContext ctx,
            io.github.nephets.switchyard.core.usage.TokenEstimator estimator) {
        NormalizeContext effective =
                new NormalizeContext(
                        true,
                        ctx == null ? null : ctx.promptText(),
                        content.toString(),
                        ctx == null ? null : ctx.maxTokens(),
                        usageChunkReceived);
        return normalizer.normalize(rawUsage, effective, estimator);
    }

    /** 是否真的收到了 usage 块（写进 span 与审计的核心可观测字段）。 */
    public boolean usageChunkReceived() {
        return usageChunkReceived;
    }

    /** 累积的完整响应文本。 */
    public String content() {
        return content.toString();
    }

    /** 分片数量。 */
    public int chunkCount() {
        return chunkCount;
    }

    /** 响应模型。 */
    public String model() {
        return model;
    }

    /** 响应 ID。 */
    public String responseId() {
        return responseId;
    }

    /** 结束原因。 */
    public String finishReason() {
        return finishReason;
    }

    /** 工具调用。 */
    public java.util.List<OpenAiWire.ChatResponse.ToolCall> toolCalls() {
        return java.util.List.copyOf(toolCalls);
    }

    /** 原始 usage（可能为 null）。 */
    public RawUsage rawUsage() {
        return rawUsage;
    }

    /**
     * 把 wire usage 转成 core 的 {@link RawUsage}。
     *
     * <p>静态方法是为了让非流式路径也能复用同一套字段映射 —— 两条路径的映射逻辑必须一致，否则会出现「同步准、流式不准」这种最难查的问题。
     *
     * @param usage wire usage
     * @return RawUsage
     */
    public static RawUsage toRawUsage(OpenAiWire.Usage usage) {
        if (usage == null) {
            return RawUsage.empty();
        }
        Long cached =
                usage.promptTokensDetails == null ? null : usage.promptTokensDetails.cachedTokens;
        Long reasoning =
                usage.completionTokensDetails == null
                        ? null
                        : usage.completionTokensDetails.reasoningTokens;
        return RawUsage.openAi(
                usage.promptTokens, usage.completionTokens, cached, reasoning, usage.totalTokens);
    }
}
