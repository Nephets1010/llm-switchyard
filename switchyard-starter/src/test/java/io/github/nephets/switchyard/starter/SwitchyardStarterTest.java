/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.audit.AuditEvent;
import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.pricing.CostCalculator;
import io.github.nephets.switchyard.core.pricing.PriceSource;
import io.github.nephets.switchyard.core.provider.Capability;
import io.github.nephets.switchyard.core.provider.ProviderCatalog;
import io.github.nephets.switchyard.core.quota.InMemoryQuotaStore;
import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaPolicy;
import io.github.nephets.switchyard.core.usage.CharHeuristicTokenEstimator;
import io.github.nephets.switchyard.core.usage.DefaultUsageNormalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * starter 层的单元测试：配置绑定、路由门面、指标累加器、审计输出、价格表热更新调度。
 *
 * <p>这些是「不启动 Spring 容器也能验证」的部分，因此能在毫秒级跑完， 把集成测试留给真正需要 HTTP 的 {@code SampleSmokeIT}。
 */
@DisplayName("starter 层单元测试")
class SwitchyardStarterTest {

    /** 构造一份带一条路由与一个供应商的配置。 */
    private static SwitchyardProperties propertiesWithRoute() {
        SwitchyardProperties properties = new SwitchyardProperties();
        properties.setDefaultRoute("default");

        SwitchyardProperties.RouteConfig route = new SwitchyardProperties.RouteConfig();
        route.setPrimary("openai/gpt-5-mini");
        route.setFallbacks(java.util.List.of("deepseek/deepseek-chat"));
        properties.setRoutes(new java.util.LinkedHashMap<>(Map.of("default", route)));

        SwitchyardProperties.ProviderConfig provider = new SwitchyardProperties.ProviderConfig();
        provider.setApiKey("test-key");
        provider.setBaseUrl("http://localhost:1/v1");
        provider.setTimeout(java.time.Duration.ofSeconds(30));
        properties.setProviders(new java.util.LinkedHashMap<>(Map.of("openai", provider)));
        return properties;
    }

    /** 装配一个只用于测试的门面。 */
    private static SwitchyardRouter routerWith(SwitchyardProperties properties) {
        var quotaStore = new InMemoryQuotaStore();
        var breakers =
                new io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry(
                        name ->
                                new io.github.nephets.switchyard.core.resilience.CircuitBreaker(
                                        name));
        var pricingTable =
                new io.github.nephets.switchyard.core.pricing.PricingTable(java.util.List.of());
        var factory =
                new RoutedChatModelFactory(
                        properties,
                        quotaStore,
                        breakers,
                        new DefaultUsageNormalizer(),
                        new CharHeuristicTokenEstimator(),
                        new CostCalculator(),
                        pricingTable,
                        new io.github.nephets.switchyard.core.audit.RingBufferAuditSink(16),
                        io.github.nephets.switchyard.provider.support.ProviderHttpClientFactory
                                .create(java.time.Duration.ofSeconds(1), null),
                        io.github.nephets.switchyard.core.resilience.RetryPolicy.noRetry(),
                        new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier(),
                        new SwitchyardMetrics());
        return new SwitchyardRouter(
                properties, factory, key -> quotaStore.snapshot(key), breakers, pricingTable);
    }

    @Test
    @DisplayName("★ router.route() 返回的仍是标准 ChatModel（装饰器不改语义）")
    void routeReturnsStandardChatModel() {
        SwitchyardRouter router = routerWith(propertiesWithRoute());

        Object model = router.route("default");

        assertThat(model)
                .isInstanceOf(org.springframework.ai.chat.model.ChatModel.class)
                .isInstanceOf(RoutedChatModel.class);
        assertThat(router.routedModel("default")).isSameAs(model);
    }

    @Test
    @DisplayName("同一路由只创建一个装饰器实例（否则熔断器与 provider 连接会失真）")
    void routeIsCached() {
        SwitchyardRouter router = routerWith(propertiesWithRoute());

        assertThat(router.route("default")).isSameAs(router.route("default"));
        assertThat(router.route()).isSameAs(router.route("default"));
    }

    @Test
    @DisplayName("未配置的路由回落到默认路由，不抛异常")
    void unknownRouteFallsBack() {
        SwitchyardRouter router = routerWith(propertiesWithRoute());

        assertThat(router.route("不存在的路由")).isNotNull();
        assertThat(router.routeNames()).containsExactly("default");
    }

    @Test
    @DisplayName("health() 反映模型可用性、熔断状态与价格条目")
    void healthView() {
        SwitchyardProperties properties = propertiesWithRoute();
        SwitchyardRouter router = routerWith(properties);

        var health = router.health();
        assertThat(health).containsKey("openai/gpt-5-mini");

        var item = health.get("openai/gpt-5-mini");
        assertThat(item.available()).isTrue();
        assertThat(item.circuitState()).isEqualTo("CLOSED");
        assertThat(item.priceEntryKey()).isEqualTo("(无价格条目)");
    }

    @Test
    @DisplayName("内置目录可从门面取到，控制台据此渲染能力矩阵")
    void catalogIsExposed() {
        SwitchyardRouter router = routerWith(propertiesWithRoute());

        assertThat(router.catalog().size()).isGreaterThanOrEqualTo(8);
        assertThat(router.catalog().require("qwen").capabilities().supports(Capability.STREAM))
                .isTrue();
        assertThat(router.catalog().require("openai").defaultRef().modelId()).isNotBlank();
        assertThat(router.catalog().require("openai").protocol())
                .isEqualTo(
                        io.github.nephets.switchyard.core.provider.ProviderCatalogEntry.Protocol
                                .OPENAI_COMPATIBLE);
        assertThat(ProviderCatalog.builtin().all()).isNotEmpty();
    }

    @Test
    @DisplayName("★ SwitchyardMetrics 累加真实数字（控制台不是占位 0）")
    void metricsAccumulateRealNumbers() {
        SwitchyardMetrics metrics = new SwitchyardMetrics();
        var usage =
                new io.github.nephets.switchyard.core.usage.Usage(
                        1000,
                        200,
                        50,
                        400,
                        0,
                        io.github.nephets.switchyard.core.usage.UsageSource.VENDOR_REPORTED,
                        false,
                        true);

        metrics.recordSuccess(usage, new BigDecimal("0.00312500"), false);
        metrics.recordSuccess(usage, new BigDecimal("0.00187500"), true);
        metrics.recordFailure("SW_PROVIDER_UNAVAILABLE");
        metrics.recordQuotaRejection("vip/-/openai/gpt-5-mini");

        var snapshot = metrics.snapshot();
        @SuppressWarnings("unchecked")
        var counters = (Map<String, Object>) snapshot.get("counters");

        assertThat(snapshot.get("totalCalls")).isEqualTo(2L);
        assertThat(snapshot.get("totalFailures")).isEqualTo(1L);
        assertThat(snapshot.get("degradedCalls")).isEqualTo(1L);
        assertThat(new BigDecimal(String.valueOf(snapshot.get("totalCostCny"))))
                .isEqualByComparingTo("0.005");
        assertThat(counters)
                .containsEntry("tokens.input", 2000L)
                .containsEntry("tokens.cached", 800L);
        assertThat(counters).containsEntry("tokens.total", 2500L);
        assertThat(counters).containsKey("errors.SW_PROVIDER_UNAVAILABLE");
        assertThat(String.valueOf(snapshot.get("scope"))).as("控制台必须声明这是进程内数据而非账目").contains("重启归零");
    }

    @Test
    @DisplayName("★ 审计日志字段齐全，且不含 prompt 原文（PII 防护）")
    void auditLogIsStructuredAndRedacted() {
        LoggingAuditSinkAdapter sink = new LoggingAuditSinkAdapter();
        String prompt = "客户身份证号 110101199003071234，请核对";

        AuditEvent event =
                AuditEvent.modelCall(
                        "4bf92f3577b34da6a3ce929d0e0e4736",
                        "00f067aa0ba902b7",
                        "vip",
                        "customer-service",
                        "id-verify",
                        io.github.nephets.switchyard.core.usage.Usage.of(1000L, 200L),
                        new BigDecimal("0.00312500"),
                        prompt);

        sink.write(event);
        String json = sink.toJson(event);

        // 必含字段
        assertThat(json).contains("\"traceId\":\"" + event.traceId() + "\"");
        assertThat(json).contains("\"tenantId\":\"vip\"");
        assertThat(json).contains("\"bizLine\":\"customer-service\"");
        assertThat(json).contains("\"input\":1000");
        assertThat(json).contains("\"output\":200");
        assertThat(json).contains("\"source\":\"VENDOR_REPORTED\"");
        assertThat(json).contains("\"streamReported\":false");
        assertThat(json).contains("\"amount\":0.00312500");
        assertThat(json).contains("\"currency\":\"CNY\"");
        assertThat(json).contains("\"payloadDigest\":\"sha256:");
        // ★ 不得出现 prompt 原文
        assertThat(json).doesNotContain("110101199003071234");
        assertThat(json).contains("\"payloadFull\":null");
        assertThat(sink.name()).isEqualTo("logging");
    }

    @Test
    @DisplayName("审计序列化失败被吞掉，绝不影响主链路")
    void auditFailureIsSwallowed() {
        LoggingAuditSinkAdapter sink = new LoggingAuditSinkAdapter();

        // null 事件直接返回；构造一个必然序列化失败的极端输入也不应抛出
        sink.write(null);

        assertThat(
                        sink.toJson(
                                AuditEvent.modelCall(
                                        "t".repeat(32),
                                        "s".repeat(16),
                                        "vip",
                                        null,
                                        null,
                                        io.github.nephets.switchyard.core.usage.Usage.of(1L, 1L),
                                        BigDecimal.ONE,
                                        "p")))
                .contains("\"payloadFull\":null");
    }

    @Test
    @DisplayName("★ 价格表热更新：外部文件改价后 reload() 立即生效")
    void priceTableHotReload(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        SwitchyardProperties properties = propertiesWithRoute();
        var table = new io.github.nephets.switchyard.core.pricing.PricingTable(java.util.List.of());
        var loader = new io.github.nephets.switchyard.core.pricing.PricingTableLoader(table);
        var quotaStore = new InMemoryQuotaStore();

        Path file = dir.resolve("switchyard-prices.yml");
        java.nio.file.Files.writeString(
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
                java.nio.charset.StandardCharsets.UTF_8);

        var scheduler = new PricingTableRefreshScheduler(table, quotaStore, file.toString());
        scheduler.refresh();

        assertThat(table.lookup("openai", "gpt-5-mini"))
                .get()
                .satisfies(e -> assertThat(e.inputPricePerUnit()).isEqualByComparingTo("0.001"));

        // 供应商官降 → 改文件 → 调度器下一次 refresh 即生效，无需重启
        java.nio.file.Files.writeString(
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
                java.nio.charset.StandardCharsets.UTF_8);
        scheduler.refresh();

        assertThat(table.lookup("openai", "gpt-5-mini"))
                .get()
                .satisfies(
                        e -> {
                            assertThat(e.inputPricePerUnit()).isEqualByComparingTo("0.0008");
                            assertThat(e.effectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
                        });
    }

    @Test
    @DisplayName("★ 价格表损坏时保留旧表并告警（配置笔误不能升级成生产事故）")
    void brokenPriceTableKeepsOldData(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var table = new io.github.nephets.switchyard.core.pricing.PricingTable(java.util.List.of());
        var loader = new io.github.nephets.switchyard.core.pricing.PricingTableLoader(table);
        Path file = dir.resolve("bad.yml");
        java.nio.file.Files.writeString(
                file,
                "version: 1\nprices:\n  - providerId: openai\n    modelId: x\n",
                java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(
                dir.resolve("good.yml"),
                """
        version: 1
        prices:
          - providerId: openai
            modelId: gpt-5-mini
            inputPricePerUnit: 0.001
            outputPricePerUnit: 0.002
        """,
                java.nio.charset.StandardCharsets.UTF_8);
        loader.loadFromFile(dir.resolve("good.yml"));

        // 写入缺字段的坏 YAML
        java.nio.file.Files.writeString(
                dir.resolve("bad2.yml"),
                "version: 1\nprices:\n  - providerId: openai\n    modelId: y\n    outputPricePerUnit: 0.002\n",
                java.nio.charset.StandardCharsets.UTF_8);
        var scheduler =
                new PricingTableRefreshScheduler(table, null, dir.resolve("bad2.yml").toString());
        scheduler.refresh();

        assertThat(table.lookup("openai", "gpt-5-mini")).as("坏文件不得让价格表变空 —— 成本会静默变成 0").isPresent();
    }

    @Test
    @DisplayName("调度器在未配置外部文件时只清理过期预留")
    void schedulerWithoutExternalFile() {
        var table = new io.github.nephets.switchyard.core.pricing.PricingTable(java.util.List.of());
        var quotaStore = new InMemoryQuotaStore();
        var scheduler = new PricingTableRefreshScheduler(table, quotaStore, null);

        scheduler.refresh();

        assertThat(scheduler.lastLoadedCount()).isZero();
        assertThat(table.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("配置属性有安全的默认值")
    void configDefaults() {
        SwitchyardProperties properties = new SwitchyardProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDefaultRoute()).isEqualTo("default");
        assertThat(properties.getQuota().isEnabled()).isTrue();
        assertThat(properties.getQuota().getOnExceeded())
                .isEqualTo(SwitchyardProperties.OverLimitBehaviorName.FAIL_SOFT);
        assertThat(properties.getAudit().isCapturePayload()).as("★ 默认不落 prompt 原文").isFalse();
        assertThat(properties.getAudit().getType())
                .isEqualTo(SwitchyardProperties.AuditType.LOGGING);
        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getCircuitBreaker().getWindowSize()).isEqualTo(20);
        assertThat(properties.getConsole().getBasePath()).isEqualTo("/_switchyard");
        assertThat(properties.getMetering().getEstimatedTokensPerRequest()).isEqualTo(1000);
    }

    @Test
    @DisplayName("null 传入时配置对象回落到默认值而不是 NPE")
    void nullInputsFallBackToDefaults() {
        SwitchyardProperties properties = new SwitchyardProperties();

        properties.setRoutes(null);
        properties.setProviders(null);
        properties.setQuota(null);
        properties.setAudit(null);
        properties.setConsole(null);
        properties.setMetering(null);

        assertThat(properties.getRoutes()).isEmpty();
        assertThat(properties.getProviders()).isEmpty();
        assertThat(properties.getQuota()).isNotNull();
        assertThat(properties.getAudit()).isNotNull();
        assertThat(properties.getConsole().getBasePath()).isEqualTo("/_switchyard");

        SwitchyardProperties.RouteConfig route = new SwitchyardProperties.RouteConfig();
        route.setFallbacks(null);
        assertThat(route.getFallbacks()).isEmpty();

        SwitchyardProperties.ProviderConfig provider = new SwitchyardProperties.ProviderConfig();
        provider.setTimeout(null);
        provider.setBaseUrl("  ");
        assertThat(provider.getTimeout()).isNull();
    }

    @Test
    @DisplayName("★ 健康检查：有模型熔断但仍有可用模型时为 UP（熔断是网关在正常工作）")
    void healthIsUpWhenSomeModelsAvailable() {
        SwitchyardProperties properties = propertiesWithRoute();
        SwitchyardProperties.RouteConfig second = new SwitchyardProperties.RouteConfig();
        second.setPrimary("deepseek/deepseek-chat");
        properties.getRoutes().put("backup", second);

        var quotaStore = new InMemoryQuotaStore();
        var breakers = new io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry();
        // 健康检查要求价格表非空（否则无法算成本，那是另一个 DOWN 原因），这里给一条真实条目
        var pricingTable =
                new io.github.nephets.switchyard.core.pricing.PricingTable(
                        java.util.List.of(
                                io.github.nephets.switchyard.core.pricing.PriceEntry.of(
                                        "openai",
                                        "gpt-5-mini",
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        LocalDate.of(2026, 1, 1),
                                        PriceSource.OFFICIAL),
                                io.github.nephets.switchyard.core.pricing.PriceEntry.of(
                                        "deepseek",
                                        "deepseek-chat",
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        LocalDate.of(2026, 1, 1),
                                        PriceSource.OFFICIAL)));
        var factory =
                new RoutedChatModelFactory(
                        properties,
                        quotaStore,
                        breakers,
                        new DefaultUsageNormalizer(),
                        new CharHeuristicTokenEstimator(),
                        new CostCalculator(),
                        pricingTable,
                        new io.github.nephets.switchyard.core.audit.RingBufferAuditSink(8),
                        io.github.nephets.switchyard.provider.support.ProviderHttpClientFactory
                                .create(java.time.Duration.ofSeconds(1), null),
                        io.github.nephets.switchyard.core.resilience.RetryPolicy.noRetry(),
                        new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier(),
                        new SwitchyardMetrics());
        var router =
                new SwitchyardRouter(
                        properties,
                        factory,
                        key -> quotaStore.snapshot(key),
                        breakers,
                        pricingTable);
        var indicator = new SwitchyardHealthIndicator(router, pricingTable);

        // 把 gpt-5-mini 打到熔断，deepseek 仍健康
        var gptBreaker = breakers.breakerOf(ModelRef.of("openai", "gpt-5-mini"));
        for (int i = 0; i < 12; i++) {
            gptBreaker.recordFailure();
        }

        var health = indicator.health();
        assertThat(health.getStatus().getCode())
                .as("部分模型熔断不应让网关被判 DOWN —— 那是网关在替上游兜底，是正常工作")
                .isEqualTo("UP");
        assertThat(health.getDetails())
                .containsEntry("modelsCircuitOpen", 1L)
                .containsEntry("modelsAvailable", 1L)
                .containsEntry("degraded", true);
    }

    @Test
    @DisplayName("健康检查：无路由 / 价格表为空 / 全部熔断 → DOWN")
    void healthIsDownWhenUnusable() {
        var emptyRouter = routerWith(new SwitchyardProperties());
        var emptyTable =
                new io.github.nephets.switchyard.core.pricing.PricingTable(java.util.List.of());

        // ① 没有任何路由
        var noRoute = new SwitchyardHealthIndicator(emptyRouter, emptyTable).health();
        assertThat(noRoute.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(noRoute.getDetails()).containsEntry("reason", "未配置任何路由");

        // ② 价格表为空 → 无法算成本
        var props = propertiesWithRoute();
        var noPrice = new SwitchyardHealthIndicator(routerWith(props), emptyTable).health();
        assertThat(noPrice.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(noPrice.getDetails()).containsEntry("reason", "价格表为空，无法计算成本");

        // ③ 全部模型熔断
        var priced =
                new io.github.nephets.switchyard.core.pricing.PricingTable(
                        java.util.List.of(
                                io.github.nephets.switchyard.core.pricing.PriceEntry.of(
                                        "openai",
                                        "gpt-5-mini",
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        LocalDate.of(2026, 1, 1),
                                        PriceSource.OFFICIAL)));
        var breakers = new io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry();
        var breaker = breakers.breakerOf(ModelRef.of("openai", "gpt-5-mini"));
        for (int i = 0; i < 12; i++) {
            breaker.recordFailure();
        }
        var allOpen =
                new SwitchyardRouter(props, routerFactory(props), key -> null, breakers, priced);
        var open = new SwitchyardHealthIndicator(allOpen, priced).health();
        assertThat(open.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(open.getDetails()).containsEntry("reason", "所有模型均处于熔断状态");
    }

    /** 便捷工厂（仅健康检查测试用）。 */
    private static RoutedChatModelFactory routerFactory(SwitchyardProperties properties) {
        var quotaStore = new InMemoryQuotaStore();
        return new RoutedChatModelFactory(
                properties,
                quotaStore,
                new io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry(),
                new DefaultUsageNormalizer(),
                new CharHeuristicTokenEstimator(),
                new CostCalculator(),
                new io.github.nephets.switchyard.core.pricing.PricingTable(java.util.List.of()),
                new io.github.nephets.switchyard.core.audit.RingBufferAuditSink(4),
                io.github.nephets.switchyard.provider.support.ProviderHttpClientFactory.create(
                        java.time.Duration.ofSeconds(1), null),
                io.github.nephets.switchyard.core.resilience.RetryPolicy.noRetry(),
                new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier(),
                new SwitchyardMetrics());
    }

    @Test
    @DisplayName("CLI 的 --since 解析：30m / 2h / 7d")
    void cliSinceParsing() {
        var now = java.time.Instant.now();

        assertThat(io.github.nephets.switchyard.starter.shell.AuditCli.parseSince("30m"))
                .isBetween(now.minusSeconds(1801), now.minusSeconds(1799));
        assertThat(io.github.nephets.switchyard.starter.shell.AuditCli.parseSince("2h"))
                .isBetween(now.minusSeconds(7201), now.minusSeconds(7199));
        assertThat(io.github.nephets.switchyard.starter.shell.AuditCli.parseSince("7d"))
                .isBetween(now.minusSeconds(604801), now.minusSeconds(604799));
        assertThat(io.github.nephets.switchyard.starter.shell.AuditCli.parseSince(null))
                .isEqualTo(java.time.Instant.EPOCH);
        assertThat(io.github.nephets.switchyard.starter.shell.AuditCli.parseSince("乱写"))
                .isEqualTo(java.time.Instant.EPOCH);
    }

    @Test
    @DisplayName("工厂能从配置解析出完整模型链（主模型 + 降级）")
    void factoryResolvesChain() {
        SwitchyardProperties props = propertiesWithRoute();
        var factory = routerFactory(props);

        var router = routerWith(props);
        var routed = router.routedModel("default");

        assertThat(routed).isNotNull();
        assertThat(factory).isNotNull();
        assertThat(props.getRoutes().get("default").getPrimary()).isEqualTo("openai/gpt-5-mini");
        assertThat(props.getRoutes().get("default").getFallbacks())
                .containsExactly("deepseek/deepseek-chat");
    }

    @Test
    @DisplayName("★ QuotaStore 通过路由器可读写配额（控制台与 CLI 的基础）")
    void quotaStoreIsAccessible() {
        SwitchyardProperties props = propertiesWithRoute();
        var quotaStore = new InMemoryQuotaStore();
        var key = new QuotaKey("vip", null, "openai", "gpt-5-mini");
        quotaStore.putPolicy(
                key,
                new QuotaPolicy(
                        QuotaPolicy.Period.MONTHLY,
                        5_000_000L,
                        io.github.nephets.switchyard.core.quota.OverLimitBehavior.FAIL_SOFT));

        var router =
                new SwitchyardRouter(
                        props,
                        routerFactory(props),
                        k -> quotaStore.snapshot(k),
                        new io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry(),
                        new io.github.nephets.switchyard.core.pricing.PricingTable(
                                java.util.List.of()));

        QuotaSnapshotAsserts.assertRemaining(router.quotaOf(key), 5_000_000L);
    }

    /** 小工具：把配额断言集中在一处，避免测试里散落魔法数字。 */
    private static final class QuotaSnapshotAsserts {
        private QuotaSnapshotAsserts() {}

        static void assertRemaining(
                io.github.nephets.switchyard.core.quota.QuotaSnapshot snapshot, long expected) {
            assertThat(snapshot.remaining()).isEqualTo(expected);
            assertThat(snapshot.exceeded()).isFalse();
            assertThat(snapshot.utilization()).isZero();
        }
    }
}
