/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

/**
 * 归一化上下文：把「上游 usage 之外的信息」喂给归一化器。
 *
 * <p>归一化算法需要这些输入才能在字段缺失时做正确的兜底：
 *
 * <ul>
 *   <li>{@code streaming} —— 决定 {@code streamUsageReported} 怎么算，以及没有 usage 时能否用文本估算；
 *   <li>{@code promptText} / {@code completionText} —— 估算器的输入；
 *   <li>{@code maxTokens} —— 最后的兜底上界（{@link UsageSource#FALLBACK}）。
 * </ul>
 *
 * @param streaming 本次请求是否为流式
 * @param promptText 渲染后的完整 prompt（用于估算），可为 null
 * @param completionText 完整响应文本（用于估算），可为 null
 * @param maxTokens 请求声明的 maxTokens，估算链路的最后兜底；可为 null
 * @param usageChunkReceived 流式场景下是否真的收到了末尾 usage 块。非流式恒为 true（响应体里就带 usage）
 */
public record NormalizeContext(
        boolean streaming,
        String promptText,
        String completionText,
        Integer maxTokens,
        boolean usageChunkReceived) {

    /** 非流式、无上下文兜底的默认上下文。 */
    public static NormalizeContext nonStreaming(Integer maxTokens) {
        return new NormalizeContext(false, null, null, maxTokens, true);
    }

    /**
     * 流式上下文。
     *
     * @param promptText 渲染后的 prompt
     * @param maxTokens maxTokens
     * @param usageChunkReceived 是否真收到 usage 块
     * @return NormalizeContext
     */
    public static NormalizeContext streaming(
            String promptText, Integer maxTokens, boolean usageChunkReceived) {
        return new NormalizeContext(true, promptText, null, maxTokens, usageChunkReceived);
    }

    /**
     * 流式上下文（同时提供响应文本，用于按实际输出估算）。
     *
     * @param promptText prompt 文本
     * @param completionText 响应文本
     * @param maxTokens maxTokens
     * @param usageChunkReceived 是否真收到 usage 块
     * @return NormalizeContext
     */
    public static NormalizeContext streaming(
            String promptText,
            String completionText,
            Integer maxTokens,
            boolean usageChunkReceived) {
        return new NormalizeContext(
                true, promptText, completionText, maxTokens, usageChunkReceived);
    }
}
