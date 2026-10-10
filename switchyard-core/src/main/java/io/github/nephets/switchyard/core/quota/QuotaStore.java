/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.quota;

import io.github.nephets.switchyard.core.usage.Usage;

/**
 * 配额存储（SPI，PRD P0-4）。
 *
 * <p>三个方法的语义必须严格配对使用，缺一不可：
 *
 * <ul>
 *   <li>{@link #reserve} —— 请求<b>前</b>预留；必须原子，否则并发下会超发；
 *   <li>{@link #settle} —— 请求<b>后</b>按真实用量结算，多退少补；
 *   <li>{@link #release} —— 请求失败时释放预留。这条最容易被忽略，但缺了它， 一次上游 5xx 就会永久吃掉一份配额，用户会看到「什么都没调用，额度却少了」。
 * </ul>
 *
 * <p><b>首版明确定位单实例</b>（嵌入式网关天然与业务同实例）。 多实例协调（Redis 令牌桶）明确列入 P2，并写进 README 的「不做什么」。
 */
public interface QuotaStore {

    /**
     * 原子预留额度。
     *
     * <p>实现必须保证：并发调用下「授予的总量」不会超过配额上限。 这条由 {@code InMemoryQuotaStoreTest} 用 100 线程并发精确断言。
     *
     * @param key 配额键
     * @param tokens 申请预留的 token 数
     * @return 预留凭据，{@code granted=false} 表示配额不足
     */
    Reservation reserve(QuotaKey key, long tokens);

    /**
     * 按真实用量结算（多退少补）。
     *
     * @param reservation 原预留凭据
     * @param actual 真实用量
     * @return 结算后的配额快照
     */
    QuotaSnapshot settle(Reservation reservation, Usage actual);

    /**
     * 释放预留（请求失败时调用，把预留额度还回去）。
     *
     * @param reservation 原预留凭据
     */
    void release(Reservation reservation);

    /**
     * 查询当前快照。
     *
     * @param key 配额键
     * @return 配额快照；未配置该键时返回不限额快照
     */
    QuotaSnapshot snapshot(QuotaKey key);

    /**
     * 注册/更新配额策略（控制台与 CLI 的写入口）。
     *
     * @param key 配额键
     * @param policy 配额策略
     */
    void putPolicy(QuotaKey key, QuotaPolicy policy);

    /**
     * 读取配额策略。
     *
     * @param key 配额键
     * @return 策略；未配置时返回 null
     */
    QuotaPolicy policyOf(QuotaKey key);

    /**
     * 释放所有已过期的预留，防止预留泄漏。
     *
     * @return 清理的预留笔数
     */
    int purgeExpired();

    /** 清空全部配额状态（CLI 的 {@code quota reset} 与测试用）。 */
    void reset();
}
