/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core;

import java.time.Instant;

/**
 * 三仓统一的对外错误响应体（契约 ERR-001）。
 *
 * <p>结构固定为 {@code {code, message, retryable, traceId, timestamp}}，三个仓库必须逐字段一致，
 * 这样调用方的错误处理代码才能在切换仓库时零改动。
 *
 * <p>{@code fail-soft} 是本项目对配额超限的承诺（PRD P0-5）：超限时<b>不抛异常</b>， 而是把这个对象直接作为 HTTP
 * 响应体返回，让业务方拿到一个可读的提示而不是一个 5xx 堆栈。
 *
 * @param code 错误码，取值见 {@link ErrorCode}
 * @param message 面向人的可读提示
 * @param retryable 是否建议重试，与 {@link ErrorCode#retryable()} 语义一致
 * @param traceId 本次请求的 W3C traceId，便于把一次报错与它的审计记录对上
 * @param timestamp 发生时刻，ISO-8601 UTC
 */
public record ErrorResponse(
        String code, String message, boolean retryable, String traceId, Instant timestamp) {

    /**
     * 用给定错误码构造响应。
     *
     * @param code 错误码
     * @param message 自定义提示；为 null 时回落到 {@link ErrorCode#defaultMessage()}
     * @param traceId traceId；为 null 时填空串（不允许为 null，避免序列化出 null 字段）
     * @param occurredAt 发生时刻
     * @return 错误响应体
     */
    public static ErrorResponse of(
            ErrorCode code, String message, String traceId, Instant occurredAt) {
        ErrorCode resolved = code == null ? ErrorCode.SW_INTERNAL : code;
        String text = (message == null || message.isBlank()) ? resolved.defaultMessage() : message;
        return new ErrorResponse(
                resolved.name(),
                text,
                resolved.retryable(),
                traceId == null ? "" : traceId,
                occurredAt);
    }

    /**
     * 用当前时刻构造响应。
     *
     * @param code 错误码
     * @param message 自定义提示
     * @param traceId traceId
     * @return 错误响应体
     */
    public static ErrorResponse of(ErrorCode code, String message, String traceId) {
        return of(code, message, traceId, Instant.now());
    }

    /** 对应的 HTTP 状态码。 */
    public int httpStatus() {
        return ErrorCode.fromName(code).httpStatus();
    }
}
