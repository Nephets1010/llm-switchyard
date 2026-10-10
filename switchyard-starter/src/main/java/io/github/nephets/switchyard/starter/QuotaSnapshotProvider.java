/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaSnapshot;

/** 配额快照读取器（函数式接口，便于控制台与 CLI 解耦具体存储）。 */
@FunctionalInterface
public interface QuotaSnapshotProvider {

    /**
     * 读取配额快照。
     *
     * @param key 配额键
     * @return 配额快照
     */
    QuotaSnapshot snapshotOf(QuotaKey key);
}
