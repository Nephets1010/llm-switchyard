/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.model;

/**
 * 模型引用：{@code (providerId, modelId, region)} 值对象。
 *
 * <p>路由、降级链、熔断器、配额 key、审计事件全部以它为标识。 做成不可变值对象 + 统一 {@link #text()} 序列化，是为了让「同一个模型」在日志、 控制台、span
 * 属性里永远是同一个字符串 —— 否则对账时会因为大小写或分隔符差异对不上。
 *
 * @param providerId 供应商标识，全小写，见 {@code ProviderCatalog}
 * @param modelId 供应商侧模型名
 * @param region 区域标记（如 {@code cn-beijing}）；可为 null
 */
public record ModelRef(String providerId, String modelId, String region) {

    /** 紧凑构造器：providerId / modelId 归一化为小写去空白。 */
    public ModelRef {
        providerId =
                providerId == null ? null : providerId.trim().toLowerCase(java.util.Locale.ROOT);
        modelId = modelId == null ? null : modelId.trim();
        region = region == null || region.isBlank() ? null : region.trim();
    }

    /**
     * 不带区域的模型引用。
     *
     * @param providerId 供应商
     * @param modelId 模型
     * @return ModelRef
     */
    public static ModelRef of(String providerId, String modelId) {
        return new ModelRef(providerId, modelId, null);
    }

    /**
     * 解析 {@code providerId/modelId} 形式。
     *
     * @param text 形如 {@code openai/gpt-5-mini} 的字符串
     * @return ModelRef
     * @throws IllegalArgumentException 格式非法时
     */
    public static ModelRef parse(String text) {
        if (text == null || !text.contains("/")) {
            throw new IllegalArgumentException("模型引用格式应为 providerId/modelId，实际为：" + text);
        }
        int idx = text.indexOf('/');
        return new ModelRef(text.substring(0, idx), text.substring(idx + 1), null);
    }

    /**
     * 字符串化：{@code providerId/modelId}，带区域时为 {@code providerId/modelId@region}。
     *
     * @return 稳定字符串
     */
    public String text() {
        return region == null
                ? providerId + "/" + modelId
                : providerId + "/" + modelId + "@" + region;
    }

    /** 查价格表用的键（不含区域）。 */
    public String priceKey() {
        return providerId + "/" + modelId;
    }
}
