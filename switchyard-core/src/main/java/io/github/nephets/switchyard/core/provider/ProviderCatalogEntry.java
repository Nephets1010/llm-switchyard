/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.provider;

import io.github.nephets.switchyard.core.model.ModelRef;

import java.util.List;
import java.util.Set;

/**
 * 供应商目录条目：一家供应商的协议、端点、能力与默认模型。
 *
 * @param alias 用户在配置里写的短名（如 {@code qwen}、{@code zhipu}）
 * @param providerId 规范供应商标识（全小写，见契约 conventions.providerIdValues）
 * @param protocol 协议类型
 * @param defaultBaseUrl 默认 base-url
 * @param capabilities 能力集合
 * @param defaultModels 该供应商的常用模型
 */
public record ProviderCatalogEntry(
        String alias,
        String providerId,
        Protocol protocol,
        String defaultBaseUrl,
        ProviderCapabilities capabilities,
        List<String> defaultModels) {

    /** 紧凑构造器：填充默认值并做防御性复制。 */
    public ProviderCatalogEntry {
        alias = alias == null ? providerId : alias.trim().toLowerCase(java.util.Locale.ROOT);
        providerId =
                providerId == null ? alias : providerId.trim().toLowerCase(java.util.Locale.ROOT);
        if (protocol == null) {
            protocol = Protocol.OPENAI_COMPATIBLE;
        }
        capabilities = capabilities == null ? ProviderCapabilities.none() : capabilities;
        defaultModels = defaultModels == null ? List.of() : List.copyOf(defaultModels);
    }

    /**
     * 该供应商的默认模型引用。
     *
     * @return 第一个默认模型的引用；无默认模型返回 {@code providerId/-}
     */
    public ModelRef defaultRef() {
        return defaultModels.isEmpty()
                ? new ModelRef(providerId, "-", null)
                : new ModelRef(providerId, defaultModels.get(0), null);
    }

    /** 支持的能力枚举集合。 */
    public Set<Capability> capabilitySet() {
        return capabilities.capabilities();
    }

    /** 协议类型。 */
    public enum Protocol {
        /** OpenAI 兼容协议。通义/智谱/DeepSeek/Kimi/豆包/硅基流动/百川… 都是这一类。 */
        OPENAI_COMPATIBLE,
        /** Anthropic Messages API 原生协议。 */
        ANTHROPIC,
        /** Ollama 原生协议（字段命名与 OpenAI 不同，必须独立适配）。 */
        OLLAMA
    }
}
