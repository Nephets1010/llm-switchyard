/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.quota;

/**
 * 超限行为（PRD P0-5）。
 *
 * <p>默认一律是 {@link #FAIL_SOFT}：返回 HTTP 429 + 业务级错误码，<b>绝不产生 5xx</b>。
 * 理由：租户把预算打满是一个<b>正常的业务状态</b>，不是系统故障； 返回 5xx 会让业务方的 SRE 把它当事故响应（告警、值班、复盘）， 而正确做法是业务方读 429
 * 与剩余额度后自行降级或提示用户。
 */
public enum OverLimitBehavior {

    /**
     * fail-soft：返回 {@code SW_QUOTA_EXCEEDED}（429）+ 友好提示，不抛异常。
     *
     * <p>这是本项目的默认行为，也是 PRD P0-5 的明确验收标准。
     */
    FAIL_SOFT,

    /**
     * 触发熔断：把该 {@link ModelRef} 的熔断器置为 OPEN，后续请求直接快速失败。
     *
     * <p>用于「已经连续超限，说明配额配置本身有问题」的场景，避免每个请求都去走一遍 上游调用再发现超限。
     */
    TRIP_BREAKER
}
