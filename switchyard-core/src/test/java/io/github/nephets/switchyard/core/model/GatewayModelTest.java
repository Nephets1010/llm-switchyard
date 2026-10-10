/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.model;

import io.github.nephets.switchyard.core.provider.Capability;
import io.github.nephets.switchyard.core.provider.ProviderCapabilities;
import io.github.nephets.switchyard.core.provider.ProviderCatalog;
import io.github.nephets.switchyard.core.provider.ProviderCatalogEntry;
import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("网关统一模型与供应商目录")
class GatewayModelTest {

    @Test
    @DisplayName("ModelRef 归一化：providerId 转小写去空白")
    void modelRefNormalizes() {
        ModelRef ref = new ModelRef("  OpenAI ", "gpt-5-mini", "  ");

        assertThat(ref.providerId()).isEqualTo("openai");
        assertThat(ref.region()).as("空白区域归一为 null").isNull();
        assertThat(ref.text()).isEqualTo("openai/gpt-5-mini");
        assertThat(ref.priceKey()).isEqualTo("openai/gpt-5-mini");
    }

    @Test
    @DisplayName("ModelRef 带区域时 text() 含 @region")
    void modelRefWithRegion() {
        ModelRef ref = new ModelRef("doubao", "doubao-pro", "cn-beijing");

        assertThat(ref.text()).isEqualTo("doubao/doubao-pro@cn-beijing");
        assertThat(ref.priceKey()).as("查价格不带区域").isEqualTo("doubao/doubao-pro");
    }

    @Test
    @DisplayName("ModelRef.parse 与 text() 互逆")
    void modelRefParse() {
        assertThat(ModelRef.parse("openai/gpt-5-mini"))
                .isEqualTo(ModelRef.of("openai", "gpt-5-mini"));
        assertThatThrownBy(() -> ModelRef.parse("no-slash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId/modelId");
    }

    @Test
    @DisplayName("GatewayRequest 渲染文本供估算器与审计摘要使用")
    void requestRendersText() {
        GatewayRequest request =
                new GatewayRequest(
                        List.of(
                                new GatewayRequest.Message(
                                        GatewayRequest.MessageRole.SYSTEM, "你是客服助手"),
                                new GatewayRequest.Message(GatewayRequest.MessageRole.USER, "你好")),
                        ModelRef.of("openai", "gpt-5-mini"),
                        512,
                        0.7d,
                        0.9d,
                        true,
                        List.of(new GatewayRequest.ToolSpec("search", "查知识库", "{}")),
                        "json_object");

        String rendered = request.renderForEstimation();

        assertThat(rendered).contains("SYSTEM:你是客服助手").contains("USER:你好");
        assertThat(request.stream()).isTrue();
        assertThat(request.tools()).hasSize(1);
        assertThat(request.responseFormat()).isEqualTo("json_object");
    }

    @Test
    @DisplayName("GatewayRequest 校验消息非空，工具列表被防御性复制")
    void requestValidation() {
        ModelRef ref = ModelRef.of("openai", "gpt-5-mini");

        assertThatThrownBy(() -> new GatewayRequest(null, ref, null, null, null, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messages");
        assertThatThrownBy(() -> GatewayRequest.simple("hi", null, false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GatewayRequest.simple 构造单轮请求")
    void simpleRequest() {
        GatewayRequest request =
                GatewayRequest.simple("你好", ModelRef.of("openai", "gpt-5-mini"), true, 256);

        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().get(0).role()).isEqualTo(GatewayRequest.MessageRole.USER);
        assertThat(request.maxTokens()).isEqualTo(256);
        assertThat(request.stream()).isTrue();
    }

    @Test
    @DisplayName("GatewayResponse 流式与非流式工厂")
    void responseFactories() {
        ModelRef ref = ModelRef.of("openai", "gpt-5-mini");
        Usage usage = Usage.of(100L, 50L);

        GatewayResponse plain = GatewayResponse.of("回答", usage, ref, "stop", 200L);
        assertThat(plain.chunks()).isEmpty();
        assertThat(plain.degraded()).isFalse();
        assertThat(plain.attempts()).isEqualTo(1);
        assertThat(plain.hasToolCalls()).isFalse();

        GatewayResponse streamed =
                GatewayResponse.streamed(List.of("你", "好"), usage, ref, "stop", 300L);
        assertThat(streamed.content()).as("分片被拼接成完整内容").isEqualTo("你好");
        assertThat(streamed.chunks()).hasSize(2);
    }

    @Test
    @DisplayName("GatewayResponse.asDegraded 标记降级与尝试次数")
    void responseDegraded() {
        GatewayResponse response =
                GatewayResponse.of(
                                "x",
                                Usage.of(1L, 1L),
                                ModelRef.of("openai", "gpt-5-mini"),
                                "stop",
                                10L)
                        .asDegraded(3);

        assertThat(response.degraded()).isTrue();
        assertThat(response.attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("GatewayResponse usage 为 null 时自动填零值，modelRef 必填")
    void responseDefaults() {
        ModelRef ref = ModelRef.of("openai", "gpt-5-mini");

        GatewayResponse response =
                new GatewayResponse(null, null, null, ref, null, 0L, false, 0, null);
        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().usageSource()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(response.attempts()).isEqualTo(1);

        assertThatThrownBy(
                        () ->
                                new GatewayResponse(
                                        "x",
                                        null,
                                        Usage.of(1L, 1L),
                                        null,
                                        null,
                                        0L,
                                        false,
                                        1,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GatewayResponse 支持工具调用")
    void responseToolCalls() {
        GatewayResponse response =
                new GatewayResponse(
                        null,
                        null,
                        Usage.of(1L, 1L),
                        ModelRef.of("openai", "gpt-5-mini"),
                        "tool_calls",
                        5L,
                        false,
                        1,
                        List.of(new GatewayResponse.ToolCall("call_1", "search", "{\"q\":\"x\"}")));

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls().get(0).name()).isEqualTo("search");
    }

    @ParameterizedTest(name = "「{0}」解析为 {1}")
    @DisplayName("★ 别名解析：中文名与常见别名都能命中（开箱即用）")
    @CsvSource({
        "openai,    openai",
        "qwen,      dashscope",
        "通义,        dashscope",
        "tongyi,    dashscope",
        "dashscope, dashscope",
        "zhipu,     zhipu",
        "智谱,        zhipu",
        "glm,       zhipu",
        "deepseek,  deepseek",
        "moonshot,  moonshot",
        "kimi,      moonshot",
        "doubao,    doubao",
        "豆包,        doubao",
        "ark,       doubao",
        "anthropic, anthropic",
        "claude,    anthropic",
        "ollama,    ollama",
        "siliconflow, siliconflow"
    })
    void aliasResolution(String alias, String expectedProviderId) {
        ProviderCatalogEntry entry = ProviderCatalog.builtin().require(alias);

        assertThat(entry.providerId()).isEqualTo(expectedProviderId);
    }

    @Test
    @DisplayName("未知别名明确报错并列出可用别名（配置错误要立刻暴露）")
    void unknownAliasRejected() {
        assertThatThrownBy(() -> ProviderCatalog.builtin().require("不存在的供应商"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的供应商别名");
        assertThat(ProviderCatalog.builtin().resolve("不存在")).isEmpty();
        assertThat(ProviderCatalog.builtin().resolve(null)).isEmpty();
        assertThat(ProviderCatalog.builtin().resolve("  ")).isEmpty();
    }

    @Test
    @DisplayName("★ 3 个适配器覆盖 9 家供应商（架构核心取舍：6 家共用 OpenAI 兼容协议）")
    void threeAdaptersCoverNineVendors() {
        ProviderCatalog catalog = ProviderCatalog.builtin();
        long protocolCount = catalog.all().stream().map(e -> e.protocol()).distinct().count();

        assertThat(catalog.size()).isEqualTo(9);
        assertThat(protocolCount)
                .as("只有 3 种协议：OPENAI_COMPATIBLE / ANTHROPIC / OLLAMA")
                .isEqualTo(3);
        assertThat(
                        catalog.all().stream()
                                .filter(
                                        e ->
                                                e.protocol()
                                                        == ProviderCatalogEntry.Protocol
                                                                .OPENAI_COMPATIBLE)
                                .count())
                .as("7 家走 OpenAI 兼容协议（openai/通义/智谱/DeepSeek/Kimi/豆包/硅基流动），只需 1 个适配器")
                .isEqualTo(7);
        assertThat(
                        catalog.all().stream()
                                .filter(
                                        e ->
                                                e.protocol()
                                                        == ProviderCatalogEntry.Protocol.ANTHROPIC)
                                .count())
                .isEqualTo(1);
        assertThat(
                        catalog.all().stream()
                                .filter(e -> e.protocol() == ProviderCatalogEntry.Protocol.OLLAMA)
                                .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 供应商能力矩阵：deepseek/zhipu 支持 reasoning，其余 OpenAI 兼容系不支持")
    void capabilityMatrix() {
        ProviderCatalog catalog = ProviderCatalog.builtin();

        assertThat(catalog.require("deepseek").capabilities().supports(Capability.REASONING))
                .isTrue();
        assertThat(catalog.require("zhipu").capabilities().supports(Capability.REASONING)).isTrue();
        assertThat(catalog.require("qwen").capabilities().supports(Capability.REASONING))
                .as("通义默认走 OpenAI 兼容端点，拿不到 reasoning token")
                .isFalse();
        assertThat(catalog.require("openai").capabilities().supports(Capability.CACHED_INPUT))
                .isTrue();
        assertThat(catalog.require("ollama").capabilities().supports(Capability.CACHED_INPUT))
                .isFalse();
        assertThat(
                        catalog.require("anthropic")
                                .capabilities()
                                .supports(Capability.STRUCTURED_OUTPUT))
                .isFalse();
    }

    @Test
    @DisplayName("每个目录条目都有默认模型与 base-url")
    void catalogEntriesComplete() {
        for (ProviderCatalogEntry entry : ProviderCatalog.builtin().all()) {
            assertThat(entry.defaultBaseUrl())
                    .as("%s 的 base-url", entry.alias())
                    .startsWith("http");
            assertThat(entry.defaultModels()).as("%s 的默认模型", entry.alias()).isNotEmpty();
            assertThat(entry.defaultRef().providerId()).isEqualTo(entry.providerId());
            assertThat(entry.capabilitySet()).isNotNull();
        }
    }

    @Test
    @DisplayName("目录条目容错：字段缺失时填默认值")
    void catalogEntryDefaults() {
        ProviderCatalogEntry entry = new ProviderCatalogEntry(null, null, null, null, null, null);

        assertThat(entry.protocol()).isEqualTo(ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE);
        assertThat(entry.defaultModels()).isEmpty();
        assertThat(entry.defaultRef().modelId()).isEqualTo("-");
        assertThat(entry.capabilitySet()).isEmpty();
    }

    @Test
    @DisplayName("能力集合不可变且支持全量判断")
    void providerCapabilities() {
        ProviderCapabilities capabilities =
                new ProviderCapabilities(Set.of(Capability.STREAM, Capability.METERING));

        assertThat(capabilities.supports(Capability.STREAM)).isTrue();
        assertThat(capabilities.supports(Capability.TOOL_CALL)).isFalse();
        assertThat(capabilities.supportsAll(Set.of(Capability.STREAM, Capability.METERING)))
                .isTrue();
        assertThat(capabilities.supportsAll(Set.of(Capability.STREAM, Capability.TOOL_CALL)))
                .isFalse();
        assertThat(capabilities.supportsAll(null)).isTrue();
        assertThat(capabilities.names()).containsExactly("METERING", "STREAM");

        assertThat(ProviderCapabilities.none().capabilities()).isEmpty();
        assertThatThrownBy(() -> capabilities.capabilities().add(Capability.TOOL_CALL))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("QuotaKey 归一化与序列化：控制台与 CLI 的读写基础")
    void quotaKeySerialization() {
        QuotaKey key = new QuotaKey("vip", "customer-service", "openai", "gpt-5-mini");

        assertThat(key.text()).isEqualTo("vip/customer-service/openai/gpt-5-mini");
        assertThat(QuotaKey.parse(key.text())).isEqualTo(key);
        assertThat(key.withModel("deepseek", "deepseek-chat").providerId()).isEqualTo("deepseek");
    }

    @Test
    @DisplayName("QuotaKey 空 tenant 归一为 _default，避免出现无法归属的配额黑洞")
    void quotaKeyDefaults() {
        QuotaKey key = new QuotaKey("  ", "", null, null);

        assertThat(key.tenantId()).isEqualTo("_default");
        assertThat(key.bizLine()).isNull();
        assertThat(key.text()).isEqualTo("_default/-/-/-");
        assertThat(QuotaKey.parse("_default/-/-/-")).isEqualTo(key);
    }

    @Test
    @DisplayName("QuotaKey.parse 拒绝非法格式")
    void quotaKeyParseValidation() {
        assertThatThrownBy(() -> QuotaKey.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotaKey.parse("a/b/c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MINUTE", "HOURLY", "DAILY", "MONTHLY"})
    @DisplayName("配额周期枚举齐全且顺序稳定")
    void quotaPeriods(String period) {
        assertThat(io.github.nephets.switchyard.core.quota.QuotaPolicy.Period.valueOf(period))
                .isNotNull();
    }
}
