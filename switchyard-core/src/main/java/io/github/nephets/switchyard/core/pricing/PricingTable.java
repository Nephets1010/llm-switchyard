/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.pricing;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存价格表：线程安全读 + 可整体替换（热更新）。
 *
 * <p><b>为什么读是无锁的</b>：价格查询在每次模型调用结算时都会发生，是本仓最热的读路径。 用 {@code volatile Map} 整体替换而不是 {@code
 * ConcurrentHashMap} 逐条 put， 是为了让「热更新」成为一个<b>原子操作</b>：解析失败时旧表继续可用，绝不会出现
 * 「更新到一半，价格表里一半是新价一半是旧价」的对账窗口。
 */
public class PricingTable {

    private volatile Map<String, PriceEntry> entries;
    private volatile Instant loadedAt;

    /**
     * 用给定条目构造。
     *
     * @param entries 条目集合
     */
    public PricingTable(Collection<PriceEntry> entries) {
        replaceAll(entries);
    }

    /**
     * 按 {@code providerId/modelId} 查价。
     *
     * <p>额外支持「按前缀匹配模型名」：{@code deepseek-chat} 查不到时，尝试用 providerId 下 唯一的条目兜底（有些部署只配了供应商没配具体模型）。
     *
     * @param providerId 供应商标识
     * @param modelId 模型名
     * @return 价格条目；无匹配返回 {@link Optional#empty()}
     */
    public Optional<PriceEntry> lookup(String providerId, String modelId) {
        Map<String, PriceEntry> snapshot = this.entries;
        PriceEntry exact = snapshot.get(PriceEntry.key(providerId, modelId));
        if (exact != null) {
            return Optional.of(exact);
        }
        if (providerId != null && modelId == null) {
            String prefix = providerId + "/";
            List<PriceEntry> matches =
                    snapshot.values().stream()
                            .filter(e -> e.providerId().equals(providerId))
                            .toList();
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * 查价，找不到时抛出。
     *
     * @param providerId 供应商标识
     * @param modelId 模型名
     * @return 价格条目
     * @throws io.github.nephets.switchyard.core.SwitchyardException 找不到时抛 {@code SW_INTERNAL}
     */
    public PriceEntry require(String providerId, String modelId) {
        return lookup(providerId, modelId)
                .orElseThrow(
                        () ->
                                new io.github.nephets.switchyard.core.SwitchyardException(
                                        io.github.nephets.switchyard.core.ErrorCode.SW_INTERNAL,
                                        "价格表中没有 "
                                                + providerId
                                                + "/"
                                                + modelId
                                                + " 的价格条目，请检查 switchyard-prices.yml",
                                        io.github.nephets.switchyard.core.trace.TraceContext
                                                                .currentOrNull()
                                                        == null
                                                ? null
                                                : io.github.nephets.switchyard.core.trace
                                                        .TraceContext.current()
                                                        .traceId(),
                                        null));
    }

    /**
     * 整体替换条目（热更新的原子入口）。
     *
     * @param newEntries 新条目
     * @return 生效后的条目数
     */
    public int replaceAll(Collection<PriceEntry> newEntries) {
        Map<String, PriceEntry> copy = new java.util.HashMap<>();
        if (newEntries != null) {
            for (PriceEntry entry : newEntries) {
                if (entry != null) {
                    copy.put(entry.key(), entry);
                }
            }
        }
        this.entries = java.util.Collections.unmodifiableMap(copy);
        this.loadedAt = Instant.now();
        return this.entries.size();
    }

    /** 重新加载（由 {@link PricingTableLoader} 填充）。 */
    public void reload() {
        this.loadedAt = Instant.now();
    }

    /** 上次加载时刻。 */
    public Instant loadedAt() {
        return loadedAt;
    }

    /** 条目数。 */
    public int size() {
        return entries.size();
    }

    /** 全部条目快照。 */
    public Collection<PriceEntry> all() {
        return entries.values();
    }

    /** 是否为空表。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
