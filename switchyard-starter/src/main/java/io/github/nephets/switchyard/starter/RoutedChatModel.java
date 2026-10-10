/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.ErrorCode;
import io.github.nephets.switchyard.core.ErrorResponse;
import io.github.nephets.switchyard.core.audit.AuditEvent;
import io.github.nephets.switchyard.core.audit.AuditSink;
import io.github.nephets.switchyard.core.model.GatewayRequest;
import io.github.nephets.switchyard.core.model.GatewayResponse;
import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.pricing.CostCalculator;
import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.provider.ChatProvider;
import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaSnapshot;
import io.github.nephets.switchyard.core.quota.QuotaStore;
import io.github.nephets.switchyard.core.quota.Reservation;
import io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry;
import io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier;
import io.github.nephets.switchyard.core.resilience.ErrorClassifier;
import io.github.nephets.switchyard.core.resilience.FallbackChain;
import io.github.nephets.switchyard.core.resilience.RetryExecutor;
import io.github.nephets.switchyard.core.trace.TraceContext;
import io.github.nephets.switchyard.core.usage.CharHeuristicTokenEstimator;
import io.github.nephets.switchyard.core.usage.DefaultUsageNormalizer;
import io.github.nephets.switchyard.core.usage.TokenEstimator;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageNormalizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import reactor.core.publisher.Flux;

/**
 * 装饰器：把路由、配额、重试、降级、熔断、计量、审计七件事织进一个标准 {@link ChatModel}。
 *
 * <p><b>为什么是装饰器而不是 Bean 替换</b>：这是「30 秒上手」承诺的技术前提。 {@code router.route("default")} 返回的仍是标准 {@code
 * ChatModel}， 因此 {@code ChatClient.builder(...).build()} 的结构化输出、工具调用、流式能力<b>零损失</b>。 业务代码只需要把
 * {@code ChatModel} 的来源换成 router，其余不动。
 *
 * <p><b>一次调用的完整顺序</b>（与架构时序图一致）：
 *
 * <ol>
 *   <li>取/建 traceId（{@link TraceContext}）
 *   <li>路由决策：选出主模型与降级链
 *   <li><b>配额预留</b>（请求前）→ 超限则 fail-soft 返回 429，不抛异常
 *   <li>熔断器许可 → 语义化重试（仅 retryable 错误）→ 降级链
 *   <li><b>配额结算</b>（请求后，多退少补）→ 计算成本 → 写审计
 * </ol>
 */
public class RoutedChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutedChatModel.class);

    private final String routeName;
    private final SwitchyardProperties properties;
    private final Function<String, ChatProvider> providerResolver;
    private final Function<String, List<ModelRef>> chainResolver;
    private final QuotaStore quotaStore;
    private final CircuitBreakerRegistry breakers;
    private final RetryExecutor retryExecutor;
    private final ErrorClassifier classifier;
    private final UsageNormalizer normalizer;
    private final TokenEstimator estimator;
    private final CostCalculator costCalculator;
    private final PricingTable pricingTable;
    private final AuditSink auditSink;
    private final SwitchyardMetrics metrics;
    private final Map<String, ChatProvider> providerCache = new ConcurrentHashMap<>();

    /** 最近一次调用的上下文（供响应头与控制台读取）。 */
    private final ThreadLocal<CallContext> lastCall = new ThreadLocal<>();

    /**
     * 构造装饰器。
     *
     * @param routeName 路由名
     * @param properties 配置
     * @param providerResolver 按 providerId 解析适配器
     * @param chainResolver 按路由名解析完整模型链（首项为主模型）
     * @param quotaStore 配额存储
     * @param breakers 熔断器注册表
     * @param retryExecutor 重试执行器
     * @param classifier 异常分类器
     * @param normalizer usage 归一化器
     * @param estimator token 估算器
     * @param costCalculator 成本计算器
     * @param pricingTable 价格表
     * @param auditSink 审计出口
     * @param metrics 指标累加器（可为 null）
     */
    public RoutedChatModel(
            String routeName,
            SwitchyardProperties properties,
            Function<String, ChatProvider> providerResolver,
            Function<String, List<ModelRef>> chainResolver,
            QuotaStore quotaStore,
            CircuitBreakerRegistry breakers,
            RetryExecutor retryExecutor,
            ErrorClassifier classifier,
            UsageNormalizer normalizer,
            TokenEstimator estimator,
            CostCalculator costCalculator,
            PricingTable pricingTable,
            AuditSink auditSink,
            SwitchyardMetrics metrics) {
        this.routeName = routeName == null ? "default" : routeName;
        this.properties = properties == null ? new SwitchyardProperties() : properties;
        this.providerResolver = providerResolver;
        this.chainResolver = chainResolver;
        this.quotaStore = quotaStore;
        this.breakers = breakers;
        this.retryExecutor = retryExecutor;
        this.classifier = classifier;
        this.normalizer = normalizer == null ? new DefaultUsageNormalizer() : normalizer;
        this.estimator = estimator == null ? new CharHeuristicTokenEstimator() : estimator;
        this.costCalculator = costCalculator == null ? new CostCalculator() : costCalculator;
        this.pricingTable = pricingTable;
        this.auditSink = auditSink;
        this.metrics = metrics;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        GatewayRequest request = toGatewayRequest(prompt, false);
        return toChatResponse(invokeWithPolicies(request));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        GatewayRequest request = toGatewayRequest(prompt, true);
        // 流式在 Reactor 语义下必须是「声明式」的：返回 Flux，让订阅时才真正发起调用。
        // 若在方法体内同步执行，上游阻塞会占住订阅线程，流式的并发优势就没了。
        return Flux.defer(() -> Flux.just(toChatResponse(invokeWithPolicies(request))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().build();
    }

    /**
     * 执行一次带全部策略的调用。
     *
     * @param request 统一请求
     * @return 统一响应
     */
    private GatewayResponse invokeWithPolicies(GatewayRequest request) {
        TraceContext trace = TraceContext.current();
        long startedAt = System.nanoTime();
        List<ModelRef> chain = chainResolver.apply(routeName);
        if (chain == null || chain.isEmpty()) {
            throw new io.github.nephets.switchyard.core.SwitchyardException(
                    ErrorCode.SW_INTERNAL, "路由 " + routeName + " 未配置任何模型", trace.traceId());
        }

        FallbackChain fallbackChain = new FallbackChain(chain, false);
        ModelRef current = chain.get(0);
        GatewayRequest currentRequest = request;
        GatewayResponse lastResponse = null;
        RuntimeException lastError = null;

        for (int attempt = 0; attempt < chain.size(); attempt++) {
            // ---------- ① 配额预留（请求前）----------
            Reservation reservation = reserve(current, currentRequest, trace);
            if (!reservation.granted()) {
                // ★ fail-soft：返回 429 业务响应，不抛异常（PRD P0-5）
                CallContext ctx = CallContext.quotaRejected(reservation, trace);
                lastCall.set(ctx);
                if (metrics != null) {
                    metrics.recordQuotaRejection(
                            reservation.key() == null ? null : reservation.key().text());
                }
                return ctx.toFailSoftResponse(current);
            }

            try {
                // ---------- ② 熔断 + 语义化重试 ----------
                final ModelRef model = current;
                GatewayResponse upstream =
                        retryExecutor.execute(
                                () -> {
                                    if (properties.getCircuitBreaker().isEnabled()
                                            && !breakers.tryAcquire(model)) {
                                        throw new io.github.nephets.switchyard.core
                                                .SwitchyardException(
                                                ErrorCode.SW_PROVIDER_UNAVAILABLE,
                                                "模型 " + model.text() + " 熔断中",
                                                trace.traceId());
                                    }
                                    ChatProvider provider = providerOf(model);
                                    // ★ 请求里的 modelRef 必须跟着当前模型走。
                                    //   降级后如果还用原始 modelRef，provider 会把请求发给
                                    //   错误的模型名（降级链形同虚设），而响应里的
                                    //   model 字段也会谎报成主模型 —— 审计与成本归因随之全错。
                                    GatewayRequest requestForModel =
                                            currentRequest.withModel(model);
                                    GatewayResponse result =
                                            requestForModel.stream()
                                                            && provider
                                                                    instanceof
                                                                    ChatProvider
                                                                                    .StreamingChatProvider
                                                                            streaming
                                                    ? streaming.stream(requestForModel)
                                                    : provider.complete(requestForModel);
                                    recordBreaker(model, null);
                                    return result;
                                },
                                (att, code, backoffMillis) ->
                                        log.warn(
                                                "retry: route={} model={} attempt={} errCode={} backoff={}ms traceId={}",
                                                routeName,
                                                model.text(),
                                                att,
                                                code,
                                                backoffMillis,
                                                trace.traceId()));

                lastResponse = upstream;

                // ---------- ③ 结算 + 成本 + 审计 ----------
                Usage usage = normalizeUsage(upstream, currentRequest);
                // ★ 响应里的模型必须反映「实际用的是谁」：降级后若还报主模型，
                //   审计与成本归因都会归错对象。
                GatewayResponse settled =
                        upstream.withUsage(usage).withModel(model, !model.equals(chain.get(0)));
                QuotaSnapshot snapshot = quotaStore.settle(reservation, usage);
                BigDecimal cost = computeCost(model, usage);
                writeAudit(trace, model, usage, cost, currentRequest);
                lastCall.set(new CallContext(snapshot, cost, usage, model, trace, null));

                if (metrics != null) {
                    metrics.recordSuccess(usage, cost, settled.degraded());
                }

                log.info(
                        "llm call: route={} model={} tenant={} input={} output={} cached={} reasoning={} source={} "
                                + "streamReported={} costCny={} latencyMs={} degraded={} traceId={}",
                        routeName,
                        model.text(),
                        properties.getQuota().getDefaultTenant(),
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.cachedInputTokens(),
                        usage.reasoningTokens(),
                        usage.usageSource(),
                        usage.streamUsageReported(),
                        cost.toPlainString(),
                        (System.nanoTime() - startedAt) / 1_000_000L,
                        settled.degraded(),
                        trace.traceId());
                return settled;
            } catch (RuntimeException ex) {
                // 请求失败：必须释放预留，否则一次 5xx 就会永久吃掉一份配额
                quotaStore.release(reservation);
                recordBreaker(current, ex);
                lastError = ex;

                ErrorCode code = classifier.errorCode(ex);
                if (!code.retryable() && !(current != chain.get(0))) {
                    // 不可重试且仍在主模型上 → 直接失败，不做无意义的降级尝试
                    break;
                }
                current = fallbackChain.next(current).orElse(null);
                if (current == null) {
                    break;
                }
                log.warn(
                        "degrade: route={} from={} reason={} traceId={}",
                        routeName,
                        "->" + current.text(),
                        code,
                        trace.traceId());
            }
        }

        ErrorCode finalCode =
                lastError == null
                        ? ErrorCode.SW_MODEL_UNAVAILABLE
                        : classifier.errorCode(lastError);
        if (finalCode == ErrorCode.SW_PROVIDER_UNAVAILABLE
                || finalCode == ErrorCode.SW_PROVIDER_TIMEOUT) {
            finalCode = ErrorCode.SW_MODEL_UNAVAILABLE;
        }
        if (metrics != null) {
            metrics.recordFailure(finalCode.name());
        }
        throw new io.github.nephets.switchyard.core.SwitchyardException(
                finalCode, "路由 " + routeName + " 的降级链已全部失败", trace.traceId(), lastError);
    }

    /** 配额预留。 */
    private Reservation reserve(ModelRef model, GatewayRequest request, TraceContext trace) {
        if (!properties.getQuota().isEnabled() || quotaStore == null) {
            // 配额关闭：授予 0 token 的空预留，让后续 settle 不会误扣真实用量
            return Reservation.granted(
                    "noop",
                    0L,
                    new QuotaKey(
                            properties.getQuota().getDefaultTenant(),
                            null,
                            model.providerId(),
                            model.modelId()),
                    trace == null ? null : java.time.Instant.now());
        }
        long estimated = estimateTokens(request);
        QuotaKey key =
                new QuotaKey(
                        properties.getQuota().getDefaultTenant(),
                        null,
                        model.providerId(),
                        model.modelId());
        Reservation reservation = quotaStore.reserve(key, estimated);
        if (!reservation.granted()) {
            log.warn(
                    "quota reserved: key={} requested={} used={} limit={} traceId={}",
                    key.text(),
                    estimated,
                    quotaStore.snapshot(key).used(),
                    quotaStore.snapshot(key).limit(),
                    trace.traceId());
        }
        return reservation;
    }

    /** 估算预留 token：prompt 估算 + maxTokens 上界。 */
    private long estimateTokens(GatewayRequest request) {
        long prompt = estimator.estimate(request.renderForEstimation());
        long maxOut =
                request.maxTokens() == null
                        ? properties.getMetering().getEstimatedTokensPerRequest()
                        : request.maxTokens();
        return prompt + maxOut;
    }

    /** 把 provider 返回的 usage 归一化（provider 已归一化过，这里只做兜底与一致性校验）。 */
    private Usage normalizeUsage(GatewayResponse response, GatewayRequest request) {
        Usage usage = response.usage();
        if (usage == null) {
            return Usage.estimated(
                    io.github.nephets.switchyard.core.usage.UsageSource.ESTIMATED,
                    response.chunks() != null && !response.chunks().isEmpty());
        }
        return usage;
    }

    /** 计算成本。 */
    private BigDecimal computeCost(ModelRef model, Usage usage) {
        if (pricingTable == null) {
            return BigDecimal.ZERO.setScale(
                    CostCalculator.INTERNAL_SCALE, java.math.RoundingMode.HALF_UP);
        }
        return costCalculator.compute(
                usage, pricingTable.lookup(model.providerId(), model.modelId()).orElse(null));
    }

    /** 写审计。 */
    private void writeAudit(
            TraceContext trace,
            ModelRef model,
            Usage usage,
            BigDecimal cost,
            GatewayRequest request) {
        if (auditSink == null) {
            return;
        }
        AuditEvent event =
                new AuditEvent(
                        io.github.nephets.switchyard.core.audit.AuditEventType.MODEL_CALL,
                        trace.traceId(),
                        trace.spanId(),
                        java.time.Instant.now(),
                        properties.getQuota().getDefaultTenant(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        usage,
                        cost,
                        "CNY",
                        io.github.nephets.switchyard.core.trace.W3CTraceCodec.digest(
                                request.renderForEstimation()),
                        properties.getAudit().isCapturePayload()
                                ? request.renderForEstimation()
                                : null);
        try {
            auditSink.write(event);
        } catch (RuntimeException ex) {
            // ★ 审计是旁路：它的实现会被用户替换（写 Kafka / 推 OTLP / 落 ClickHouse），
            //   那些实现完全可能抛异常。这里必须兜住 ——
            //   否则一次审计后端故障会连带把用户的 LLM 调用一起搞挂，
            //   而审计丢失只是「少一条记录」，两者的严重程度天差地别。
            log.warn("audit write failed, 已忽略：traceId={}", event.traceId(), ex);
        }
    }

    /** 记录熔断器结果。 */
    private void recordBreaker(ModelRef model, RuntimeException error) {
        if (!properties.getCircuitBreaker().isEnabled()) {
            return;
        }
        CircuitBreakerHolder.record(breakers, model, error);
    }

    /** 取或建适配器。 */
    private ChatProvider providerOf(ModelRef model) {
        return providerCache.computeIfAbsent(
                model.providerId(),
                id -> {
                    ChatProvider provider = providerResolver.apply(id);
                    if (provider == null) {
                        throw new io.github.nephets.switchyard.core.SwitchyardException(
                                ErrorCode.SW_INTERNAL,
                                "未配置供应商：" + id,
                                TraceContext.current().traceId());
                    }
                    return provider;
                });
    }

    /** Spring AI Prompt → 统一请求。 */
    private GatewayRequest toGatewayRequest(Prompt prompt, boolean stream) {
        List<GatewayRequest.Message> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            GatewayRequest.MessageRole role =
                    switch (message.getMessageType()) {
                        case SYSTEM -> GatewayRequest.MessageRole.SYSTEM;
                        case ASSISTANT -> GatewayRequest.MessageRole.ASSISTANT;
                        case TOOL -> GatewayRequest.MessageRole.TOOL;
                        default -> GatewayRequest.MessageRole.USER;
                    };
            messages.add(new GatewayRequest.Message(role, message.getText()));
        }
        ChatOptions options = prompt.getOptions();
        Integer maxTokens = options == null ? null : options.getMaxTokens();
        Double temperature = options == null ? null : options.getTemperature();
        Double topP = options == null ? null : options.getTopP();

        ModelRef ref = ModelRef.of("default", "unknown");
        List<ModelRef> chain = chainResolver.apply(routeName);
        if (chain != null && !chain.isEmpty()) {
            ref = chain.get(0);
        }
        return new GatewayRequest(
                messages, ref, maxTokens, temperature, topP, stream, List.of(), null);
    }

    /** 统一响应 → Spring AI ChatResponse（保留 usage 供业务侧读取）。 */
    private ChatResponse toChatResponse(GatewayResponse gateway) {
        AssistantMessage message = new AssistantMessage(gateway.content());
        Generation generation =
                new Generation(
                        message,
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                .finishReason(gateway.finishReason())
                                .build());
        Usage usage = gateway.usage();
        ChatResponseMetadata metadata =
                ChatResponseMetadata.builder()
                        .model(gateway.modelRef().modelId())
                        .usage(
                                new DefaultUsage(
                                        (int) usage.inputTokens(),
                                        (int) usage.outputTokens(),
                                        (int) usage.totalTokens(),
                                        usage))
                        .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    /** 配额存储访问器（控制台与 CLI 设置配额时用）。 */
    public QuotaStore quotaStore() {
        return quotaStore;
    }

    /** 取最近一次调用上下文（供 QuotaWebFilter 写响应头）。 */
    public CallContext lastCallContext() {
        return lastCall.get();
    }

    /** 清理线程绑定的上下文，避免线程复用串号。 */
    public void clearLastCallContext() {
        lastCall.remove();
    }

    /** 一次调用的结果上下文。 */
    public record CallContext(
            QuotaSnapshot snapshot,
            BigDecimal cost,
            Usage usage,
            ModelRef model,
            TraceContext trace,
            ErrorResponse error) {

        /**
         * 被配额拒绝时的上下文。
         *
         * @param reservation 预留凭据
         * @param trace trace 上下文
         * @return 上下文
         */
        static CallContext quotaRejected(Reservation reservation, TraceContext trace) {
            ErrorResponse response =
                    ErrorResponse.of(
                            ErrorCode.SW_QUOTA_EXCEEDED,
                            reservation.reason(),
                            trace == null ? null : trace.traceId());
            return new CallContext(
                    null,
                    BigDecimal.ZERO,
                    Usage.estimated(
                            io.github.nephets.switchyard.core.usage.UsageSource.FALLBACK, false),
                    null,
                    trace,
                    response);
        }

        /**
         * fail-soft 的统一响应（PRD P0-5：超限返回业务级错误码，<b>不抛异常</b>、不产生 5xx）。
         *
         * <p>返回 {@link GatewayResponse} 而非 Spring 的 {@code ChatResponse}，是为了让 fail-soft 路径 与成功路径在
         * {@code invokeWithPolicies} 里<b>同类型</b>：调用方不需要为「配额拒绝」
         * 写一个单独的分支，也不会出现「一半路径抛异常、一半路径返回空响应」的不一致。
         *
         * @param model 被选中的模型
         * @return 携带友好提示的响应
         */
        public GatewayResponse toFailSoftResponse(ModelRef model) {
            Usage zero =
                    usage == null
                            ? Usage.estimated(
                                    io.github.nephets.switchyard.core.usage.UsageSource.FALLBACK,
                                    false)
                            : usage;
            String message = error == null ? "" : error.message();
            return new GatewayResponse(
                    message,
                    List.of(),
                    zero,
                    model == null ? ModelRef.of("unknown", "unknown") : model,
                    "quota_exceeded",
                    0L,
                    false,
                    1,
                    List.of());
        }

        /**
         * 剩余配额。
         *
         * <p>返回 -1 表示「不适用」：配额被关闭、未配置，或配置为不限额。 刻意<b>不</b>返回 {@link Long#MAX_VALUE} ——
         * 那个值在响应头里会被业务方当成 「还剩 922 亿 token」而当真，而它实际表达的是「这个维度上没有上限」， 两者的排查含义完全不同。
         */
        public long remainingQuota() {
            if (snapshot == null || snapshot.unlimited()) {
                return -1L;
            }
            return snapshot.remaining();
        }

        /** 本次成本。 */
        public BigDecimal costAmount() {
            return cost == null ? BigDecimal.ZERO : cost;
        }

        /** 是否为 fail-soft 拒绝。 */
        public boolean rejected() {
            return error != null;
        }
    }

    /** 熔断器记录辅助（保持构造器参数数量可控）。 */
    private static final class CircuitBreakerHolder {
        private CircuitBreakerHolder() {}

        static void record(
                CircuitBreakerRegistry registry, ModelRef model, RuntimeException error) {
            if (registry == null || model == null) {
                return;
            }
            io.github.nephets.switchyard.core.resilience.CircuitBreaker breaker =
                    registry.breakerOf(model);
            if (error == null) {
                breaker.recordSuccess();
            } else if (new DefaultErrorClassifier().isRetryable(error)) {
                breaker.recordFailure();
            } else {
                // 业务错误（4xx）不该污染熔断器：它说明上游是健康的，只是这个请求有问题
                breaker.recordSuccess();
            }
        }
    }
}
