/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.pricing;

/** 计价单位（契约 PRC-001）。首版只有一种，但写进契约是为了将来加「按次计费」时不破坏兼容性。 */
public enum PriceUnit {
    /** 每 1000 token 计费。 */
    PER_1K_TOKENS
}
