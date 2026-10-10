/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 请求摘要：让 mock 的响应可被确定性断言。
 *
 * <p>用法：把 {@code sha256(prompt)} 的前 8 位放进响应文本。测试里对同一 prompt 重放时， 拿到的响应文本必然相同 —— 于是可以断言「同一 prompt
 * 得到同一响应」， 这是「确定性」最容易被验证的一种形式。
 */
public final class RequestDigest {

    private RequestDigest() {
        // 工具类不允许实例化
    }

    /**
     * 计算摘要。
     *
     * @param payload 载荷，可为 null
     * @return 前 8 位十六进制摘要；payload 为空返回 {@code "00000000"}
     */
    public static String shortDigest(String payload) {
        if (payload == null || payload.isBlank()) {
            return "00000000";
        }
        // 显式指定 Locale：默认 Locale 可能是土耳其语等，会让 %x 的十六进制字母变成非 ASCII
        java.util.Locale locale = java.util.Locale.ROOT;
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(locale, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
