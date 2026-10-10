/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("价格表加载与热更新（无需改代码、无需重启）")
class PricingTableLoaderTest {

    private final PricingTable table = new PricingTable(List.of());
    private final PricingTableLoader loader = new PricingTableLoader(table);

    @Test
    @DisplayName("内置价格表能加载且覆盖全部 8 家供应商")
    void builtinTableLoads() {
        int count = loader.loadFromClasspath();

        assertThat(count).isGreaterThanOrEqualTo(8);
        assertThat(loader.lastError()).isNull();
        // 契约 conventions.providerIdValues 的 8 家都必须有价格条目
        for (String provider :
                List.of(
                        "openai",
                        "anthropic",
                        "ollama",
                        "dashscope",
                        "deepseek",
                        "zhipu",
                        "moonshot",
                        "doubao")) {
            assertThat(table.all().stream().anyMatch(e -> e.providerId().equals(provider)))
                    .as("内置价格表缺少 %s 的条目", provider)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("★ 改 yml 后 reload() 无需改代码即生效")
    void hotReloadTakesEffect(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("switchyard-prices.yml");
        Files.writeString(
                file,
                """
        version: 1
        prices:
          - providerId: openai
            modelId: gpt-5-mini
            inputPricePerUnit: 0.001
            outputPricePerUnit: 0.002
            effectiveFrom: 2026-01-01
            source: OFFICIAL
        """,
                StandardCharsets.UTF_8);

        assertThat(loader.loadFromFile(file)).isEqualTo(1);
        assertThat(table.lookup("openai", "gpt-5-mini"))
                .isPresent()
                .get()
                .satisfies(e -> assertThat(e.inputPricePerUnit()).isEqualByComparingTo("0.001"));
        assertThat(table.loadedAt()).isNotNull();

        // 供应商官降 → 改文件 → 热加载，新价格立即生效
        Files.writeString(
                file,
                """
        version: 1
        prices:
          - providerId: openai
            modelId: gpt-5-mini
            inputPricePerUnit: 0.0008
            outputPricePerUnit: 0.0016
            effectiveFrom: 2026-06-01
            source: OFFICIAL
        """,
                StandardCharsets.UTF_8);

        assertThat(loader.loadFromFile(file)).isEqualTo(1);
        assertThat(table.lookup("openai", "gpt-5-mini"))
                .get()
                .satisfies(
                        e -> {
                            assertThat(e.inputPricePerUnit()).isEqualByComparingTo("0.0008");
                            assertThat(e.effectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
                        });
    }

    @Test
    @DisplayName("★ 热更新失败时保留旧表，绝不让服务因配置笔误中断")
    void brokenFileKeepsOldTable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("switchyard-prices.yml");
        String good =
                """
        version: 1
        prices:
          - providerId: openai
            modelId: gpt-5-mini
            inputPricePerUnit: 0.001
            outputPricePerUnit: 0.002
        """;
        Files.writeString(file, good, StandardCharsets.UTF_8);
        loader.loadFromFile(file);

        Files.writeString(file, "prices: [ 这不是合法 YAML 的结构 ]]]", StandardCharsets.UTF_8);
        int result = loader.loadFromFile(file);

        assertThat(result).isEqualTo(-1);
        assertThat(loader.lastError()).isNotNull();
        assertThat(table.lookup("openai", "gpt-5-mini"))
                .as("解析失败必须保留旧价格表")
                .isPresent()
                .get()
                .satisfies(e -> assertThat(e.inputPricePerUnit()).isEqualByComparingTo("0.001"));
    }

    @Test
    @DisplayName("文件不存在时返回 -1 并记录原因，不抛异常")
    void missingFileIsSafe(@TempDir Path dir) {
        assertThat(loader.loadFromFile(dir.resolve("nope.yml"))).isEqualTo(-1);
        assertThat(loader.lastError()).contains("不存在");
    }

    @Test
    @DisplayName("缺少必填价格字段时明确报错而不是静默取 0")
    void missingPriceFieldRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.yml");
        Files.writeString(
                file,
                """
        version: 1
        prices:
          - providerId: openai
            modelId: gpt-5-mini
            outputPricePerUnit: 0.002
        """,
                StandardCharsets.UTF_8);

        assertThat(loader.loadFromFile(file)).isEqualTo(-1);
        assertThat(loader.lastError()).contains("inputPricePerUnit");
    }

    @Test
    @DisplayName("完整字段（含推理价与缓存价）能被正确解析")
    void allFieldsParsed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("full.yml");
        Files.writeString(
                file,
                """
        version: 1
        prices:
          - providerId: deepseek
            modelId: deepseek-reasoner
            currency: CNY
            unit: PER_1K_TOKENS
            inputPricePerUnit: 0.004
            outputPricePerUnit: 0.016
            reasoningPricePerUnit: 0.008
            cachedInputPricePerUnit: 0.001
            effectiveFrom: 2026-03-01
            source: ESTIMATED
        """,
                StandardCharsets.UTF_8);

        assertThat(loader.loadFromFile(file)).isEqualTo(1);
        PriceEntry entry = table.lookup("deepseek", "deepseek-reasoner").orElseThrow();

        assertThat(entry.reasoningPricePerUnit()).isEqualByComparingTo("0.008");
        assertThat(entry.cachedInputPricePerUnit()).isEqualByComparingTo("0.001");
        assertThat(entry.unit()).isEqualTo(PriceUnit.PER_1K_TOKENS);
        assertThat(entry.source()).isEqualTo(PriceSource.ESTIMATED);
    }

    @Test
    @DisplayName("价格表按 providerId/modelId 精确查找")
    void lookupByKey() {
        loader.parse(
                """
        version: 1
        prices:
          - providerId: zhipu
            modelId: glm-4-plus
            inputPricePerUnit: 0.05
            outputPricePerUnit: 0.05
        """);

        assertThat(table.lookup("zhipu", "glm-4-plus")).isPresent();
        assertThat(table.lookup("zhipu", "不存在的模型")).isEmpty();
        assertThat(table.lookup(null, null)).isEmpty();
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("只给 providerId 时，若该供应商只有一个模型则可兜底命中")
    void lookupFallsBackToSoleEntry() {
        loader.parse(
                """
        version: 1
        prices:
          - providerId: moonshot
            modelId: moonshot-v1-128k
            inputPricePerUnit: 0.012
            outputPricePerUnit: 0.012
        """);

        assertThat(table.lookup("moonshot", null)).isPresent();
    }

    @Test
    @DisplayName("空表与空文本不报错")
    void emptyInputs() {
        assertThat(loader.parse(null)).isZero();
        assertThat(loader.parse("")).isZero();
        assertThat(table.isEmpty()).isTrue();
    }
}
