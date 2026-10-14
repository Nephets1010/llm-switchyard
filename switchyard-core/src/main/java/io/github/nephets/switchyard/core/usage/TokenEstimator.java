/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.usage;

/**
 * Token 估算器（SPI）。
 *
 * <p>本项目的诚实立场：<b>估算不等于计量</b>。估算器只在「上游确实没给 usage」时兜底， 且产出的 {@link Usage} 一律标 {@link
 * UsageSource#ESTIMATED} 并计入可观测字段， 让使用者永远知道自己拿到的是真值还是估。
 *
 * <p>可替换性是刻意留的：默认实现是字符启发式（够用、零依赖、零网络）， 但金融客户可能愿意为准确性接入 tiktoken 或自己的分词服务。
 */
public interface TokenEstimator {

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本，可为 null 或空
     * @return 估算的 token 数，非负
     */
    long estimate(String text);
}
