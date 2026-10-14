/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.provider;

/**
 * 供应商能力（打勾表的数据源）。
 *
 * <p>这五个能力维度就是 README「供应商支持矩阵」表格的列定义。 表格里的每个格子都必须有对应的测试类作为证据链接 —— 架构验收标准 T05 第 4 条
 * 要求「每一格都有对应测试类名作为证据链接」，否则矩阵就是口头承诺。
 */
public enum Capability {

    /** 支持流式响应（SSE）。 */
    STREAM,

    /** 支持结构化输出（JSON Schema / {@code response_format}）。 */
    STRUCTURED_OUTPUT,

    /** 支持工具调用（function calling / tool use）。 */
    TOOL_CALL,

    /** 返回可被精确计量的 usage 块。 */
    METERING,

    /**
     * 返回推理/思考 token。
     *
     * <p>注意：绝大多数 OpenAI 兼容端点<b>不</b>返回 reasoning token（DeepSeek 与 GLM 的 thinking
     * 模式才返回），所以这个能力位通常是关的， 此时 {@code reasoningTokens} 恒为 0 且 {@code reasoningEstimated=true}。
     */
    REASONING,

    /** 返回缓存命中的输入 token 数（可支撑缓存折扣计价）。 */
    CACHED_INPUT
}
