/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.shell;

import io.github.nephets.switchyard.core.audit.AuditEvent;
import io.github.nephets.switchyard.core.audit.RingBufferAuditSink;
import io.github.nephets.switchyard.core.pricing.CostCalculator;
import io.github.nephets.switchyard.core.pricing.PriceEntry;
import io.github.nephets.switchyard.core.pricing.PriceSource;
import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.quota.InMemoryQuotaStore;
import io.github.nephets.switchyard.core.quota.OverLimitBehavior;
import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaPolicy;
import io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry;
import io.github.nephets.switchyard.core.usage.CharHeuristicTokenEstimator;
import io.github.nephets.switchyard.core.usage.DefaultUsageNormalizer;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.starter.RoutedChatModelFactory;
import io.github.nephets.switchyard.starter.SwitchyardMetrics;
import io.github.nephets.switchyard.starter.SwitchyardProperties;
import io.github.nephets.switchyard.starter.SwitchyardRouter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三条命令输出的<b>逐字节契约</b>。
 *
 * <p>收口到 {@link CliConsole} 之后，命令结果仍是产品输出：CI 与运维脚本按行解析这些表格。 所以这里把「分隔线长度」「分节顺序」「结论行位置」钉死 ——
 * 以后谁再动输出口，改的是实现而不是契约，这个测试会先把话说清楚。
 */
@DisplayName("CLI 输出契约：分隔线长度与分节顺序")
class CliOutputContractTest {

    private SwitchyardProperties properties;
    private InMemoryQuotaStore quotaStore;
    private CircuitBreakerRegistry breakers;
    private PricingTable pricingTable;
    private SwitchyardMetrics metrics;
    private SwitchyardRouter router;

    @BeforeEach
    void setUp() {
        properties = new SwitchyardProperties();
        properties.getProviders().put("openai", new SwitchyardProperties.ProviderConfig());
        properties.getRoutes().put("default", new SwitchyardProperties.RouteConfig());
        properties.getRoutes().get("default").setPrimary("openai/gpt-5-mini");
        properties.getRoutes().get("default").setFallbacks(List.of());

        quotaStore = new InMemoryQuotaStore();
        breakers = new CircuitBreakerRegistry();
        pricingTable =
                new PricingTable(
                        List.of(
                                PriceEntry.of(
                                        "openai",
                                        "gpt-5-mini",
                                        new BigDecimal("0.001"),
                                        new BigDecimal("0.002"),
                                        java.time.LocalDate.of(2026, 1, 1),
                                        PriceSource.OFFICIAL)));
        metrics = new SwitchyardMetrics();
        router =
                new SwitchyardRouter(
                        properties,
                        new StubFactory(),
                        key -> quotaStore.snapshot(key),
                        breakers,
                        pricingTable);
    }

    @Test
    @DisplayName("★ audit 表格分隔线是 120 个连字符，紧跟表头")
    void auditSeparatorIs120Dashes() {
        RingBufferAuditSink sink = new RingBufferAuditSink(10);
        sink.write(
                AuditEvent.modelCall(
                        "4bf92f3577b34da6a3ce929d0e0e4736",
                        "00f067aa0ba902b7",
                        "vip",
                        "customer-service",
                        "id-verify",
                        Usage.of(100L, 50L),
                        new BigDecimal("0.0012"),
                        "身份证 110101199003071234"));

        String text = capture(() -> new AuditCli(parentWith(sink)).onCommand());
        List<String> lines = text.lines().toList();

        assertThat(lines.get(0)).startsWith("TRACED");
        assertThat(lines.get(1)).isEqualTo("-".repeat(120));
        assertThat(text).contains("不落 prompt 原文");
    }

    @Test
    @DisplayName("★ quota 表格分隔线是 102 个连字符，紧跟表头")
    void quotaSeparatorIs102Dashes() {
        QuotaKey key =
                new QuotaKey(
                        properties.getQuota().getDefaultTenant(), null, "openai", "gpt-5-mini");
        quotaStore.putPolicy(
                key,
                new QuotaPolicy(QuotaPolicy.Period.DAILY, 10_000L, OverLimitBehavior.FAIL_SOFT));
        quotaStore.settle(quotaStore.reserve(key, 1_500L), Usage.of(1_500L, 0L));

        String text = capture(() -> new QuotaCli(parentWith(null)).onCommand());
        List<String> lines = text.lines().toList();

        assertThat(lines.get(0)).startsWith("TENANT");
        assertThat(lines.get(1)).isEqualTo("-".repeat(102));
        assertThat(lines).anyMatch(line -> line.contains("1500"));
    }

    @Test
    @DisplayName("★ doctor 四个分节按序出现，最后一行是结论")
    void doctorSectionsInOrderEndsWithVerdict() {
        String text = capture(() -> new DoctorCli(parentWith(null)).onCommand());

        int first = text.indexOf("=== 1. 路由配置 ===");
        int second = text.indexOf("=== 2. 价格表 ===");
        int third = text.indexOf("=== 3. 供应商目录 ===");
        int fourth = text.indexOf("=== 4. 模型健康 ===");

        assertThat(first).isNotNegative();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
        assertThat(fourth).isGreaterThan(third);

        List<String> nonEmpty =
                text.lines().filter(line -> !line.isBlank()).map(String::strip).toList();
        assertThat(nonEmpty.get(nonEmpty.size() - 1)).startsWith("结论：");
    }

    /** 只为让 Router 有个可用的工厂：doctor / quota / audit 三条命令都不触发 {@code route()}， 所以这个工厂不会被调用。 */
    private final class StubFactory extends RoutedChatModelFactory {
        StubFactory() {
            super(
                    properties,
                    quotaStore,
                    breakers,
                    new DefaultUsageNormalizer(),
                    new CharHeuristicTokenEstimator(),
                    new CostCalculator(),
                    pricingTable,
                    new RingBufferAuditSink(10),
                    java.net.http.HttpClient.newHttpClient(),
                    io.github.nephets.switchyard.core.resilience.RetryPolicy.defaults(),
                    new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier(),
                    metrics);
        }
    }

    /** 构造一个根命令，审计源按入参取。 */
    private SwitchyardCli parentWith(RingBufferAuditSink sink) {
        return new SwitchyardCli(
                router,
                pricingTable,
                tenantId -> sink == null ? List.of() : sink.events(),
                quotaStore);
    }

    /** 在替换过标准输出的上下文里跑一段命令，返回它写出的全部内容。 */
    private static String capture(Runnable command) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            command.run();
        } finally {
            System.setOut(saved);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
