/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core;

/**
 * 三仓共用的错误码（契约 ERR-001）。
 *
 * <p>每个错误码同时携带三件事：对外的 HTTP 语义、是否允许重试、以及给业务方看的可读语义。 「是否允许重试」是本枚举存在的<b>主要理由</b> —— PRD P0-6 的验收标准是「注入
 * 429 故障可自动重试 成功；注入业务错误（如参数非法）不发生无意义重试」。把 retryable 直接编码进枚举，重试策略就只剩 一行 {@code if
 * (classifier.errorCode(t).retryable())}，不可能写错。
 *
 * <p>⚠️ 字段名与顺序由 {@code contract/contract-v1.json} 锁定，改动必须三仓同 PR。
 */
public enum ErrorCode {

    /** 配额不足。fail-soft：返回 429 业务响应，<b>不抛异常</b>（PRD P0-5）。 */
    SW_QUOTA_EXCEEDED(429, false, "配额不足"),

    /** 上游 provider 不可用或熔断中。可重试。 */
    SW_PROVIDER_UNAVAILABLE(503, true, "上游供应商不可用"),

    /** 上游超时。可重试。 */
    SW_PROVIDER_TIMEOUT(504, true, "上游供应商超时"),

    /** 已配置降级链但全部失败。可重试（对调用方而言换时间重试有意义）。 */
    SW_MODEL_UNAVAILABLE(503, true, "降级链上的模型全部不可用"),

    /** 内容审核拦截。不可重试 —— 同样的输入重试必然同样被拦。 */
    SW_CONTENT_FILTERED(422, false, "内容被安全审核拦截"),

    /** 参数错误。<b>严禁重试</b>：重试同样的非法参数 100 次结果一样，只是白烧配额。 */
    SW_BAD_REQUEST(400, false, "请求参数错误"),

    /** 工具未注册或超出白名单（仓库②语义，①中预留）。 */
    SW_TOOL_DENIED(403, false, "工具未注册或超出白名单"),

    /** 需人工审批（仓库②专用语义，返回挂起令牌）。 */
    SW_APPROVAL_REQUIRED(202, false, "需要人工审批"),

    /** 审批拒绝。 */
    SW_APPROVAL_DENIED(403, false, "审批被拒绝"),

    /** 内部错误。 */
    SW_INTERNAL(500, false, "网关内部错误");

    private final int httpStatus;
    private final boolean retryable;
    private final String defaultMessage;

    ErrorCode(int httpStatus, boolean retryable, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.defaultMessage = defaultMessage;
    }

    /** 对外暴露的 HTTP 状态码。 */
    public int httpStatus() {
        return httpStatus;
    }

    /** 是否允许重试。只有 true 的错误码才允许进入重试与降级链。 */
    public boolean retryable() {
        return retryable;
    }

    /** 未提供具体信息时的默认可读描述。 */
    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * 按名称解析错误码，未知名称一律归为 {@link #SW_INTERNAL}。
     *
     * <p>不抛异常是刻意的：错误码来自上游 HTTP 响应与内部映射，第三方内容不可控， 一个没见过的字符串不应该让网关自己崩掉。
     *
     * @param name 错误码名称，可为 null
     * @return 匹配的错误码，无匹配时返回 {@link #SW_INTERNAL}
     */
    public static ErrorCode fromName(String name) {
        if (name == null || name.isBlank()) {
            return SW_INTERNAL;
        }
        for (ErrorCode code : values()) {
            if (code.name().equals(name)) {
                return code;
            }
        }
        return SW_INTERNAL;
    }
}
