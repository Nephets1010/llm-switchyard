/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 内置供应商目录（架构决策 4 的落地：3 个适配器覆盖 8 家供应商）。
 *
 * <p><b>核心工程判断</b>：通义/智谱/DeepSeek/Kimi/豆包五家都是 OpenAI 兼容协议， 只有 Anthropic 与 Ollama 需要独立适配。因此首版只写 <b>3
 * 个适配器</b>：
 *
 * <ul>
 *   <li>{@code OpenAiCompatibleProvider}（配 base-url 覆盖 openai + 5 家国产模型 + 硅基流动）
 *   <li>{@code AnthropicProvider}
 *   <li>{@code OllamaProvider}
 * </ul>
 *
 * 把「6 类供应商适配」从 6 个实现降为 3 个，是首版能按期交付的关键取舍。 若为每家单独写一个类，绝大多数代码会是重复的 base-url 替换，收益极低而维护成本极高。
 *
 * <p>⚠️ <b>价格不在本目录里</b>：本目录只管「怎么连」，价格表是独立的热更新 YAML （{@code PricingTableLoader}）。两者耦合会导致「改一次价格要发版」。
 *
 * <p>⚠️ <b>base-url 均为公开文档值</b>，用户应按自己的账号区域与代理设置覆盖。
 */
public class ProviderCatalog {

    private final Map<String, ProviderCatalogEntry> byAlias;
    private final Map<String, ProviderCatalogEntry> byProviderId;

    private ProviderCatalog(Map<String, ProviderCatalogEntry> entries) {
        Map<String, ProviderCatalogEntry> aliasMap = new LinkedHashMap<>();
        Map<String, ProviderCatalogEntry> idMap = new LinkedHashMap<>();
        for (ProviderCatalogEntry entry : entries.values()) {
            aliasMap.put(entry.alias(), entry);
            idMap.put(entry.providerId(), entry);
            // providerId 本身也是一个合法别名（openai/openai、qwen/dashscope 之外的直连写法）
            aliasMap.putIfAbsent(entry.providerId(), entry);
        }
        this.byAlias = Map.copyOf(aliasMap);
        this.byProviderId = Map.copyOf(idMap);
    }

    /** 构建内置目录（8 家供应商 + 常见别名）。 */
    public static ProviderCatalog builtin() {
        Map<String, ProviderCatalogEntry> entries = new LinkedHashMap<>();

        // ---------- OpenAI 系 ----------
        entries.put(
                "openai",
                new ProviderCatalogEntry(
                        "openai",
                        "openai",
                        ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE,
                        "https://api.openai.com",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.STRUCTURED_OUTPUT,
                                        Capability.TOOL_CALL,
                                        Capability.METERING,
                                        Capability.CACHED_INPUT)),
                        List.of("gpt-5-mini", "gpt-5", "gpt-4.1-mini", "gpt-4o-mini")));

        // ---------- Anthropic ----------
        entries.put(
                "anthropic",
                new ProviderCatalogEntry(
                        "anthropic",
                        "anthropic",
                        ProviderCatalogEntry.Protocol.ANTHROPIC,
                        "https://api.anthropic.com",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.TOOL_CALL,
                                        Capability.METERING,
                                        Capability.REASONING,
                                        Capability.CACHED_INPUT)),
                        List.of("claude-sonnet-4-5", "claude-haiku-4-5")));

        // ---------- Ollama（本地）----------
        entries.put(
                "ollama",
                new ProviderCatalogEntry(
                        "ollama",
                        "ollama",
                        ProviderCatalogEntry.Protocol.OLLAMA,
                        "http://localhost:11434",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.TOOL_CALL,
                                        Capability.METERING)),
                        List.of("qwen3:8b", "llama3.2:3b", "deepseek-r1:7b")));

        // ---------- 通义千问（OpenAI 兼容）----------
        entries.put(
                "qwen",
                new ProviderCatalogEntry(
                        "qwen",
                        "dashscope",
                        ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE,
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.STRUCTURED_OUTPUT,
                                        Capability.TOOL_CALL,
                                        Capability.METERING,
                                        Capability.CACHED_INPUT)),
                        List.of("qwen-max", "qwen-plus", "qwen-turbo")));

        // ---------- 智谱 GLM（OpenAI 兼容，支持 thinking）----------
        entries.put(
                "zhipu",
                new ProviderCatalogEntry(
                        "zhipu",
                        "zhipu",
                        ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE,
                        "https://open.bigmodel.cn/api/paas/v4",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.TOOL_CALL,
                                        Capability.METERING,
                                        Capability.REASONING)),
                        List.of("glm-4-plus", "glm-4-flash", "glm-4-air")));

        // ---------- DeepSeek（OpenAI 兼容，reasoning_content）----------
        entries.put(
                "deepseek",
                new ProviderCatalogEntry(
                        "deepseek",
                        "deepseek",
                        ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.TOOL_CALL,
                                        Capability.METERING,
                                        Capability.REASONING,
                                        Capability.CACHED_INPUT)),
                        List.of("deepseek-chat", "deepseek-reasoner")));

        // ---------- Kimi / 月之暗面（OpenAI 兼容）----------
        entries.put(
                "moonshot",
                new ProviderCatalogEntry(
                        "moonshot",
                        "moonshot",
                        ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE,
                        "https://api.moonshot.cn/v1",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.STRUCTURED_OUTPUT,
                                        Capability.TOOL_CALL,
                                        Capability.METERING,
                                        Capability.CACHED_INPUT)),
                        List.of("moonshot-v1-128k", "kimi-k2-0905-preview")));

        // ---------- 豆包 / 火山方舟（OpenAI 兼容）----------
        entries.put(
                "doubao",
                new ProviderCatalogEntry(
                        "doubao",
                        "doubao",
                        ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE,
                        "https://ark.cn-beijing.volces.com/api/v3",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.TOOL_CALL,
                                        Capability.METERING,
                                        Capability.CACHED_INPUT)),
                        List.of("doubao-pro-32k", "doubao-lite-32k")));

        // ---------- 硅基流动（OpenAI 兼容，一站多模型）----------
        entries.put(
                "siliconflow",
                new ProviderCatalogEntry(
                        "siliconflow",
                        "siliconflow",
                        ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE,
                        "https://api.siliconflow.cn/v1",
                        new ProviderCapabilities(
                                java.util.Set.of(
                                        Capability.STREAM,
                                        Capability.STRUCTURED_OUTPUT,
                                        Capability.TOOL_CALL,
                                        Capability.METERING)),
                        List.of("Qwen/Qwen3-8B", "deepseek-ai/DeepSeek-V3")));

        return new ProviderCatalog(entries);
    }

    /** 内置目录的全部条目。 */
    public List<ProviderCatalogEntry> all() {
        return List.copyOf(byProviderId.values());
    }

    /**
     * 按别名或 providerId 解析。
     *
     * <p>别名解析是「开箱即用」的关键：用户写 {@code qwen} 应该能拿到通义， 写 {@code dashscope} 也应该拿到同一个条目 —— 两个名字指的是同一家供应商。
     *
     * @param alias 别名或 providerId
     * @return 目录条目；未找到返回 {@link Optional#empty()}
     */
    public Optional<ProviderCatalogEntry> resolve(String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAlias.get(normalize(alias)));
    }

    /**
     * 解析并在失败时抛异常（配置错误应该立刻暴露，而不是运行时才发现）。
     *
     * @param alias 别名
     * @return 目录条目
     * @throws IllegalArgumentException 别名未知时
     */
    public ProviderCatalogEntry require(String alias) {
        return resolve(alias)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "未知的供应商别名："
                                                + alias
                                                + "，内置可用别名："
                                                + all().stream()
                                                        .map(ProviderCatalogEntry::alias)
                                                        .sorted()
                                                        .toList()));
    }

    /** 目录大小。 */
    public int size() {
        return byProviderId.size();
    }

    private static String normalize(String alias) {
        String key = alias.trim().toLowerCase(Locale.ROOT);
        // 常见中文/英文别名归一
        return switch (key) {
            case "通义", "tongyi", "aliyun", "alibaba" -> "qwen";
            case "智谱", "bigmodel", "glm" -> "zhipu";
            case "kimi", "月之暗面" -> "moonshot";
            case "豆包", "doubao", "volcengine", "ark" -> "doubao";
            case "硅基", "silicon" -> "siliconflow";
            case "claude" -> "anthropic";
            default -> key;
        };
    }
}
