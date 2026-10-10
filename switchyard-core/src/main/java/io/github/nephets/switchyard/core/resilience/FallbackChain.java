/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import io.github.nephets.switchyard.core.model.ModelRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 降级链（PRD P0-6）。
 *
 * <p>语义：主模型失败（重试耗尽或熔断打开）时，按配置顺序依次尝试备用模型。
 *
 * <p><b>为什么降级链是「有序且会去重」的</b>：
 *
 * <ul>
 *   <li>有序 —— 运维需要控制「先降级到贵的备用模型，还是先降级到便宜的本地模型」，顺序本身就是策略；
 *   <li>去重 —— 配置里写了两个相同的模型引用时，跳过第二次尝试。否则用户会看到 「降级日志里同一个模型出现了两次」，然后怀疑网关有 bug。
 * </ul>
 *
 * <p><b>降级必须可查</b>（PRD 验收标准：「降级链路有日志可查」）： 每次 {@link #next(ModelRef)} 调用都会记入 {@link
 * #degradationTrail()}， 控制台与 {@code doctor} 命令据此展示「哪个模型降到了哪个」。
 */
public class FallbackChain {

    private final List<ModelRef> chain;
    private final Map<ModelRef, List<ModelRef>> tried = new LinkedHashMap<>();
    private final boolean throwWhenExhausted;

    /**
     * 用有序降级链构造。
     *
     * @param chain 降级链，首项应为主模型；为空时等价于单模型链
     */
    public FallbackChain(List<ModelRef> chain) {
        this(chain, true);
    }

    /**
     * 构造。
     *
     * @param chain 降级链
     * @param throwWhenExhausted 链耗尽时是否抛异常；false 时 {@link #next} 返回 {@link Optional#empty()}
     */
    public FallbackChain(List<ModelRef> chain, boolean throwWhenExhausted) {
        List<ModelRef> deduped = new ArrayList<>();
        if (chain != null) {
            for (ModelRef ref : chain) {
                if (ref == null) {
                    continue;
                }
                // 去重但保持顺序：LinkedHashSet 天然满足
                if (!deduped.contains(ref)) {
                    deduped.add(ref);
                }
            }
        }
        this.chain = List.copyOf(deduped);
        this.throwWhenExhausted = throwWhenExhausted;
    }

    /**
     * 取下一个可尝试的模型。
     *
     * @param failed 刚失败的模型；为 null 时返回链首项
     * @return 下一个模型；链耗尽时，{@code throwWhenExhausted=true} 抛 {@code SW_MODEL_UNAVAILABLE}，否则返回 {@link
     *     Optional#empty()}
     */
    public Optional<ModelRef> next(ModelRef failed) {
        List<ModelRef> triedForKey = tried.computeIfAbsent(failed, k -> new ArrayList<>());
        if (failed != null) {
            triedForKey.add(failed);
        }
        for (ModelRef candidate : chain) {
            if (!triedForKey.contains(candidate)) {
                triedForKey.add(candidate);
                return Optional.of(candidate);
            }
        }
        if (throwWhenExhausted) {
            throw new io.github.nephets.switchyard.core.SwitchyardException(
                    io.github.nephets.switchyard.core.ErrorCode.SW_MODEL_UNAVAILABLE,
                    "降级链已耗尽："
                            + (failed == null ? "(未指定失败模型)" : failed.text())
                            + "，链配置为 "
                            + chain.stream().map(ModelRef::text).toList(),
                    null);
        }
        return Optional.empty();
    }

    /**
     * 不记录尝试地取下一个模型。
     *
     * @param failed 已失败的模型
     * @return 链中第一个不等于 {@code failed} 的模型
     */
    public Optional<ModelRef> peekNext(ModelRef failed) {
        for (ModelRef candidate : chain) {
            if (!candidate.equals(failed)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** 降级链全部模型。 */
    public List<ModelRef> chain() {
        return chain;
    }

    /** 链是否只有一个模型（即无降级）。 */
    public boolean isSingle() {
        return chain.size() <= 1;
    }

    /** 某次降级过程的尝试记录（可查，满足 PRD「降级链路有日志可查」）。 */
    public List<ModelRef> degradationTrail(ModelRef startFrom) {
        return List.copyOf(tried.getOrDefault(startFrom, List.of()));
    }

    /** 清空尝试记录（每次新的顶层调用开始时调用）。 */
    public void resetTrail() {
        tried.clear();
    }

    /**
     * 链路描述，用于日志与 {@code doctor} 输出。
     *
     * @return 形如 {@code a → b → c} 的字符串
     */
    public String describe() {
        if (chain.isEmpty()) {
            return "(空)";
        }
        return String.join(" → ", chain.stream().map(ModelRef::text).toList());
    }
}
