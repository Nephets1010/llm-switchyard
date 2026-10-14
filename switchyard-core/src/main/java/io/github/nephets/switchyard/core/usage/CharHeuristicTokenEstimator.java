/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

/**
 * 字符启发式 Token 估算器（默认实现）。
 *
 * <p>启发式规则：
 *
 * <ul>
 *   <li><b>中文及 CJK 字符</b>：约 1 字 ≈ 1 token。国内主流模型的 BPE 词表对中文的压缩率普遍在 0.6~1.2 token/字之间，取 1.0 是保守中值；
 *   <li><b>ASCII 文本</b>：约 4 字符 ≈ 1 token。GPT 系列的 BPE 对英文的典型压缩比在 3.5~4.5 之间；
 *   <li><b>数字串</b>：按 1~3 位一个 token 切，这是 BPE 的典型行为（数字会被拆成更小的片段）。
 * </ul>
 *
 * <p>⚠️ <b>这套启发式的误差不可忽视</b>，本类存在的意义不是「精确」，而是「不再为 0」。 PRD 痛点 4 说的就是修复前流式请求账单恒为 0 的静默失真 —— 一个标注了
 * {@code ESTIMATED} 的估算值，比一个没有争议的 0 更有价值，因为它可以被监控、被告警、被纠正。
 *
 * <p>要更准就替换实现（实现 {@link TokenEstimator} 接 tiktoken），计量链路无需改动。
 */
public class CharHeuristicTokenEstimator implements TokenEstimator {

    /** 中文等全角字符每个折算的 token 数。 */
    public static final double DEFAULT_CJK_PER_TOKEN = 1.0d;

    /** ASCII 字符每 token 对应的字符数。 */
    public static final double DEFAULT_ASCII_CHARS_PER_TOKEN = 4.0d;

    private final double cjkPerToken;
    private final double asciiCharsPerToken;

    /** 用默认参数构造（1 CJK 字 ≈ 1 token，4 ASCII 字符 ≈ 1 token）。 */
    public CharHeuristicTokenEstimator() {
        this(DEFAULT_CJK_PER_TOKEN, DEFAULT_ASCII_CHARS_PER_TOKEN);
    }

    /**
     * 用自定义参数构造。
     *
     * @param cjkPerToken 一个 CJK 字符折算几个 token
     * @param asciiCharsPerToken 一个 token 对应几个 ASCII 字符
     * @throws IllegalArgumentException 参数非正时
     */
    public CharHeuristicTokenEstimator(double cjkPerToken, double asciiCharsPerToken) {
        if (cjkPerToken <= 0 || asciiCharsPerToken <= 0) {
            throw new IllegalArgumentException("估算参数必须为正数");
        }
        this.cjkPerToken = cjkPerToken;
        this.asciiCharsPerToken = asciiCharsPerToken;
    }

    @Override
    public long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long cjkCount = 0L;
        long asciiCount = 0L;
        long digitRun = 0L;
        long digitTokens = 0L;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            i += charCount;

            if (isCjk(codePoint)) {
                cjkCount++;
                if (digitRun > 0) {
                    digitTokens += ceilDiv(digitRun, 3);
                    digitRun = 0;
                }
            } else if (codePoint < 128) {
                if (codePoint >= '0' && codePoint <= '9') {
                    digitRun++;
                } else {
                    if (digitRun > 0) {
                        digitTokens += ceilDiv(digitRun, 3);
                        digitRun = 0;
                    }
                    asciiCount++;
                }
            } else {
                // 其他非 ASCII（emoji、俄文、韩文等）按 2 字符 ≈ 1 token 折算
                asciiCount += 2;
                if (digitRun > 0) {
                    digitTokens += ceilDiv(digitRun, 3);
                    digitRun = 0;
                }
            }
        }
        if (digitRun > 0) {
            digitTokens += ceilDiv(digitRun, 3);
        }

        double total = cjkCount * cjkPerToken + asciiCount / asciiCharsPerToken + digitTokens;
        // 向上取整：估算宁可略高也不要低估，否则配额会被穿透
        return Math.max(1L, (long) Math.ceil(total));
    }

    /** 中文、CJK 统一表意文字、日文假名、韩文音节都按「1 字 1 token」处理。 */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) // CJK 统一表意文字
                || (cp >= 0x3400 && cp <= 0x4DBF) // 扩展 A
                || (cp >= 0xF900 && cp <= 0xFAFF) // 兼容表意文字
                || (cp >= 0x3040 && cp <= 0x30FF) // 日文假名
                || (cp >= 0xAC00 && cp <= 0xD7AF) // 韩文音节
                || (cp >= 0x20000 && cp <= 0x2A6DF); // 扩展 B 起始
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
