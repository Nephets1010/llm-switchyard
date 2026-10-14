/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

/** 熔断器状态。 */
public enum CircuitState {

    /** 正常放行。 */
    CLOSED,

    /** 打开：直接快速失败，不打上游（保护上游，也让业务方立刻拿到可重试的错误）。 */
    OPEN,

    /** 半开：放行一个探测请求，成功则关闭，失败则重新打开。 */
    HALF_OPEN
}
