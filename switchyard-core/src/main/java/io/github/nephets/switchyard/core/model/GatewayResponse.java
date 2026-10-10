/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.model;

import io.github.nephets.switchyard.core.usage.Usage;

import java.util.List;

/**
 * 网关统一的响应模型。
 *
 * @param content 完整响应文本
 * @param chunks 流式下的分片内容；非流式时为空
 * @param usage 归一化后的计量（已完成 7 步算法）
 * @param modelRef 实际响应模型（可能与请求模型不同：供应商可能做静默别名映射）
 * @param finishReason 结束原因
 * @param latencyMillis 端到端耗时
 * @param degraded 是否经过降级才成功
 * @param attempts 总尝试次数（含重试）
 * @param toolCalls 工具调用请求；无则为空
 */
public record GatewayResponse(
        String content,
        List<String> chunks,
        Usage usage,
        ModelRef modelRef,
        String finishReason,
        long latencyMillis,
        boolean degraded,
        int attempts,
        List<ToolCall> toolCalls) {

    /** 紧凑构造器：填充默认值。 */
    public GatewayResponse {
        content = content == null ? "" : content;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        attempts = attempts <= 0 ? 1 : attempts;
        if (usage == null) {
            usage =
                    Usage.estimated(
                            io.github.nephets.switchyard.core.usage.UsageSource.ESTIMATED, false);
        }
        if (modelRef == null) {
            throw new IllegalArgumentException("modelRef 不可为 null");
        }
    }

    /** 非流式成功响应。 */
    public static GatewayResponse of(
            String content, Usage usage, ModelRef ref, String finishReason, long latencyMs) {
        return new GatewayResponse(
                content, List.of(), usage, ref, finishReason, latencyMs, false, 1, List.of());
    }

    /** 流式成功响应。 */
    public static GatewayResponse streamed(
            List<String> chunks, Usage usage, ModelRef ref, String finishReason, long latencyMs) {
        String joined = chunks == null ? "" : String.join("", chunks);
        return new GatewayResponse(
                joined, chunks, usage, ref, finishReason, latencyMs, false, 1, List.of());
    }

    /** 返回一个标记了「已降级」的副本。 */
    public GatewayResponse asDegraded(int attempts) {
        return new GatewayResponse(
                content,
                chunks,
                usage,
                modelRef,
                finishReason,
                latencyMillis,
                true,
                attempts,
                toolCalls);
    }

    /**
     * 返回一个替换了 usage 的副本。
     *
     * <p>starter 层用它把「provider 已归一化的 usage」换成「网关最终确认的 usage」， 保证下游（成本、审计、响应头、业务侧看到的
     * ChatResponse）看到的是同一份数据 —— 计量口径出现两个版本是对账灾难的起点。
     *
     * @param newUsage 新 usage
     * @return 新的响应对象
     */
    public GatewayResponse withUsage(Usage newUsage) {
        return new GatewayResponse(
                content,
                chunks,
                newUsage,
                modelRef,
                finishReason,
                latencyMillis,
                degraded,
                attempts,
                toolCalls);
    }

    /**
     * 返回一个替换了「实际服务模型」的副本。
     *
     * <p>★ 为什么需要它：发生降级时，provider 返回的 {@code modelRef} 仍是<b>请求时</b>的模型 （provider
     * 只知道自己被要求调什么），但真正回答问题的是降级链上的另一个模型。 若不覆盖，业务方从 {@code ChatResponse.getMetadata().getModel()}
     * 读到的会是错的模型名 —— 于是「这次到底是谁答的」这个问题，在最需要追责的降级场景下反而答不出来。
     *
     * @param actualModel 实际服务的模型
     * @param isDegraded 是否发生过降级
     * @return 新的响应对象
     */
    public GatewayResponse withModel(ModelRef actualModel, boolean isDegraded) {
        return new GatewayResponse(
                content,
                chunks,
                usage,
                actualModel,
                finishReason,
                latencyMillis,
                isDegraded,
                attempts,
                toolCalls);
    }

    /** 是否有工具调用。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 工具调用请求。
     *
     * @param id 调用 ID
     * @param name 工具名
     * @param argumentsJson 参数 JSON
     */
    public record ToolCall(String id, String name, String argumentsJson) {}
}
