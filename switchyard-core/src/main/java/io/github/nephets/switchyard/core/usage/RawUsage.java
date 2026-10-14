/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

/**
 * 各供应商 usage 结构的并集（宽松解析）。
 *
 * <p>刻意用「一个大 record 装所有供应商的字段」而不是每家一个 DTO：
 *
 * <ul>
 *   <li><b>单一归一化算法</b>：USG-001 要求三仓用同一套逻辑。若每家一个 DTO，归一化就得写 6 遍， 6
 *       遍实现必然出现口径漂移，而口径漂移正是「与供应商账单对不上」的根因；
 *   <li><b>宽松解析友好</b>：所有字段都是装箱类型，{@code null} 天然表达「该供应商没这个字段」， 于是「某字段缺失 → 走估算」这条规则可以用统一的 {@code
 *       null} 判断表达。
 * </ul>
 *
 * <p>⚠️ 这里的字段名必须与供应商 HTTP 响应的 JSON key 逐字符对应（{@code promptTokens} ↔ {@code prompt_tokens} 等），因为
 * provider 层会用 Jackson 的 snake_case 策略映射进来。
 *
 * @param promptTokens 输入 token，OpenAI {@code prompt_tokens} / Anthropic 与 DashScope 的 {@code
 *     input_tokens}
 * @param completionTokens 输出 token，OpenAI {@code completion_tokens} / Anthropic 与 DashScope 的
 *     {@code output_tokens}
 * @param cachedPromptTokens 缓存命中的输入 token，OpenAI {@code prompt_tokens_details.cached_tokens}
 * @param reasoningTokens 推理 token，DeepSeek {@code completion_tokens_details.reasoning_tokens}
 * @param promptEvalCount Ollama {@code prompt_eval_count}
 * @param evalCount Ollama {@code eval_count}
 * @param cacheCreationInputTokens Anthropic {@code cache_creation_input_tokens}
 * @param cacheReadInputTokens Anthropic {@code cache_read_input_tokens}
 * @param vendorTotalTokens <b>上游报的 total，仅用于测试断言「我们没有拷贝它」</b>，计量链路不使用
 */
public record RawUsage(
        Long promptTokens,
        Long completionTokens,
        Long cachedPromptTokens,
        Long reasoningTokens,
        Long promptEvalCount,
        Long evalCount,
        Long cacheCreationInputTokens,
        Long cacheReadInputTokens,
        Long vendorTotalTokens) {

    /** 空的上游 usage（流式末尾没有 usage chunk 时）。 */
    public static RawUsage empty() {
        return new RawUsage(null, null, null, null, null, null, null, null, null);
    }

    /**
     * OpenAI / 通义 / 智谱 / DeepSeek / Kimi / 豆包 的标准形态。
     *
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @param cachedPromptTokens 缓存命中输入 token，可为 null
     * @param reasoningTokens 推理 token，可为 null
     * @param vendorTotalTokens 上游报的 total
     * @return RawUsage
     */
    public static RawUsage openAi(
            Long promptTokens,
            Long completionTokens,
            Long cachedPromptTokens,
            Long reasoningTokens,
            Long vendorTotalTokens) {
        return new RawUsage(
                promptTokens,
                completionTokens,
                cachedPromptTokens,
                reasoningTokens,
                null,
                null,
                null,
                null,
                vendorTotalTokens);
    }

    /**
     * Anthropic 形态。
     *
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @param cacheCreationInputTokens 缓存写入 token
     * @param cacheReadInputTokens 缓存读取 token
     * @return RawUsage
     */
    public static RawUsage anthropic(
            Long inputTokens,
            Long outputTokens,
            Long cacheCreationInputTokens,
            Long cacheReadInputTokens) {
        return new RawUsage(
                inputTokens,
                outputTokens,
                cacheReadInputTokens,
                null,
                null,
                null,
                cacheCreationInputTokens,
                cacheReadInputTokens,
                null);
    }

    /** Ollama 形态。 */
    public static RawUsage ollama(Long promptEvalCount, Long evalCount) {
        return new RawUsage(null, null, null, null, promptEvalCount, evalCount, null, null, null);
    }

    /**
     * 上游是否完全没有返回任何 token 信息。
     *
     * <p>归一化算法第 6 步的依据：{@code usageSource = raw 全部缺失 ? ESTIMATED : VENDOR_REPORTED}。
     *
     * @return 无任何 token 字段时返回 true
     */
    public boolean isCompletelyMissing() {
        return promptTokens == null
                && completionTokens == null
                && cachedPromptTokens == null
                && reasoningTokens == null
                && promptEvalCount == null
                && evalCount == null
                && cacheCreationInputTokens == null
                && cacheReadInputTokens == null;
    }
}
