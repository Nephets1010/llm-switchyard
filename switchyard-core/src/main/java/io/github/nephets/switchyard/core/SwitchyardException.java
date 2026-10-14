/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core;

import java.io.Serial;

/**
 * 网关唯一的运行时异常类型。
 *
 * <p>设计约束（架构文档 §8.4）：<b>禁止把 provider 的原始异常抛给业务代码</b>。 上游的 {@code RestClientException}、{@code
 * IOException}、{@code HttpTimeoutException} 对业务方毫无信息量，且会把供应商的实现细节泄漏到用户的错误处理逻辑里。 一律经 {@code
 * ErrorClassifier} 翻译成本异常，业务方只需要处理 {@link ErrorCode}。
 *
 * <p>本异常携带 traceId，因此用户报障时只需报一个 traceId，我们就能在审计日志里精确定位这一次调用。
 */
public class SwitchyardException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient String traceId;

    /**
     * 构造异常。
     *
     * @param errorCode 错误码，不可为 null
     * @param message 可读描述
     * @param traceId 本次调用的 traceId，可为 null
     * @param cause 原始异常，可为 null
     */
    public SwitchyardException(
            ErrorCode errorCode, String message, String traceId, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? ErrorCode.SW_INTERNAL : errorCode;
        this.traceId = traceId == null ? "" : traceId;
    }

    /**
     * 构造无 cause 的异常。
     *
     * @param errorCode 错误码
     * @param message 可读描述
     * @param traceId traceId
     */
    public SwitchyardException(ErrorCode errorCode, String message, String traceId) {
        this(errorCode, message, traceId, null);
    }

    /** 错误码。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 本次调用的 traceId。 */
    public String traceId() {
        return traceId;
    }

    /** 是否允许重试。等价于 {@code errorCode().retryable()}。 */
    public boolean retryable() {
        return errorCode.retryable();
    }

    /** 转换为对外错误响应体。 */
    public ErrorResponse toErrorResponse() {
        return ErrorResponse.of(errorCode, getMessage(), traceId);
    }

    /** 转换为 fail-soft 响应体（PRD P0-5：配额超限不抛异常，直接返回这个对象）。 */
    public ErrorResponse toFailSoftResponse() {
        return ErrorResponse.of(errorCode, getMessage(), traceId);
    }
}
