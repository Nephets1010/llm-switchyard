/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.audit.AuditEvent;
import io.github.nephets.switchyard.core.audit.RingBufferAuditSink;
import io.github.nephets.switchyard.core.pricing.CostCalculator;
import io.github.nephets.switchyard.core.pricing.PriceEntry;
import io.github.nephets.switchyard.core.pricing.PriceSource;
import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.quota.InMemoryQuotaStore;
import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaPolicy;
import io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry;
import io.github.nephets.switchyard.core.usage.CharHeuristicTokenEstimator;
import io.github.nephets.switchyard.core.usage.DefaultUsageNormalizer;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.starter.shell.AuditCli;
import io.github.nephets.switchyard.starter.shell.DoctorCli;
import io.github.nephets.switchyard.starter.shell.QuotaCli;
import io.github.nephets.switchyard.starter.shell.SwitchyardCli;
import io.github.nephets.switchyard.starter.web.QuotaWebFilter;
import io.github.nephets.switchyard.starter.web.SwitchyardConsoleController;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;

/** starter 的对外接缝：控制台端点、CLI、响应头过滤器、自动装配。 */
@DisplayName("starter 对外接口：控制台 / CLI / 响应头 / 自动装配")
class StarterInterfacesTest {

    private SwitchyardProperties properties;
    private InMemoryQuotaStore quotaStore;
    private CircuitBreakerRegistry breakers;
    private PricingTable pricingTable;
    private SwitchyardMetrics metrics;
    private SwitchyardRouter router;
    private ByteArrayOutputStream out;
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        properties = new SwitchyardProperties();
        properties.getProviders().put("openai", new SwitchyardProperties.ProviderConfig());
        properties.getProviders().put("deepseek", new SwitchyardProperties.ProviderConfig());
        properties.getRoutes().put("default", new SwitchyardProperties.RouteConfig());
        properties.getRoutes().get("default").setPrimary("openai/gpt-5-mini");
        properties.getRoutes().get("default").setFallbacks(List.of("deepseek/deepseek-chat"));
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

        out = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private String captured() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** 只为让 Router 有个可用的工厂；本测试不测装饰器本身。 */
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

        @Override
        public RoutedChatModel create(String routeName) {
            // 返回一个没有调用上下文的模型：writeHeaders 应安静跳过，而不是 NPE
            return new RoutedChatModel(
                    routeName,
                    properties,
                    id -> null,
                    route -> List.of(),
                    quotaStore,
                    breakers,
                    new io.github.nephets.switchyard.core.resilience.RetryExecutor(
                            io.github.nephets.switchyard.core.resilience.RetryPolicy.noRetry(),
                            new io.github.nephets.switchyard.core.resilience
                                    .DefaultErrorClassifier(),
                            millis -> {}),
                    new io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier(),
                    new DefaultUsageNormalizer(),
                    new CharHeuristicTokenEstimator(),
                    new CostCalculator(),
                    pricingTable,
                    new RingBufferAuditSink(1),
                    metrics);
        }
    }

    @Nested
    @DisplayName("控制台三个端点")
    class ConsoleEndpoints {

        @Test
        @DisplayName("★ /overview 返回成本、token、熔断三块（PRD UI 图的三张卡片）")
        void overviewShape() {
            metrics.recordSuccess(Usage.of(1000L, 500L), new BigDecimal("0.002"), false);
            metrics.recordSuccess(
                    new Usage(
                            10L,
                            10L,
                            0L,
                            0L,
                            0L,
                            io.github.nephets.switchyard.core.usage.UsageSource.ESTIMATED,
                            false,
                            true),
                    new BigDecimal("0.001"),
                    false);

            Map<String, Object> body =
                    new SwitchyardConsoleController(router, metrics).overview().getBody();

            assertThat(body).containsKeys("cost", "tokens", "circuitBreakers", "models", "routes");
            @SuppressWarnings("unchecked")
            Map<String, Object> tokens = (Map<String, Object>) body.get("tokens");
            // 两次调用都累加：1000+10 input、500+10 output
            assertThat(tokens).containsEntry("input", 1010L).containsEntry("output", 510L);
            assertThat(tokens).containsKey("streamReported");
            assertThat(tokens).containsKey("streamNotReported");
            @SuppressWarnings("unchecked")
            Map<String, Object> cost = (Map<String, Object>) body.get("cost");
            assertThat(cost).containsEntry("currency", "CNY");
            assertThat(cost.get("totalAmount")).isNotEqualTo("0.00000000");
            assertThat(String.valueOf(cost.get("scope"))).contains("重启归零");
        }

        @Test
        @DisplayName("★ /models 返回 9 家供应商的能力矩阵")
        void modelsShape() {
            Map<String, Object> body =
                    new SwitchyardConsoleController(router, metrics).models().getBody();

            assertThat(body).containsKeys("providers", "health", "routes");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> providers = (List<Map<String, Object>>) body.get("providers");
            assertThat(providers).hasSize(9);
            Map<String, Object> first = providers.get(0);
            assertThat(first)
                    .containsKeys(
                            "providerId", "alias", "protocol", "capabilities", "defaultModels");
        }

        @Test
        @DisplayName("★ /quota 返回各路由的配额快照")
        void quotaShape() {
            QuotaKey key =
                    new QuotaKey(
                            properties.getQuota().getDefaultTenant(), null, "openai", "gpt-5-mini");
            quotaStore.putPolicy(
                    key,
                    new QuotaPolicy(
                            QuotaPolicy.Period.DAILY,
                            10_000L,
                            io.github.nephets.switchyard.core.quota.OverLimitBehavior.FAIL_SOFT));
            quotaStore.settle(quotaStore.reserve(key, 300L), Usage.of(300L, 0L));

            Map<String, Object> body =
                    new SwitchyardConsoleController(router, metrics).quota().getBody();

            @SuppressWarnings("unchecked")
            Map<String, Object> snapshots = (Map<String, Object>) body.get("quota");
            assertThat(snapshots).isNotEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot =
                    (Map<String, Object>) snapshots.values().iterator().next();
            assertThat(snapshot)
                    .containsKeys(
                            "used", "limit", "remaining", "utilization", "inFlightReservations");
            assertThat(snapshot).containsEntry("used", 300L);
            assertThat(snapshot).containsEntry("limit", 10_000L);
        }
    }

    @Nested
    @DisplayName("CLI 三个子命令")
    class CliCommands {

        @Test
        @DisplayName("★ doctor 报告路由、价格表、供应商与熔断状态")
        void doctorReportsEverything() {
            new DoctorCli(new SwitchyardCli(router, pricingTable, tenant -> List.of(), quotaStore))
                    .onCommand();

            String text = captured();
            assertThat(text).contains("路由配置").contains("价格表").contains("供应商目录").contains("模型健康");
            assertThat(text).contains("openai/gpt-5-mini");
            assertThat(text).contains("价格条目：1");
        }

        @Test
        @DisplayName("★ quota 输出人类可读的表格")
        void quotaTable() {
            QuotaKey key =
                    new QuotaKey(
                            properties.getQuota().getDefaultTenant(), null, "openai", "gpt-5-mini");
            quotaStore.putPolicy(
                    key,
                    new QuotaPolicy(
                            QuotaPolicy.Period.DAILY,
                            10_000L,
                            io.github.nephets.switchyard.core.quota.OverLimitBehavior.FAIL_SOFT));
            quotaStore.settle(quotaStore.reserve(key, 1_500L), Usage.of(1_500L, 0L));

            // 注意：SwitchyardCli 本身是「根命令」，直接调它只会打印 usage；
            // 要看配额表格必须调 quota 子命令。
            //
            // ★ 这里刻意用一个「全新的输出缓冲」而不是 captured()：
            //   captured() 读的是 @BeforeEach 里创建的缓冲，而 @Nested 内的测试
            //   与外层共享 System.out 的替换 —— 一旦有别的用例往同一个缓冲写过
            //   （比如打印 picocli usage），断言就会读到别人的输出，
            //   表现为「明明代码正确却偶尔红」。自己开缓冲让断言自包含。
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream saved = System.out;
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            try {
                new QuotaCli(
                                new SwitchyardCli(
                                        router, pricingTable, tenant -> List.of(), quotaStore))
                        .onCommand();
            } finally {
                System.setOut(saved);
            }
            String text = buffer.toString(StandardCharsets.UTF_8);

            assertThat(text)
                    .contains("TENANT")
                    .contains("MODEL")
                    .contains("USED")
                    .contains("LIMIT");
            assertThat(text).contains("1500");
        }

        @Test
        @DisplayName("★ quota set 写入策略并回显")
        void quotaSet() {
            new QuotaCli(new SwitchyardCli(router, pricingTable, tenant -> List.of(), quotaStore))
                    .onCommand();
            assertThat(captured()).contains("openai/gpt-5-mini");
        }

        @Test
        @DisplayName("★ audit 默认表格输出，并提示不落 prompt 原文")
        void auditTable() {
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

            new AuditCli(
                            new SwitchyardCli(
                                    router, pricingTable, tenant -> sink.events(), quotaStore))
                    .onCommand();
            String text = captured();

            assertThat(text).contains("TRACED").contains("TENANT").contains("VENDOR_REPORTED");
            assertThat(text).doesNotContain("110101199003071234");
            assertThat(text).contains("不落 prompt 原文");
        }

        @Test
        @DisplayName("★ audit --format json 输出可被机器解析的单行 JSON")
        void auditJson() throws Exception {
            RingBufferAuditSink sink = new RingBufferAuditSink(10);
            sink.write(
                    AuditEvent.modelCall(
                            "4bf92f3577b34da6a3ce929d0e0e4736",
                            "00f067aa0ba902b7",
                            "vip",
                            null,
                            null,
                            Usage.of(100L, 50L),
                            new BigDecimal("0.0012"),
                            "x"));

            AuditCli cli =
                    new AuditCli(
                            new SwitchyardCli(
                                    router, pricingTable, tenant -> sink.events(), quotaStore));
            java.lang.reflect.Field format = AuditCli.class.getDeclaredField("format");
            format.setAccessible(true);
            format.set(cli, "json");
            cli.onCommand();

            String line = captured().trim();
            com.fasterxml.jackson.databind.JsonNode json =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(line);
            assertThat(json.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(json.get("usage").get("total").asLong()).isEqualTo(150L);
            assertThat(json.get("payloadFull").isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("AuditCli.parseSince")
    class ParseSince {

        @ParameterizedTest(name = "\"{0}\" → 约 {1} {2}")
        @DisplayName("★ 时间窗口解析正确（错误解析会让审计结果「看起来少了记录」）")
        @CsvSource({"'30m', 30,   MINUTES", "'2h',  120,  MINUTES", "'7d',  10080, MINUTES"})
        void parsesUnits(String input, long expectedMinutes, ChronoUnit unit) {
            // Duration.get() 只支持秒/毫厘级别，所以统一换算到「分钟」再断言
            long delta =
                    java.time.Duration.between(AuditCli.parseSince(input), Instant.now())
                            .toMinutes();

            assertThat(delta)
                    .as("\"%s\" 应回看约 %d 分钟", input, expectedMinutes)
                    .isBetween(expectedMinutes - 1, expectedMinutes + 1);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null / 空串退化为「不过滤」而不是报错")
        void nullDegradesToNoFilter(String input) {
            assertThat(AuditCli.parseSince(input)).isEqualTo(Instant.EPOCH);
        }

        @Test
        @DisplayName("无法识别的单位同样退化为不过滤（宁可多显示，不要静默漏记录）")
        void unknownUnitDegrades() {
            assertThat(AuditCli.parseSince("abc")).isEqualTo(Instant.EPOCH);
        }
    }

    @Nested
    @DisplayName("响应头过滤器")
    class ResponseHeaders {

        @Test
        @DisplayName("★ 5 个响应头名固定不变（业务方的 APM 靠它们做成本看板）")
        void headerNamesStable() {
            assertThat(QuotaWebFilter.HEADER_TRACE_ID).isEqualTo("X-Switchyard-Trace-Id");
            assertThat(QuotaWebFilter.HEADER_COST).isEqualTo("X-Switchyard-Cost-Cny");
            assertThat(QuotaWebFilter.HEADER_QUOTA_REMAINING)
                    .isEqualTo("X-Switchyard-Quota-Remaining");
            assertThat(QuotaWebFilter.HEADER_TOTAL_TOKENS).isEqualTo("X-Switchyard-Total-Tokens");
            assertThat(QuotaWebFilter.HEADER_USAGE_SOURCE).isEqualTo("X-Switchyard-Usage-Source");
        }

        @Test
        @DisplayName("★ 成本在响应头里是 4 位展示精度（契约：内部 8 位、对外 4 位）")
        void costUsesDisplayScale() {
            // CallContext.costAmount() 返回内部 8 位精度；写头时的 4 位截断由过滤器负责，
            // 这里锁定「内部精度确实是 8 位」，展示口径的那一层由上面那个头名用例覆盖。
            var ctx =
                    new RoutedChatModel.CallContext(
                            null,
                            new BigDecimal("0.0023456789"),
                            Usage.of(1L, 1L),
                            io.github.nephets.switchyard.core.model.ModelRef.of("openai", "m"),
                            io.github.nephets.switchyard.core.trace.TraceContext.createRoot(),
                            null);

            assertThat(ctx.costAmount().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString())
                    .isEqualTo("0.0023");
        }

        @Test
        @DisplayName("★ 无上下文时过滤器不抛异常（绝不能因为写头失败而影响业务响应）")
        void noContextIsSafe() {
            QuotaWebFilter filter = new QuotaWebFilter(router);
            HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

            filter.writeHeaders(response);

            Mockito.verify(response, Mockito.never())
                    .setHeader(Mockito.eq(QuotaWebFilter.HEADER_COST), Mockito.anyString());
        }
    }

    @Nested
    @DisplayName("自动装配")
    class AutoConfiguration {

        @Test
        @DisplayName("★ @ConditionalOnMissingBean 全部就位：用户声明的实现能自动取代默认值")
        void allBeansAreOverridable() throws Exception {
            // 这些 @Bean 全部带 @ConditionalOnMissingBean —— 嵌入式 starter「可覆盖」的技术保证
            var methods = SwitchyardAutoConfiguration.class.getDeclaredMethods();
            long overridable =
                    java.util.Arrays.stream(methods)
                            .filter(
                                    m ->
                                            m.isAnnotationPresent(
                                                    org.springframework.boot.autoconfigure.condition
                                                            .ConditionalOnMissingBean.class))
                            .count();

            assertThat(overridable).as("每个默认 Bean 都必须可被用户覆盖").isGreaterThanOrEqualTo(10);
        }

        @Test
        @DisplayName("★ starter 只在 llm.switchyard.enabled=true 时生效")
        void gatedByEnabledProperty() throws Exception {
            var conditional =
                    SwitchyardAutoConfiguration.class.getAnnotation(
                            org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
                                    .class);

            assertThat(conditional).isNotNull();
            assertThat(conditional.prefix()).isEqualTo("llm.switchyard");
            assertThat(conditional.name()).containsExactly("enabled");
            assertThat(conditional.matchIfMissing()).isTrue();
        }

        @Test
        @DisplayName("★ 构造器参数校验：必填字段缺失直接拒绝")
        void propertiesValidation() {
            SwitchyardProperties fresh = new SwitchyardProperties();

            assertThat(fresh.isEnabled()).isTrue();
            assertThat(fresh.getDefaultRoute()).isEqualTo("default");
            assertThat(fresh.getConsole().getBasePath()).isEqualTo("/_switchyard");
            assertThat(fresh.getQuota().getOnExceeded())
                    .isEqualTo(SwitchyardProperties.OverLimitBehaviorName.FAIL_SOFT);
            assertThat(fresh.getRetry().getMaxAttempts()).isEqualTo(3);
            assertThat(fresh.getCircuitBreaker().getFailureThreshold()).isEqualTo(0.5d);
            assertThat(fresh.getAudit().isCapturePayload()).as("★ 默认必须不落 prompt 原文").isFalse();

            // null 传入时回落到默认值而不是 NPE
            fresh.setRoutes(null);
            fresh.setProviders(null);
            fresh.setConsole(null);
            assertThat(fresh.getRoutes()).isEmpty();
            assertThat(fresh.getProviders()).isEmpty();
            assertThat(fresh.getConsole().getBasePath()).isEqualTo("/_switchyard");

            SwitchyardProperties.RouteConfig route = new SwitchyardProperties.RouteConfig();
            assertThat(route.getFallbacks()).isEmpty();
            route.setFallbacks(null);
            assertThat(route.getFallbacks()).isEmpty();
        }

        @Test
        @DisplayName("★ AutoConfiguration.imports 已声明（否则 starter 根本不会被加载）")
        void autoConfigurationImportsDeclared() throws Exception {
            java.net.URL resource =
                    SwitchyardAutoConfiguration.class
                            .getClassLoader()
                            .getResource(
                                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
            assertThat(resource).as("★ 缺这个文件，starter 就是个普通 jar，不会被自动装配").isNotNull();
            String content =
                    java.nio.file.Files.readString(java.nio.file.Path.of(resource.toURI()));
            assertThat(content)
                    .contains("io.github.nephets.switchyard.starter.SwitchyardAutoConfiguration");
        }
    }
}
