/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.quota;

/**
 * 配额键：{@code (tenantId, bizLine, providerId, modelId)}。
 *
 * <p>这四元组对应 PRD P0-5 的「按租户 / 按业务线 / 按模型三级配额」。
 *
 * <p><b>为什么 bizLine 必须进 key 而不是做成「租户下的子维度」</b>：财务出账的最小单位是 「部门 ×
 * 模型」。若二级维度只能靠事后遍历统计，就无法在请求前拦截某个业务线的超额消耗， 而「事前拦截」正是配额存在的意义。
 *
 * @param tenantId 租户标识
 * @param bizLine 业务线；可为 null（表示租户级总配额）
 * @param providerId 供应商标识
 * @param modelId 模型名
 */
public record QuotaKey(String tenantId, String bizLine, String providerId, String modelId) {

    /** 紧凑构造器：tenantId 为空时归一化为 {@code "_default"}，避免出现无法归属的配额黑洞。 */
    public QuotaKey {
        tenantId = (tenantId == null || tenantId.isBlank()) ? "_default" : tenantId;
        bizLine = (bizLine == null || bizLine.isBlank()) ? null : bizLine;
    }

    /** 字符串化后的 key，供控制台展示与日志使用。 */
    public String text() {
        return text(tenantId, bizLine, providerId, modelId);
    }

    /**
     * 构造字符串 key。
     *
     * @param tenantId 租户
     * @param bizLine 业务线
     * @param providerId 供应商
     * @param modelId 模型
     * @return 形如 {@code tenant/bizLine/provider/model} 的字符串
     */
    public static String text(String tenantId, String bizLine, String providerId, String modelId) {
        return (tenantId == null ? "_default" : tenantId)
                + "/"
                + (bizLine == null ? "-" : bizLine)
                + "/"
                + (providerId == null ? "-" : providerId)
                + "/"
                + (modelId == null ? "-" : modelId);
    }

    /**
     * 解析字符串 key（控制台与 CLI 的读写入口）。
     *
     * @param text 形如 {@code tenant/bizLine/provider/model} 的字符串
     * @return QuotaKey
     * @throws IllegalArgumentException 格式非法时
     */
    public static QuotaKey parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("配额键不可为空");
        }
        String[] parts = text.split("/", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("配额键格式应为 tenant/bizLine/provider/model，实际为：" + text);
        }
        return new QuotaKey(
                "-".equals(parts[0]) ? null : parts[0],
                "-".equals(parts[1]) ? null : parts[1],
                "-".equals(parts[2]) ? null : parts[2],
                "-".equals(parts[3]) ? null : parts[3]);
    }

    /** 返回一个只换了模型名的副本（用于「按模型三级配额」时对同一租户不同模型分别计账）。 */
    public QuotaKey withModel(String newProviderId, String newModelId) {
        return new QuotaKey(tenantId, bizLine, newProviderId, newModelId);
    }
}
