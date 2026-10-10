/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.pricing;

/**
 * 价格来源（契约 PRC-001）。
 *
 * <p>这个枚举存在的唯一目的是<b>让「我们不知道」成为一个可传播的信息</b>。 一个标了 {@link #ESTIMATED} 的价格，用户看到控制台上的提示就会自己去核对； 一个混在
 * {@link #OFFICIAL} 里的猜测，则会让整个成本报表失去可信度。
 */
public enum PriceSource {

    /** 官方价目表。内置价格表中可标注此值的条目必须在 README 给出核对日期。 */
    OFFICIAL,

    /** 社区估算。控制台与 README 必须显式标注，不可作为预算依据。 */
    ESTIMATED,

    /** 用户通过 {@code switchyard-prices.yml} 自配。优先级最高。 */
    CUSTOM
}
