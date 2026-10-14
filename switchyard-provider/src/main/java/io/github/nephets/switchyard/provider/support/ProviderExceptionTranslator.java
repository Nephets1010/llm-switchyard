/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.support;

import io.github.nephets.switchyard.core.ErrorCode;
import io.github.nephets.switchyard.core.SwitchyardException;
import io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier;

/**
 * 把 provider 的 HTTP 异常翻译成统一错误码。
 *
 * <p><b>存在的理由</b>：JDK HttpClient 抛的 {@code IOException} / {@code HttpTimeoutException} 里没有任何 HTTP
 * 状态码 —— 429 和 404 长得一模一样。如果直接把它们交给 core 的 {@code DefaultErrorClassifier}，只能按「网络故障」一律判成可重试， 结果就是对
 * 400 参数错误重试三次（PRD P0-6 明确禁止）。
 *
 * <p>所以适配器必须在捕获异常的第一时间把状态码「贴」上去，让下游分类器能正确判断。
 *
 * <p>实现 {@link DefaultErrorClassifier.HttpStatusCarrier} 是关键：它让 core 在 <b>完全不依赖 Spring
 * 异常体系</b>的前提下识别状态码，这也是 core 零框架依赖能成立的基础。
 */
public final class ProviderExceptionTranslator {

    private ProviderExceptionTranslator() {
        // 工具类不允许实例化
    }

    /**
     * 包装一个带 HTTP 状态码的异常。
     *
     * @param httpStatus 上游状态码
     * @param body 响应体（截断后，便于排障）
     * @param cause 原始异常
     * @return 携带状态码的异常，交给 core 的分类器判定可重试性
     */
    public static RuntimeException wrap(int httpStatus, String body, Throwable cause) {
        ErrorCode code = DefaultErrorClassifier.fromStatus(httpStatus);
        return new ProviderHttpException(httpStatus, code, body, cause);
    }

    /**
     * 构造一个不带 cause 的 provider 异常。
     *
     * @param httpStatus 上游状态码
     * @param body 响应体
     * @return 异常
     */
    public static RuntimeException wrap(int httpStatus, String body) {
        return wrap(httpStatus, body, null);
    }

    /**
     * 把任意异常归一为 {@link SwitchyardException}，保证业务侧只需处理一种异常类型。
     *
     * @param throwable 原始异常
     * @param traceId traceId
     * @return 统一异常
     */
    public static RuntimeException toSwitchyard(Throwable throwable, String traceId) {
        if (throwable instanceof SwitchyardException already) {
            return already;
        }
        ErrorCode code = new DefaultErrorClassifier().errorCode(throwable);
        return new SwitchyardException(code, messageOf(throwable, code), traceId, throwable);
    }

    /**
     * 读取错误响应体中的 {@code error.message}，用于给用户可读的提示。
     *
     * <p>刻意<b>不</b>把整个响应体塞进异常消息：上游的错误响应可能回显用户 prompt 全文， 而我们承诺默认不落 prompt 原文（契约 EVT-001）。
     *
     * @param body 响应体 JSON 文本
     * @param fallback 解析不出时的兜底文案
     * @return 可读提示
     */
    public static String extractMessage(String body, String fallback) {
        if (body == null || body.isBlank()) {
            return fallback;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            com.fasterxml.jackson.databind.JsonNode error = root.path("error");
            if (error.isTextual()) {
                return truncate(error.asText(), 300);
            }
            com.fasterxml.jackson.databind.JsonNode message = error.path("message");
            if (message.isTextual()) {
                return truncate(message.asText(), 300);
            }
        } catch (Exception ignored) {
            // 响应体不是合法 JSON（如 HTML 错误页）—— 属于常见情况，退回兜底文案
        }
        return fallback;
    }

    private static String messageOf(Throwable throwable, ErrorCode code) {
        String message = throwable == null ? null : throwable.getMessage();
        return (message == null || message.isBlank()) ? code.defaultMessage() : message;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 带 HTTP 状态码的 provider 异常。 */
    public static final class ProviderHttpException extends RuntimeException
            implements DefaultErrorClassifier.HttpStatusCarrier {

        private static final long serialVersionUID = 1L;

        private final int httpStatus;
        private final transient ErrorCode errorCode;

        /**
         * 构造。
         *
         * @param httpStatus 状态码
         * @param errorCode 翻译后的错误码
         * @param body 响应体摘要
         * @param cause 原始异常
         */
        public ProviderHttpException(
                int httpStatus, ErrorCode errorCode, String body, Throwable cause) {
            super(
                    "[HTTP "
                            + httpStatus
                            + "] "
                            + (body == null ? errorCode.defaultMessage() : body),
                    cause);
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
        }

        @Override
        public int httpStatus() {
            return httpStatus;
        }

        /** 翻译后的错误码。 */
        public ErrorCode errorCode() {
            return errorCode;
        }

        /** 状态码。 */
        public int status() {
            return httpStatus;
        }
    }
}
