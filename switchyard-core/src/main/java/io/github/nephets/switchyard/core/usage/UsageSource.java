/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

/**
 * 计量来源（契约 USG-001 嵌套枚举）。
 *
 * <p>这个枚举回答的是「你报上来的这个数字，可信到什么程度」。企业财务场景下， 「这个数是供应商告诉我的」和「这是我猜的」必须能区分开，否则预算就是拿猜测在做决策。
 */
public enum UsageSource {

    /**
     * 供应商明确返回了 usage。
     *
     * <p>用于：非流式正常响应；流式且开启了 {@code stream_options.include_usage} 且收到末尾 usage chunk。
     * 这是唯一可以被财务直接采信的来源。
     */
    VENDOR_REPORTED,

    /**
     * 用估算器算出来的。
     *
     * <p>用于：流式但供应商没回 usage（此时 {@code streamUsageReported=false}）。 PRD 痛点 4 的核心场景 —— 修复前这类请求的账单恒为
     * 0，修复后至少是一个诚实标注的估算值。
     */
    ESTIMATED,

    /**
     * 兜底：按 {@code maxTokens} 上界计。
     *
     * <p>用于：连估算器也没配置（如 embedding 调用）。作为配额预扣的保守值， 保证「预留下界永远不小于实际可能消耗」。
     */
    FALLBACK
}
