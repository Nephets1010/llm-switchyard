/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm.admin;

import java.util.List;

/**
 * 故障注入场景模型。
 *
 * @param name 场景名（对应 {@code resources/mock-llm/scenarios/*.yml} 的文件名）
 * @param description 场景说明
 * @param latencyMillis 注入的响应延迟
 * @param httpStatus 非 2xx 时返回的 HTTP 状态码；{@code 0} 表示正常响应
 * @param errorBody 错误响应体；为空时用协议默认格式
 * @param failTimes 连续失败次数；{@code <= 0} 表示永久生效直到切换场景
 * @param dropStreamMidway 流式时是否在输出中途断流（不发送 {@code [DONE]}）
 * @param omitStreamUsage 流式时<b>刻意不返回 usage 块</b>（模拟 vLLM 未开 include_usage 的历史行为）
 * @param emptyContent 是否返回空内容
 */
public record Scenario(
        String name,
        String description,
        long latencyMillis,
        int httpStatus,
        String errorBody,
        int failTimes,
        boolean dropStreamMidway,
        boolean omitStreamUsage,
        boolean emptyContent) {

    /** 正常场景。 */
    public static Scenario ok() {
        return new Scenario("ok", "正常响应，返回可预测的 usage", 0L, 0, null, 0, false, false, false);
    }

    /** 429 限流。 */
    public static Scenario rateLimit() {
        return new Scenario(
                "rate-limit", "持续返回 429，用于验证语义化重试", 0L, 429, null, 0, false, false, false);
    }

    /** 429 但只失败 N 次后成功。 */
    public static Scenario rateLimitTimes(int times) {
        return new Scenario(
                "rate-limit-retryable",
                "前 N 次返回 429 之后成功，用于验证重试链路",
                0L,
                429,
                null,
                times,
                false,
                false,
                false);
    }

    /** 500 服务端错误。 */
    public static Scenario serverError() {
        return new Scenario(
                "server-error", "返回 500，用于验证 5xx 可重试", 0L, 500, null, 0, false, false, false);
    }

    /** 400 参数错误。 */
    public static Scenario invalidRequest() {
        return new Scenario(
                "invalid-req", "返回 400，用于验证参数错误绝不重试", 0L, 400, null, 0, false, false, false);
    }

    /** 慢响应。 */
    public static Scenario slow() {
        return new Scenario("slow", "延迟 2 秒后响应，用于验证超时与降级", 2000L, 0, null, 0, false, false, false);
    }

    /** 故意超时。 */
    public static Scenario timeout() {
        return new Scenario(
                "timeout", "延迟 30 秒，用于验证超时重试", 30_000L, 0, null, 0, false, false, false);
    }

    /** 流式但不返回 usage 块。 */
    public static Scenario emptyUsageStream() {
        return new Scenario(
                "empty-usage-stream",
                "流式响应刻意不返回 usage 块，用于验证「流式计量不得为 0」的修复",
                0L,
                0,
                null,
                0,
                false,
                true,
                false);
    }

    /**
     * 流式中途断流场景。
     *
     * <p>工厂方法<b>不能</b>与 record 组件的访问器同名 —— record 会自动生成 {@code dropStreamMidway()}，同名会导致签名冲突。
     */
    public static Scenario streamDropped() {
        return new Scenario(
                "drop-stream", "流式输出中途断开且不发 [DONE]，用于验证异常处理", 0L, 0, null, 0, true, false, false);
    }

    /** 内置场景清单（供 admin 端点与测试使用）。 */
    public static List<Scenario> builtin() {
        return List.of(
                ok(),
                rateLimit(),
                rateLimitTimes(2),
                serverError(),
                invalidRequest(),
                slow(),
                timeout(),
                emptyUsageStream(),
                streamDropped());
    }
}
