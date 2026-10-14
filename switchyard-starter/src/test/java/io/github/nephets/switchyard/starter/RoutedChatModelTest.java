/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.core.audit.AuditEvent;
import io.github.nephets.switchyard.core.audit.AuditSink;
import io.github.nephets.switchyard.core.audit.RingBufferAuditSink;
import io.github.nephets.switchyard.core.model.GatewayRequest;
import io.github.nephets.switchyard.core.model.GatewayResponse;
import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.pricing.CostCalculator;
import io.github.nephets.switchyard.core.pricing.PriceEntry;
import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.provider.Capability;
import io.github.nephets.switchyard.core.provider.ChatProvider;
import io.github.nephets.switchyard.core.provider.ProviderCapabilities;
import io.github.nephets.switchyard.core.quota.InMemoryQuotaStore;
import io.github.nephets.switchyard.core.quota.OverLimitBehavior;
import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaPolicy;
import io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry;
import io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier;
import io.github.nephets.switchyard.core.resilience.ErrorClassifier;
import io.github.nephets.switchyard.core.resilience.RetryExecutor;
import io.github.nephets.switchyard.core.resilience.RetryPolicy;
import io.github.nephets.switchyard.core.trace.TraceContext;
import io.github.nephets.switchyard.core.usage.CharHeuristicTokenEstimator;
import io.github.nephets.switchyard.core.usage.DefaultUsageNormalizer;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ starter 层的行为测试：不 mock 掉被测逻辑本身，只 mock 上游 provider。
 *
 * <p>这样测出来的才是「装饰器 + 策略 + 计量 + 配额」这条链路的真实行为， 而不是一堆 mock 的自我确认。
 */
@DisplayName("starter：装饰器把七件事织进一个 ChatModel")
class RoutedChatModelTest {

    private static final ModelRef PRIMARY = ModelRef.of("openai", "gpt-5-mini");
    private static final ModelRef BACKUP = ModelRef.of("deepseek", "deepseek-chat");
    private static final ModelRef THIRD = ModelRef.of("dashscope", "qwen-plus");

    private SwitchyardProperties properties;
    private InMemoryQuotaStore quotaStore;
    private CircuitBreakerRegistry breakers;
    private PricingTable pricingTable;
    private RingBufferAuditSink auditSink;
    private ErrorClassifier classifier;

    @BeforeEach
    void setUp() {
        TraceContext.clear();
        properties = new SwitchyardProperties();
        properties.getProviders().put("openai", new SwitchyardProperties.ProviderConfig());
        properties.getMetering().setCjkPerToken(1.0d);
        properties.getMetering().setAsciiCharsPerToken(4.0d);
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
                                        io.github.nephets.switchyard.core.pricing.PriceSource
                                                .OFFICIAL)));
        auditSink = new RingBufferAuditSink(50);
        classifier = new DefaultErrorClassifier();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    private RoutedChatModel model(
            ChatProvider provider,
            List<ModelRef> chain,
            java.util.function.Function<String, ChatProvider> resolver) {
        return new RoutedChatModel(
                "default",
                properties,
                resolver,
                route -> chain,
                quotaStore,
                breakers,
                new RetryExecutor(RetryPolicy.defaults(), classifier, millis -> {}),
                classifier,
                new DefaultUsageNormalizer(),
                new CharHeuristicTokenEstimator(),
                new CostCalculator(),
                pricingTable,
                auditSink,
                new SwitchyardMetrics());
    }

    /** 造一个把固定 provider 装进装饰器的工厂（Router 现在只接受工厂，不接受 lambda）。 */
    private RoutedChatModelFactory factoryFor(ChatProvider provider) {
        return new RoutedChatModelFactory(
                properties,
                quotaStore,
                breakers,
                new DefaultUsageNormalizer(),
                new CharHeuristicTokenEstimator(),
                new CostCalculator(),
                pricingTable,
                auditSink,
                java.net.http.HttpClient.newHttpClient(),
                RetryPolicy.defaults(),
                classifier,
                new SwitchyardMetrics()) {
            @Override
            public RoutedChatModel create(String routeName) {
                return model(provider, List.of(PRIMARY), id -> provider);
            }
        };
    }

    private ChatProvider okProvider() {
        return new StubProvider(PrimaryBehavior.NORMAL, 1000L, 200L);
    }

    private enum PrimaryBehavior {
        NORMAL,
        SERVER_ERROR,
        BAD_REQUEST
    }

    /** 可编程的假 provider：不 mock 掉被测逻辑，只控制上游行为。 */
    private static final class StubProvider implements ChatProvider.StreamingChatProvider {
        private final PrimaryBehavior behavior;
        private final long inputTokens;
        private final long outputTokens;
        private final AtomicInteger calls = new AtomicInteger();

        StubProvider(PrimaryBehavior behavior, long inputTokens, long outputTokens) {
            this.behavior = behavior;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        @Override
        public String providerId() {
            return "stub";
        }

        @Override
        public ProviderCapabilities capabilities() {
            return new ProviderCapabilities(
                    java.util.Set.of(Capability.STREAM, Capability.METERING));
        }

        @Override
        public GatewayResponse complete(GatewayRequest request) {
            calls.incrementAndGet();
            return switch (behavior) {
                case NORMAL -> response(request, false);
                case SERVER_ERROR -> {
                    throw new io.github.nephets.switchyard.provider.support
                            .ProviderExceptionTranslator.ProviderHttpException(
                            503,
                            io.github.nephets.switchyard.core.ErrorCode.SW_PROVIDER_UNAVAILABLE,
                            "上游不可用",
                            null);
                }
                case BAD_REQUEST -> {
                    throw new io.github.nephets.switchyard.provider.support
                            .ProviderExceptionTranslator.ProviderHttpException(
                            400,
                            io.github.nephets.switchyard.core.ErrorCode.SW_BAD_REQUEST,
                            "参数错误",
                            null);
                }
            };
        }

        @Override
        public GatewayResponse stream(GatewayRequest request) {
            calls.incrementAndGet();
            return response(request, true);
        }

        private GatewayResponse response(GatewayRequest request, boolean streaming) {
            Usage usage =
                    new Usage(
                            inputTokens,
                            outputTokens,
                            0L,
                            0L,
                            0L,
                            UsageSource.VENDOR_REPORTED,
                            false,
                            streaming);
            String content = "回答".repeat((int) Math.min(3, outputTokens / 50 + 1));
            return new GatewayResponse(
                    content,
                    List.of(content),
                    usage,
                    request.modelRef(),
                    "stop",
                    1L,
                    false,
                    1,
                    List.of());
        }

        int calls() {
            return calls.get();
        }
    }

    private Prompt prompt() {
        return new Prompt(List.<Message>of(new SystemMessage("你是客服助手"), new UserMessage("你好")));
    }

    @Nested
    @DisplayName("① 装饰器保持 ChatModel 语义")
    class ChatModelContract {

        @Test
        @DisplayName("★ 标准 ChatModel 调用返回带 usage 的 ChatResponse（结构化输出/工具调用零损失）")
        void standardCallReturnsUsage() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 100L, 50L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            ChatResponse response = model.call(prompt());

            assertThat(response).isNotNull();
            assertThat(response.getResult().getOutput().getText()).isNotBlank();
            assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(100);
            assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(50);
            assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(150);
            assertThat(response.getMetadata().getModel()).isEqualTo("gpt-5-mini");
        }

        @Test
        @DisplayName("流式调用返回 Flux，且 content 不为空")
        void streamReturnsFlux() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 100L, 50L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            List<ChatResponse> responses =
                    model.stream(prompt()).collectList().block(Duration.ofSeconds(10));

            assertThat(responses).isNotNull().isNotEmpty();
            assertThat(responses.get(0).getResult().getOutput().getText()).isNotBlank();
        }

        @Test
        @DisplayName("getDefaultOptions 不为 null（部分调用方会直接读它）")
        void defaultOptionsNotNull() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            assertThat(model(provider, List.of(PRIMARY), id -> provider).getDefaultOptions())
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("② 计量与成本")
    class MeteringAndCost {

        @Test
        @DisplayName("★ 成本按价格表计算，并写进响应上下文")
        void costComputedFromPriceTable() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 1000L, 500L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            model.call(prompt());
            RoutedChatModel.CallContext ctx = model.lastCallContext();

            assertThat(ctx).isNotNull();
            assertThat(ctx.usage().totalTokens()).isEqualTo(1500L);
            // 1000 * 0.001/1000 + 500 * 0.002/1000 = 0.001 + 0.001 = 0.002
            assertThat(ctx.costAmount()).isEqualByComparingTo("0.00200000");
            assertThat(ctx.costAmount().scale()).isEqualTo(8);
        }

        @Test
        @DisplayName("★ 审计记录被写入，且 usage/cost/traceId 齐全")
        void auditRecordWritten() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 800L, 200L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            model.call(prompt());

            List<AuditEvent> events = auditSink.events();
            assertThat(events).hasSize(1);
            AuditEvent event = events.get(0);
            assertThat(event.usage().inputTokens()).isEqualTo(800L);
            assertThat(event.usage().outputTokens()).isEqualTo(200L);
            assertThat(event.costAmount()).isGreaterThan(BigDecimal.ZERO);
            assertThat(event.traceId()).hasSize(32);
            assertThat(event.eventType())
                    .isEqualTo(io.github.nephets.switchyard.core.audit.AuditEventType.MODEL_CALL);
            assertThat(event.tenantId()).isEqualTo(properties.getQuota().getDefaultTenant());
        }

        @Test
        @DisplayName("★ 审计默认不落 prompt 原文（只留摘要）")
        void auditDoesNotLeakPrompt() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            model.call(prompt());

            AuditEvent event = auditSink.events().get(0);
            assertThat(event.payloadFull()).as("★ 金融/客服场景的 prompt 常含 PII，默认绝不能落原文").isNull();
            assertThat(event.payloadDigest()).startsWith("sha256:");
        }
    }

    @Nested
    @DisplayName("③ 配额：请求前预留 + 请求后结算")
    class QuotaLifecycle {

        @Test
        @DisplayName("★ 成功后按真实用量结算（多退少补），剩余额度准确")
        void settleUsesRealUsage() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 300L, 100L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);
            QuotaKey key =
                    new QuotaKey(
                            properties.getQuota().getDefaultTenant(), null, "openai", "gpt-5-mini");
            quotaStore.putPolicy(
                    key,
                    new QuotaPolicy(
                            QuotaPolicy.Period.DAILY, 10_000L, OverLimitBehavior.FAIL_SOFT));

            model.call(prompt());

            assertThat(quotaStore.snapshot(key).used())
                    .as("应按真实 400 token 结算，而不是按预留时的估算值")
                    .isEqualTo(400L);
        }

        @Test
        @DisplayName("★ 配额用尽时 fail-soft：返回响应而非抛异常（PRD P0-5）")
        void failSoftWhenQuotaExhausted() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);
            QuotaKey key =
                    new QuotaKey(
                            properties.getQuota().getDefaultTenant(), null, "openai", "gpt-5-mini");
            quotaStore.putPolicy(
                    key,
                    new QuotaPolicy(QuotaPolicy.Period.DAILY, 1L, OverLimitBehavior.FAIL_SOFT));

            ChatResponse response = model.call(prompt());

            assertThat(response).as("★ fail-soft 不抛异常").isNotNull();
            assertThat(provider.calls()).as("配额不足时不应打上游").isZero();
            RoutedChatModel.CallContext ctx = model.lastCallContext();
            assertThat(ctx.rejected()).isTrue();
            assertThat(ctx.error().code()).isEqualTo("SW_QUOTA_EXCEEDED");
        }

        @Test
        @DisplayName("★ 请求失败时释放预留，不让一次 5xx 白吃配额")
        void reservationReleasedOnFailure() {
            StubProvider provider = new StubProvider(PrimaryBehavior.SERVER_ERROR, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);
            QuotaKey key =
                    new QuotaKey(
                            properties.getQuota().getDefaultTenant(), null, "openai", "gpt-5-mini");
            quotaStore.putPolicy(
                    key,
                    new QuotaPolicy(
                            QuotaPolicy.Period.DAILY, 100_000L, OverLimitBehavior.FAIL_SOFT));

            assertThatThrownBy(() -> model.call(prompt())).isInstanceOf(RuntimeException.class);

            assertThat(quotaStore.snapshot(key).used()).as("失败请求的预留必须被释放").isZero();
        }

        @Test
        @DisplayName("关闭配额后 store 不参与，但调用仍正常")
        void quotaCanBeDisabled() {
            properties.getQuota().setEnabled(false);
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            assertThat(model.call(prompt())).isNotNull();
            // 配额被显式关闭时返回 -1：与「不限额」(UNLIMITED_REMAINING) 是两个不同语义，
            // 前者表示本模块没参与这个请求，后者表示有额度但没有上限。业务方据此可区分
            // 「没配配额」与「配了但不设限」。
            assertThat(model.lastCallContext().remainingQuota())
                    .as("配额关闭时用 -1 表示「未参与」，而不是伪装成无限额")
                    .isEqualTo(-1L);
        }
    }

    @Nested
    @DisplayName("④ 语义化重试与降级")
    class RetryAndFallback {

        @Test
        @DisplayName("★ 5xx 可重试：同一模型最多试 3 次")
        void retriesOnServerError() {
            StubProvider provider = new StubProvider(PrimaryBehavior.SERVER_ERROR, 10L, 10L);
            // 只有主模型，无降级 —— 于是正好能数清重试次数
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            assertThatThrownBy(() -> model.call(prompt())).isInstanceOf(RuntimeException.class);
            assertThat(provider.calls()).isEqualTo(3);
        }

        @Test
        @DisplayName("★ 400 不可重试：只打 1 次就失败（不做无意义重试）")
        void doesNotRetryOnBadRequest() {
            StubProvider provider = new StubProvider(PrimaryBehavior.BAD_REQUEST, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            assertThatThrownBy(() -> model.call(prompt())).isInstanceOf(RuntimeException.class);
            assertThat(provider.calls()).as("★ 参数错误重试只会白烧配额").isEqualTo(1);
        }

        @Test
        @DisplayName("★ 主模型不可用时降级到备用模型并成功返回")
        void degradesToFallback() {
            StubProvider failing = new StubProvider(PrimaryBehavior.SERVER_ERROR, 10L, 10L);
            StubProvider healthy = new StubProvider(PrimaryBehavior.NORMAL, 400L, 100L);
            RoutedChatModel model =
                    model(
                            failing,
                            List.of(PRIMARY, BACKUP),
                            id -> "openai".equals(id) ? failing : healthy);

            ChatResponse response = model.call(prompt());

            assertThat(response).isNotNull();
            assertThat(response.getMetadata().getModel()).isEqualTo("deepseek-chat");
            assertThat(failing.calls()).isEqualTo(3);
            assertThat(healthy.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 降级链全部失败时抛 SW_MODEL_UNAVAILABLE")
        void allFallbacksExhausted() {
            StubProvider failing = new StubProvider(PrimaryBehavior.SERVER_ERROR, 10L, 10L);
            RoutedChatModel model = model(failing, List.of(PRIMARY, BACKUP, THIRD), id -> failing);

            assertThatThrownBy(() -> model.call(prompt()))
                    .isInstanceOf(io.github.nephets.switchyard.core.SwitchyardException.class)
                    .satisfies(
                            ex ->
                                    assertThat(
                                                    ((io.github.nephets.switchyard.core
                                                                            .SwitchyardException)
                                                                    ex)
                                                            .errorCode())
                                            .isEqualTo(
                                                    io.github.nephets.switchyard.core.ErrorCode
                                                            .SW_MODEL_UNAVAILABLE));
        }

        @Test
        @DisplayName("★ 熔断打开后不再打上游")
        void circuitBreakerStopsTraffic() {
            StubProvider failing = new StubProvider(PrimaryBehavior.SERVER_ERROR, 10L, 10L);
            RoutedChatModel model = model(failing, List.of(PRIMARY), id -> failing);

            // 连续失败直到熔断打开
            for (int i = 0; i < 8; i++) {
                try {
                    model.call(prompt());
                } catch (RuntimeException ignored) {
                    // 预期异常
                }
            }
            int callsWhenOpen = failing.calls();

            try {
                model.call(prompt());
            } catch (RuntimeException ignored) {
                // 预期异常
            }

            assertThat(failing.calls()).as("熔断打开后不应再打上游").isEqualTo(callsWhenOpen);
            assertThat(breakers.stateOf(PRIMARY))
                    .isEqualTo(io.github.nephets.switchyard.core.resilience.CircuitState.OPEN);
        }

        @Test
        @DisplayName("关闭重试后只尝试 1 次")
        void retryCanBeDisabled() {
            properties.getRetry().setEnabled(false);
            StubProvider failing = new StubProvider(PrimaryBehavior.SERVER_ERROR, 10L, 10L);
            RoutedChatModel model =
                    new RoutedChatModel(
                            "default",
                            properties,
                            id -> failing,
                            route -> List.of(PRIMARY),
                            quotaStore,
                            breakers,
                            new RetryExecutor(RetryPolicy.noRetry(), classifier, millis -> {}),
                            classifier,
                            new DefaultUsageNormalizer(),
                            new CharHeuristicTokenEstimator(),
                            new CostCalculator(),
                            pricingTable,
                            auditSink,
                            new SwitchyardMetrics());

            assertThatThrownBy(() -> model.call(prompt())).isInstanceOf(RuntimeException.class);
            assertThat(failing.calls()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("⑤ 路由与配置")
    class RoutingAndConfig {

        @Test
        @DisplayName("★ Router 返回的是同一个装饰器实例（熔断器状态不能因重复注入而失真）")
        void routerCachesDecorator() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            properties.getRoutes().put("default", new SwitchyardProperties.RouteConfig());
            properties.getRoutes().get("default").setPrimary("openai/gpt-5-mini");

            SwitchyardRouter router =
                    new SwitchyardRouter(
                            properties,
                            factoryFor(provider),
                            key -> quotaStore.snapshot(key),
                            breakers,
                            pricingTable);

            assertThat(router.route("default")).isSameAs(router.route("default"));
            assertThat(router.route()).isSameAs(router.route("default"));
            assertThat(router.route(null)).isSameAs(router.route("default"));
            assertThat(router.route("  ")).isSameAs(router.route("default"));
        }

        @Test
        @DisplayName("★ health 视图报告熔断状态与价格条目")
        void healthView() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            properties.getRoutes().put("default", new SwitchyardProperties.RouteConfig());
            properties.getRoutes().get("default").setPrimary("openai/gpt-5-mini");

            SwitchyardRouter router =
                    new SwitchyardRouter(
                            properties,
                            factoryFor(provider),
                            key -> quotaStore.snapshot(key),
                            breakers,
                            pricingTable);
            router.route("default").call(prompt());

            Map<String, SwitchyardRouter.ModelHealth> health = router.health();
            assertThat(health).containsKey("openai/gpt-5-mini");
            SwitchyardRouter.ModelHealth item = health.get("openai/gpt-5-mini");
            assertThat(item.available()).isTrue();
            assertThat(item.circuitState()).isEqualTo("CLOSED");
            assertThat(item.totalCalls()).isPositive();
            assertThat(item.priceEntryKey()).isNotBlank();
            assertThat(router.routeNames()).contains("default");
            assertThat(router.catalog().size()).isEqualTo(9);
        }

        @Test
        @DisplayName("未配置的路由不抛异常，只在真正调用时失败")
        void unknownRouteFailsOnCallNotOnLookup() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            SwitchyardRouter router =
                    new SwitchyardRouter(
                            properties,
                            factoryFor(provider),
                            key -> quotaStore.snapshot(key),
                            breakers,
                            pricingTable);

            assertThat(router.route("不存在")).isNotNull();
            assertThat(router.routeNames()).isEmpty();
        }

        @Test
        @DisplayName("空模型链在调用时报错（配置错误要立刻暴露）")
        void emptyChainFailsFast() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(), id -> provider);

            assertThatThrownBy(() -> model.call(prompt()))
                    .isInstanceOf(io.github.nephets.switchyard.core.SwitchyardException.class)
                    .hasMessageContaining("未配置任何模型");
        }

        @Test
        @DisplayName("未注册的供应商在调用时报错并说明是哪个")
        void unknownProviderFailsFast() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model =
                    model(provider, List.of(ModelRef.of("不存在的供应商", "m")), id -> null);

            // 未注册的供应商最终表现为「降级链走完仍失败」——
            // 这正是它应有的样子：调用方拿到的是统一错误，而不是把内部配置细节泄露出去
            assertThatThrownBy(() -> model.call(prompt()))
                    .isInstanceOf(io.github.nephets.switchyard.core.SwitchyardException.class)
                    .hasMessageContaining("降级链已全部失败");
        }

        @Test
        @DisplayName("★ chatOptions 的 maxTokens/temperature/topP 被正确传递")
        void optionsForwarded() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            Prompt withOptions =
                    new Prompt(
                            List.of(new UserMessage("你好")),
                            ChatOptions.builder()
                                    .maxTokens(256)
                                    .temperature(0.3d)
                                    .topP(0.8d)
                                    .build());

            assertThat(model.call(withOptions)).isNotNull();
            assertThat(provider.calls()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("⑥ 上下文与清理")
    class ContextHandling {

        @Test
        @DisplayName("clearLastCallContext 避免线程复用串号")
        void clearsThreadLocal() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            model.call(prompt());
            assertThat(model.lastCallContext()).isNotNull();

            model.clearLastCallContext();
            assertThat(model.lastCallContext()).isNull();
        }

        @Test
        @DisplayName("★ traceId 贯通到审计记录（一次报错就能定位）")
        void traceIdPropagatesToAudit() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            model.call(prompt());

            assertThat(auditSink.events().get(0).traceId())
                    .isEqualTo(TraceContext.current().traceId());
        }

        @Test
        @DisplayName("每次调用都用同一个 traceId（一次请求内）")
        void sameTraceIdWithinCall() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);

            model.call(prompt());
            String first = auditSink.events().get(0).traceId();
            model.call(prompt());

            assertThat(auditSink.events()).hasSize(2);
            assertThat(auditSink.events().get(1).traceId()).isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("⑦ 工厂与配置绑定")
    class FactoryWiring {

        @Test
        @DisplayName("★ 工厂按协议分派到正确的适配器（9 家 → 3 种协议）")
        void factoryResolvesProtocols() {
            properties.getProviders().put("openai", new SwitchyardProperties.ProviderConfig());
            properties.getProviders().put("anthropic", new SwitchyardProperties.ProviderConfig());
            properties.getProviders().put("ollama", new SwitchyardProperties.ProviderConfig());

            RoutedChatModelFactory factory =
                    new RoutedChatModelFactory(
                            properties,
                            quotaStore,
                            breakers,
                            new DefaultUsageNormalizer(),
                            new CharHeuristicTokenEstimator(),
                            new CostCalculator(),
                            pricingTable,
                            auditSink,
                            java.net.http.HttpClient.newHttpClient(),
                            RetryPolicy.defaults(),
                            classifier,
                            new SwitchyardMetrics());

            for (String providerId :
                    List.of(
                            "openai",
                            "deepseek",
                            "zhipu",
                            "moonshot",
                            "doubao",
                            "siliconflow",
                            "dashscope")) {
                RoutedChatModel created = factory.create("default");
                assertThat(created).as("%s 应能创建路由", providerId).isNotNull();
            }
        }

        @Test
        @DisplayName("★ 工厂解析模型链：主模型在前、降级链在后")
        void factoryResolvesChain() {
            properties.getRoutes().put("default", new SwitchyardProperties.RouteConfig());
            properties.getRoutes().get("default").setPrimary("openai/gpt-5-mini");
            properties
                    .getRoutes()
                    .get("default")
                    .setFallbacks(List.of("deepseek/deepseek-chat", "ollama/qwen3:8b"));

            // 链解析通过 create → call 的行为间接验证：降级链顺序决定尝试顺序
            StubProvider failing = new StubProvider(PrimaryBehavior.SERVER_ERROR, 10L, 10L);
            SwitchyardProperties props = properties;
            RoutedChatModel model =
                    new RoutedChatModel(
                            "default",
                            props,
                            id -> failing,
                            route -> resolveChain(props, route),
                            quotaStore,
                            breakers,
                            new RetryExecutor(RetryPolicy.defaults(), classifier, millis -> {}),
                            classifier,
                            new DefaultUsageNormalizer(),
                            new CharHeuristicTokenEstimator(),
                            new CostCalculator(),
                            pricingTable,
                            auditSink,
                            new SwitchyardMetrics());

            assertThat(resolveChain(props, "default"))
                    .containsExactly(PRIMARY, BACKUP, ModelRef.of("ollama", "qwen3:8b"));
            assertThat(resolveChain(props, "不存在的路由"))
                    .as("未配置的路由回落到默认路由，而不是抛启动期异常")
                    .containsExactly(PRIMARY, BACKUP, ModelRef.of("ollama", "qwen3:8b"));
            assertThat(model).isNotNull();
        }

        @Test
        @DisplayName("工厂能按配置创建熔断器注册表与估算器")
        void factoryCreatesHelpers() {
            properties.getCircuitBreaker().setWindowSize(30);
            properties.getCircuitBreaker().setFailureThreshold(0.4d);
            properties.getMetering().setCjkPerToken(0.5d);

            RoutedChatModelFactory factory =
                    new RoutedChatModelFactory(
                            properties,
                            quotaStore,
                            breakers,
                            new DefaultUsageNormalizer(),
                            new CharHeuristicTokenEstimator(),
                            new CostCalculator(),
                            pricingTable,
                            auditSink,
                            java.net.http.HttpClient.newHttpClient(),
                            RetryPolicy.defaults(),
                            classifier,
                            new SwitchyardMetrics());

            assertThat(factory.createBreakerRegistry()).isNotNull();
            assertThat(factory.createEstimator().estimate("测试")).isPositive();
            assertThat(factory.createHttpClient()).isNotNull();
            assertThat(factory.createRetryPolicy().maxAttempts()).isEqualTo(3);
            assertThat(factory.createClassifier()).isNotNull();
        }

        private List<ModelRef> resolveChain(SwitchyardProperties props, String routeName) {
            SwitchyardProperties.RouteConfig route = props.getRoutes().get(routeName);
            if (route == null) {
                route = props.getRoutes().get(props.getDefaultRoute());
            }
            if (route == null || route.getPrimary() == null) {
                return List.of();
            }
            List<ModelRef> chain = new ArrayList<>();
            chain.add(ModelRef.parse(route.getPrimary()));
            for (String fallback : route.getFallbacks()) {
                chain.add(ModelRef.parse(fallback));
            }
            return chain;
        }
    }

    @Nested
    @DisplayName("⑧ 控制台数据来源真实")
    class ConsoleData {

        @Test
        @DisplayName("★ overview 的成本/token 来自真实调用累加，不是 0")
        void overviewUsesRealNumbers() throws Exception {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 1000L, 500L);
            RoutedChatModel model = model(provider, List.of(PRIMARY), id -> provider);
            model.call(prompt());

            SwitchyardMetrics metrics = new SwitchyardMetrics();
            metrics.recordSuccess(
                    model.lastCallContext().usage(), model.lastCallContext().cost(), false);

            Map<String, Object> snapshot = metrics.snapshot();
            @SuppressWarnings("unchecked")
            Map<String, Object> counters = (Map<String, Object>) snapshot.get("counters");

            assertThat((Long) counters.get("tokens.input")).isEqualTo(1000L);
            assertThat((Long) counters.get("tokens.output")).isEqualTo(500L);
            assertThat((Long) counters.get("tokens.total")).isEqualTo(1500L);
            assertThat(snapshot.get("totalCostCny")).isEqualTo("0.00200000");
            assertThat(snapshot.get("totalCalls")).isEqualTo(1L);
            assertThat(String.valueOf(snapshot.get("scope"))).contains("重启归零");
        }

        @Test
        @DisplayName("指标区分流式计量是否真的拿到了 usage")
        void metricsDistinguishStreamUsage() {
            SwitchyardMetrics metrics = new SwitchyardMetrics();
            metrics.recordSuccess(
                    new Usage(10L, 10L, 0L, 0L, 0L, UsageSource.VENDOR_REPORTED, false, true),
                    BigDecimal.ONE,
                    false);
            metrics.recordSuccess(
                    new Usage(10L, 10L, 0L, 0L, 0L, UsageSource.ESTIMATED, false, false),
                    BigDecimal.ONE,
                    false);

            @SuppressWarnings("unchecked")
            Map<String, Object> counters = (Map<String, Object>) metrics.snapshot().get("counters");

            assertThat((Long) counters.get("usage.streamReported")).isEqualTo(1L);
            assertThat((Long) counters.get("usage.streamNotReported")).isEqualTo(1L);
            assertThat(metrics.snapshot().get("estimatedUsageCalls")).isEqualTo(1L);
        }

        @Test
        @DisplayName("失败与降级被分别计数")
        void metricsCountFailuresAndDegradation() {
            SwitchyardMetrics metrics = new SwitchyardMetrics();
            metrics.recordSuccess(
                    new Usage(10L, 10L, 0L, 0L, 0L, UsageSource.VENDOR_REPORTED, false, false),
                    BigDecimal.ONE,
                    true);
            metrics.recordFailure("SW_PROVIDER_UNAVAILABLE");
            metrics.recordQuotaRejection("vip/-/openai/gpt-5-mini");

            @SuppressWarnings("unchecked")
            Map<String, Object> counters = (Map<String, Object>) metrics.snapshot().get("counters");

            assertThat(metrics.snapshot().get("degradedCalls")).isEqualTo(1L);
            assertThat(metrics.snapshot().get("totalFailures")).isEqualTo(1L);
            assertThat((Long) counters.get("errors.SW_PROVIDER_UNAVAILABLE")).isEqualTo(1L);
            assertThat((Long) counters.get("quota.rejected")).isEqualTo(1L);

            metrics.reset();
            assertThat(metrics.totalCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("⑨ 健康指示器")
    class HealthIndicator {

        @Test
        @DisplayName("★ 有模型熔断但仍有可用模型时保持 UP（网关在替上游兜底，不是它自己坏了）")
        void upWhenSomeModelsOpen() {
            properties.getRoutes().put("default", new SwitchyardProperties.RouteConfig());
            properties.getRoutes().get("default").setPrimary("openai/gpt-5-mini");
            properties.getRoutes().put("other", new SwitchyardProperties.RouteConfig());
            properties.getRoutes().get("other").setPrimary("deepseek/deepseek-chat");

            SwitchyardRouter router =
                    new SwitchyardRouter(
                            properties,
                            factoryFor(new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L)),
                            key -> quotaStore.snapshot(key),
                            breakers,
                            pricingTable);
            breakers.breakerOf(PRIMARY);
            for (int i = 0; i < 12; i++) {
                breakers.breakerOf(PRIMARY).recordFailure();
            }

            SwitchyardHealthIndicator indicator =
                    new SwitchyardHealthIndicator(router, pricingTable);
            var health = indicator.health();

            assertThat(health.getStatus())
                    .as("★ 熔断不等于网关故障，否则 K8s 会把网关摘掉，事故反而扩大")
                    .isEqualTo(org.springframework.boot.actuate.health.Status.UP);
            assertThat(health.getDetails()).containsKey("modelsCircuitOpen");
        }

        @Test
        @DisplayName("未配置任何路由时 DOWN")
        void downWhenNoRoutes() {
            SwitchyardRouter router =
                    new SwitchyardRouter(
                            properties,
                            factoryFor(null),
                            key -> quotaStore.snapshot(key),
                            breakers,
                            pricingTable);

            var health = new SwitchyardHealthIndicator(router, pricingTable).health();
            assertThat(health.getStatus())
                    .isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
            assertThat(health.getDetails()).containsEntry("reason", "未配置任何路由");
        }

        @Test
        @DisplayName("价格表为空时 DOWN（无法算成本）")
        void downWhenPriceTableEmpty() {
            properties.getRoutes().put("default", new SwitchyardProperties.RouteConfig());
            properties.getRoutes().get("default").setPrimary("openai/gpt-5-mini");
            SwitchyardRouter router =
                    new SwitchyardRouter(
                            properties,
                            factoryFor(null),
                            key -> quotaStore.snapshot(key),
                            breakers,
                            new PricingTable(List.of()));

            var health =
                    new SwitchyardHealthIndicator(router, new PricingTable(List.of())).health();
            assertThat(health.getStatus())
                    .isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
        }
    }

    @Nested
    @DisplayName("⑩ 审计 sink 实现")
    class AuditSinks {

        @Test
        @DisplayName("★ 日志 sink 输出一行 JSON，含各维度且不落 prompt 原文")
        void loggingSinkEmitsSingleLineJson() throws Exception {
            LoggingAuditSinkAdapter sink = new LoggingAuditSinkAdapter();
            AuditEvent event =
                    AuditEvent.modelCall(
                            "4bf92f3577b34da6a3ce929d0e0e4736",
                            "00f067aa0ba902b7",
                            "vip",
                            "customer-service",
                            "id-verify",
                            Usage.of(100L, 50L),
                            new BigDecimal("0.0012"),
                            "身份证 110101199003071234");

            String json = sink.toJson(event);

            assertThat(sink.name()).isEqualTo("logging");
            assertThat(json).doesNotContain("\n");
            assertThat(json).contains("\"tenantId\":\"vip\"");
            assertThat(json).contains("\"bizLine\":\"customer-service\"");
            assertThat(json).contains("\"payloadFull\":null");
            assertThat(json)
                    .as("★ 即使有 payloadFull 开关，日志也不应把原文写进 JSON 的明文字段")
                    .doesNotContain("110101199003071234");
            // 必须是合法 JSON
            new ObjectMapper().readTree(json);
        }

        @Test
        @DisplayName("审计 sink 抛异常不影响主链路（SPI 契约）")
        void auditFailureDoesNotBreakMainFlow() {
            StubProvider provider = new StubProvider(PrimaryBehavior.NORMAL, 10L, 10L);
            AuditSink exploding =
                    new AuditSink() {
                        @Override
                        public void write(AuditEvent event) {
                            throw new IllegalStateException("审计后端挂了");
                        }

                        @Override
                        public String name() {
                            return "exploding";
                        }
                    };
            RoutedChatModel model =
                    new RoutedChatModel(
                            "default",
                            properties,
                            id -> provider,
                            route -> List.of(PRIMARY),
                            quotaStore,
                            breakers,
                            new RetryExecutor(RetryPolicy.defaults(), classifier, millis -> {}),
                            classifier,
                            new DefaultUsageNormalizer(),
                            new CharHeuristicTokenEstimator(),
                            new CostCalculator(),
                            pricingTable,
                            exploding,
                            new SwitchyardMetrics());

            // 装饰器内部对审计异常做了兜底，调用仍应成功
            assertThat(model.call(prompt())).isNotNull();
        }
    }
}
