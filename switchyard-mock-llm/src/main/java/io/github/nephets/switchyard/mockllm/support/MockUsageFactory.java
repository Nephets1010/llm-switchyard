/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm.support;

/**
 * 可预测的 usage 工厂。
 *
 * <p><b>为什么「可预测」比「真实」更重要</b>：mock 若返回随机 token 数，测试就只能写 {@code assertThat(total).isGreaterThan(0)}
 * 这种弱断言 —— 它无法区分 「正确解析了 cached_tokens」和「随手给了个正数」。 可预测的数值让测试可以写 {@code
 * assertThat(cachedInputTokens).isEqualTo(400L)}， 真正锁住字段映射。
 *
 * <p>算法：输入 token 按 prompt 长度推导并对齐到 4 的倍数（让 cached 比例是整数）， 输出 token 按固定基数 + 内容长度推导。这样同一个 prompt
 * 永远得到同一个 usage。
 */
public final class MockUsageFactory {

    private MockUsageFactory() {
        // 工具类不允许实例化
    }

    /**
     * 依据 prompt 推导输入 token。
     *
     * @param prompt 渲染后的 prompt
     * @return 输入 token（4 的倍数，便于推导缓存量）
     */
    public static long inputTokens(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return 16L;
        }
        // 中文约 1 字 1 token，ASCII 约 4 字符 1 token；再向上对齐到 4 的倍数
        long cjk = prompt.codePoints().filter(MockUsageFactory::isCjk).count();
        long ascii = prompt.length() - cjk;
        long estimate = cjk + ascii / 4;
        return Math.max(16L, ((estimate + 3) / 4) * 4);
    }

    /**
     * 依据响应内容推导输出 token。
     *
     * @param content 响应内容
     * @return 输出 token
     */
    public static long outputTokens(String content) {
        if (content == null || content.isBlank()) {
            return 8L;
        }
        long cjk = content.codePoints().filter(MockUsageFactory::isCjk).count();
        long ascii = content.length() - cjk;
        return Math.max(8L, cjk + ascii / 4);
    }

    /**
     * 推导缓存命中 token。
     *
     * <p>固定按输入的 40%（向上对齐到 4 的倍数）返回，让「缓存不重复计费」这条断言有确定期望值。
     *
     * @param inputTokens 输入 token
     * @return 缓存命中 token
     */
    public static long cachedInputTokens(long inputTokens) {
        return (inputTokens * 2) / 5 / 4 * 4;
    }

    /**
     * 推导推理 token。
     *
     * @param outputTokens 输出 token
     * @return 推理 token（输出量的 1/4）
     */
    public static long reasoningTokens(long outputTokens) {
        return outputTokens / 4;
    }

    /**
     * 构造确定性的回答文本。
     *
     * @param prompt 用户输入
     * @param model 模型名
     * @return 回答文本
     */
    public static String reply(String prompt, String model) {
        String topic = (prompt == null || prompt.isBlank()) ? "你的问题" : truncate(prompt.trim(), 20);
        return "[" + model + "] 关于「" + topic + "」：这是一段用于计量测试的确定性响应，长度固定以便断言。";
    }

    /**
     * 把一段文本切成固定数量的分片（用于流式）。
     *
     * @param content 完整内容
     * @param chunkCount 分片数
     * @return 分片列表
     */
    public static java.util.List<String> split(String content, int chunkCount) {
        java.util.List<String> chunks = new java.util.ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }
        int size = Math.max(1, (int) Math.ceil((double) content.length() / chunkCount));
        for (int i = 0; i < content.length(); i += size) {
            chunks.add(content.substring(i, Math.min(content.length(), i + size)));
        }
        return chunks;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
