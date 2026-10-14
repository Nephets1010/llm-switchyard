/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.trace;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.random.RandomGenerator;

/**
 * W3C TraceContext 的编解码器（契约 CTX-001）。
 *
 * <p><b>一致性硬约束</b>：三个仓库必须提供本类，且对同一输入产生<b>字节级相同</b>的 {@code traceparent}。由 {@code
 * ContractSchemaTest} 守住 —— 这是「不共享 jar 也能共享语义」的关键。
 *
 * <p>{@code traceparent} 格式固定为 {@code 00-{traceId}-{spanId}-{traceFlags}}，版本号固定 {@code 00}。
 *
 * <p>⚠️ 解码策略是<b>宽容但安全</b>：畸形输入不抛异常，而是返回空 Optional。 理由是 {@code traceparent} 来自不可信的 HTTP
 * 头，一个坏头不应该让整个请求 500； 但同时不能"尽力修复"成一个新的 traceId —— 那样会造成错误归因，比丢失更危险。
 */
public final class W3CTraceCodec {

    /** {@code traceparent} 的 HTTP 头名。 */
    public static final String TRACEPARENT = "traceparent";

    /** {@code tracestate} 的 HTTP 头名。 */
    public static final String TRACESTATE = "tracestate";

    /** {@code baggage} 的 HTTP 头名。 */
    public static final String BAGGAGE = "baggage";

    /** {@code tracestate} 允许的最大 list-member 数量（W3C 规范上限）。 */
    public static final int MAX_TRACESTATE_MEMBERS = 32;

    /** 版本号，契约要求固定。 */
    public static final String VERSION = "00";

    /** W3C 规范明确规定的非法版本号。 */
    public static final String INVALID_VERSION = "ff";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private W3CTraceCodec() {
        // 工具类不允许实例化
    }

    /**
     * 编码 {@code traceparent}。
     *
     * @param ctx 上下文
     * @return 形如 {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}
     */
    public static String encodeTraceparent(TraceContext ctx) {
        return VERSION + "-" + ctx.traceId() + "-" + ctx.spanId() + "-" + ctx.traceFlags();
    }

    /**
     * 解码 {@code traceparent}。
     *
     * @param traceparent 头值，可为 null
     * @return 解析成功返回上下文；为空或畸形返回 {@link java.util.Optional#empty()}
     */
    public static java.util.Optional<TraceContext> decodeTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return java.util.Optional.empty();
        }
        String value = traceparent.trim();
        // 允许 version(2) + "-" + traceId(32) + "-" + spanId(16) + "-" + flags(2)
        if (value.length() < 55) {
            return java.util.Optional.empty();
        }
        if (value.charAt(2) != '-' || value.charAt(35) != '-' || value.charAt(52) != '-') {
            return java.util.Optional.empty();
        }
        String version = value.substring(0, 2);
        // W3C 规范：ff 是非法版本；其他未知版本按「向前兼容」宽松解析（丢弃额外字段）
        if (INVALID_VERSION.equals(version)) {
            return java.util.Optional.empty();
        }
        String traceId = value.substring(3, 35);
        String spanId = value.substring(36, 52);
        String flags = value.substring(53, 55);
        // 全零 traceId / spanId 按 W3C 规范视为非法
        if (isAllZeros(traceId) || isAllZeros(spanId)) {
            return java.util.Optional.empty();
        }
        if (!TraceContext.FLAGS_SAMPLED.equals(flags)
                && !TraceContext.FLAGS_NOT_SAMPLED.equals(flags)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new TraceContext(traceId, spanId, null, flags, null));
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    /**
     * 编码 {@code tracestate}。
     *
     * @param traceState 原始 tracestate 值，可为 null
     * @return 超过 32 个 list-member 时截断；null 或空返回 null
     */
    public static String encodeTracestate(String traceState) {
        if (traceState == null || traceState.isBlank()) {
            return null;
        }
        String[] members = traceState.split(",");
        if (members.length <= MAX_TRACESTATE_MEMBERS) {
            return traceState;
        }
        return String.join(",", java.util.Arrays.copyOf(members, MAX_TRACESTATE_MEMBERS));
    }

    /**
     * 编码 {@code baggage}，只透传 {@link BaggageKeys} 白名单内的键。
     *
     * @param baggage 业务维度键值对
     * @return {@code k1=v1;k2=v2} 形式；无有效键时返回 null
     */
    public static String encodeBaggage(Map<String, String> baggage) {
        if (baggage == null || baggage.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : baggage.entrySet()) {
            if (!BaggageKeys.isAllowed(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append('=').append(sanitizeValue(entry.getValue()));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 解码 {@code baggage}，忽略白名单外的键。
     *
     * @param baggage 头值，可为 null
     * @return 键值对视图（保持出现顺序）；无有效键时返回空 Map
     */
    public static Map<String, String> decodeBaggage(String baggage) {
        Map<String, String> result = new LinkedHashMap<>();
        if (baggage == null || baggage.isBlank()) {
            return result;
        }
        for (String pair : baggage.split(";")) {
            String item = pair.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = item.substring(0, eq).trim();
            String value = item.substring(eq + 1).trim();
            if (BaggageKeys.isAllowed(key) && !value.isEmpty()) {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 把三件套头一次性格式化到一个 sink 里。
     *
     * @param ctx 上下文
     * @param sink 接收 {@code (name, value)} 的回调，value 为 null 表示不写该头
     */
    public static void encodeAll(TraceContext ctx, BiConsumer<String, String> sink) {
        sink.accept(TRACEPARENT, encodeTraceparent(ctx));
        String state = encodeTracestate(ctx.traceState());
        if (state != null) {
            sink.accept(TRACESTATE, state);
        }
    }

    /**
     * 填充 3 个随机 64 位 id。
     *
     * <p>刻意不用 {@code nextLongs(long[])}：那是 JDK 24 才有的 API，而本项目目标是 Java 21。
     *
     * @param out 长度为 3 的数组：前两个拼成 32 位 traceId，第三个作为 16 位 spanId
     */
    public static void randomize(long[] out) {
        for (int i = 0; i < out.length; i++) {
            out[i] = SECURE_RANDOM.nextLong();
        }
    }

    /**
     * 用指定随机源填充 3 个 64 位 id，便于测试可复现。
     *
     * @param random 随机源
     * @param out 长度为 3 的数组
     */
    public static void randomize(RandomGenerator random, long[] out) {
        for (int i = 0; i < out.length; i++) {
            out[i] = random.nextLong();
        }
    }

    /**
     * 把一个 64 位 id 转成 16 位小写 hex。
     *
     * <p>traceId 需要 32 位，因此它由<b>两个</b> 64 位 id 拼成（见 {@link #createRoot()} 的用法）： 只取一个 long 的 16 位
     * hex 会让 traceId 长度不足 32，构造器会直接拒绝。
     *
     * @param value 输入
     * @return 16 位小写 hex
     */
    public static String toHex(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return toHex(bytes);
    }

    /**
     * byte[] 转小写 hex。
     *
     * @param bytes 输入字节
     * @return 与输入等长的小写 hex
     */
    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * 对 payload 取 sha256 摘要，用于审计留痕（契约 EVT-001 的 {@code payloadDigest}）。
     *
     * <p>审计默认<b>不落 prompt 原文</b>，但必须能证明「同一份 payload 再来一次时内容没变」， 摘要就是为此存在的：有了它可以验证脱敏/改写逻辑有没有生效，又不会泄漏
     * PII。
     *
     * @param payload 载荷，可为 null
     * @return 形如 {@code sha256:<hex>}；payload 为 null 时返回 null
     */
    public static String digest(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + toHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 规范要求必须支持的算法，走到这里说明 JRE 被裁剪过
            throw new IllegalStateException("当前 JRE 不支持 SHA-256，无法计算 payload 摘要", ex);
        }
    }

    private static boolean isAllZeros(String hex) {
        for (int i = 0; i < hex.length(); i++) {
            if (hex.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static String sanitizeValue(String value) {
        // baggage 的值不允许含分隔符，否则一个值就能伪造出第二个键
        return value.replaceAll("[\\s;=,\"]", "_");
    }
}
