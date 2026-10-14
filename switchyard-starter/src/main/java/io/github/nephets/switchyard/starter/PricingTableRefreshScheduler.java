/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.pricing.PricingTableLoader;
import io.github.nephets.switchyard.core.quota.QuotaStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Path;

/**
 * 价格表热更新调度器（PRD P0-7：「价格表更新无需改代码、无需重启」）。
 *
 * <p>为什么需要它：官方价目表会变，而把每次调价都变成一次发版是不可接受的 —— 用户会为了改一个数字去等一个发版窗口。
 *
 * <p><b>热更新绝不能影响服务</b>：解析失败时保留旧表并告警。 价格表出错的正确表现是「成本按旧价记账 + 控制台告警」， 而不是「网关启动失败」——
 * 后者会把一次配置笔误升级成一次生产事故。
 */
public class PricingTableRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(PricingTableRefreshScheduler.class);

    private final PricingTable table;
    private final QuotaStore quotaStore;
    private final String externalFilePath;
    private int lastLoadedCount;

    /**
     * 构造。
     *
     * @param table 价格表
     * @param quotaStore 配额存储（用于清理过期的在途预留）
     * @param externalFilePath 外部价格表路径；为空表示只用内置表
     */
    public PricingTableRefreshScheduler(
            PricingTable table, QuotaStore quotaStore, String externalFilePath) {
        this.table = table;
        this.quotaStore = quotaStore;
        this.externalFilePath = externalFilePath;
    }

    /**
     * 定时热更新（默认每 5 分钟，与架构 PRD P0-7 一致）。
     *
     * <p>固定延迟而非固定频率：处理耗时不会累积到下一次触发上。
     */
    @Scheduled(fixedDelayString = "${llm.switchyard.pricing-refresh-interval-ms:300000}")
    public void refresh() {
        if (externalFilePath != null && !externalFilePath.isBlank()) {
            PricingTableLoader loader = new PricingTableLoader(table);
            int count = loader.loadFromFile(Path.of(externalFilePath));
            if (count >= 0) {
                if (count != lastLoadedCount) {
                    log.info("switchyard: 价格表热更新生效，共 {} 条", count);
                }
                lastLoadedCount = count;
            } else {
                // ★ 保留旧表并告警，绝不让一次 YAML 笔误中断服务
                log.warn("switchyard: 价格表热更新失败，沿用旧表（{} 条）。原因：{}", table.size(), loader.lastError());
            }
        }
        if (quotaStore != null) {
            int purged = quotaStore.purgeExpired();
            if (purged > 0) {
                log.info("switchyard: 回收了 {} 笔过期的在途预留", purged);
            }
        }
    }

    /** 上次成功加载的条目数。 */
    public int lastLoadedCount() {
        return lastLoadedCount;
    }

    /**
     * 从配置读取外部路径。
     *
     * @param path 配置值
     * @return 路径字符串；为空表示只用内置表
     */
    static String resolvePath(String path) {
        return path == null || path.isBlank() ? null : path;
    }

    /**
     * 默认外部路径（可通过 {@code llm.switchyard.pricing-file} 覆盖）。
     *
     * @return 路径
     */
    static String defaultPath(@Value("${llm.switchyard.pricing-file:}") String configured) {
        return resolvePath(configured);
    }
}
