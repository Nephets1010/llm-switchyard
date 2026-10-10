/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.anthropic;

import io.github.nephets.switchyard.core.usage.NormalizeContext;
import io.github.nephets.switchyard.core.usage.RawUsage;
import io.github.nephets.switchyard.core.usage.TokenEstimator;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic 流式 usage 收集器。
 *
 * <p><b>Anthropic 流式的特殊性</b>：它的 usage 分两处到达 —— {@code message_start} 里带一份「输入侧」usage（input_tokens /
 * cache_*）， {@code message_delta} 里带一份「输出侧」usage（output_tokens）。 只读其中一处就会得到一个半残的计量值。
 *
 * <p>另一个关键点：Anthropic <b>无条件</b>返回 usage（不像 OpenAI 需要 {@code include_usage}），所以本适配器不存在「流式计量丢失」的问题；
 * 但仍然要把 {@code streamUsageReported} 如实置位，供跨供应商的统一可观测口径使用。
 */
public class AnthropicStreamUsageCollector {

    private final StringBuilder content = new StringBuilder();
    private final List<String> chunks = new ArrayList<>();
    private RawUsage rawUsage;
    private boolean usageReported;
    private String model;
    private String stopReason;
    private String messageId;
    private int chunkCount;

    /**
     * 处理一个流式事件。
     *
     * @param event 事件
     */
    public void accept(AnthropicWire.StreamEvent event) {
        if (event == null) {
            return;
        }
        chunkCount++;

        switch (event.type == null ? "" : event.type) {
            case "message_start" -> {
                if (event.message != null) {
                    model = event.message.model;
                    messageId = event.message.id;
                    if (event.message.usage != null) {
                        // 第一份 usage：输入侧
                        rawUsage = AnthropicWire.toRawUsage(event.message.usage);
                        usageReported = true;
                    }
                }
            }
            case "content_block_delta" -> {
                if (event.delta != null && event.delta.text != null) {
                    content.append(event.delta.text);
                    chunks.add(event.delta.text);
                }
            }
            case "message_delta" -> {
                if (event.delta != null && event.delta.stopReason != null) {
                    stopReason = event.delta.stopReason;
                }
                if (event.usage != null) {
                    // 第二份 usage：输出侧 —— 必须与第一份合并，否则只剩半边
                    merge(event.usage);
                }
            }
            case "message_stop" -> {
                // 无需处理：状态已由前面的事件记录
            }
            default -> {
                // ping 等其他事件类型忽略
            }
        }
    }

    /**
     * 合并第二份 usage。
     *
     * <p><b>覆盖方向：后到的 message_delta 优先</b>。这是 Anthropic 流式的语义： {@code message_start} 里的 {@code
     * output_tokens} 是个占位值（典型为 1）， {@code message_delta} 里的才是真实输出量。若写成「先到者优先」， 每次流式调用的输出 token
     * 都会被记成 1 —— 成本统计将<b>系统性偏低</b>， 而且因为数字看起来「有值」，极难被发现。
     *
     * @param usage 第二份 usage
     */
    private void merge(AnthropicWire.Usage usage) {
        if (usage == null) {
            return;
        }
        if (rawUsage == null) {
            rawUsage = AnthropicWire.toRawUsage(usage);
            usageReported = true;
            return;
        }
        rawUsage =
                new RawUsage(
                        usage.inputTokens != null ? usage.inputTokens : rawUsage.promptTokens(),
                        usage.outputTokens != null
                                ? usage.outputTokens
                                : rawUsage.completionTokens(),
                        usage.cacheReadInputTokens != null
                                ? usage.cacheReadInputTokens
                                : rawUsage.cachedPromptTokens(),
                        rawUsage.reasoningTokens(),
                        rawUsage.promptEvalCount(),
                        rawUsage.evalCount(),
                        usage.cacheCreationInputTokens != null
                                ? usage.cacheCreationInputTokens
                                : rawUsage.cacheCreationInputTokens(),
                        usage.cacheReadInputTokens != null
                                ? usage.cacheReadInputTokens
                                : rawUsage.cacheReadInputTokens(),
                        rawUsage.vendorTotalTokens());
        usageReported = true;
    }

    /**
     * 产出归一化结果。
     *
     * @param normalizer 归一化器
     * @param ctx 上下文
     * @param estimator 估算器
     * @return 归一化后的 Usage
     */
    public Usage toUsage(
            UsageNormalizer normalizer, NormalizeContext ctx, TokenEstimator estimator) {
        NormalizeContext effective =
                new NormalizeContext(
                        true,
                        ctx == null ? null : ctx.promptText(),
                        content.toString(),
                        ctx == null ? null : ctx.maxTokens(),
                        usageReported);
        return normalizer.normalize(rawUsage, effective, estimator);
    }

    /** 是否收到了 usage。Anthropic 下通常恒为 true。 */
    public boolean usageReported() {
        return usageReported;
    }

    /** 累积的完整文本。 */
    public String content() {
        return content.toString();
    }

    /** 分片列表。 */
    public List<String> chunks() {
        return List.copyOf(chunks);
    }

    /** 分片数。 */
    public int chunkCount() {
        return chunkCount;
    }

    /** 响应模型。 */
    public String model() {
        return model;
    }

    /** 消息 ID。 */
    public String messageId() {
        return messageId;
    }

    /** 结束原因。 */
    public String stopReason() {
        return stopReason;
    }
}
