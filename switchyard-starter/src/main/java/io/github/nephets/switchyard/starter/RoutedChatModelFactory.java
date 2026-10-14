/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.audit.AuditSink;
import io.github.nephets.switchyard.core.model.ModelRef;
import io.github.nephets.switchyard.core.pricing.CostCalculator;
import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.provider.ChatProvider;
import io.github.nephets.switchyard.core.provider.ProviderCatalog;
import io.github.nephets.switchyard.core.provider.ProviderCatalogEntry;
import io.github.nephets.switchyard.core.quota.QuotaStore;
import io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry;
import io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier;
import io.github.nephets.switchyard.core.resilience.ErrorClassifier;
import io.github.nephets.switchyard.core.resilience.RetryExecutor;
import io.github.nephets.switchyard.core.resilience.RetryPolicy;
import io.github.nephets.switchyard.core.usage.CharHeuristicTokenEstimator;
import io.github.nephets.switchyard.core.usage.TokenEstimator;
import io.github.nephets.switchyard.core.usage.UsageNormalizer;
import io.github.nephets.switchyard.provider.anthropic.AnthropicProvider;
import io.github.nephets.switchyard.provider.ollama.OllamaProvider;
import io.github.nephets.switchyard.provider.openai.OpenAiCompatibleProvider;
import io.github.nephets.switchyard.provider.support.ProviderHttpClientFactory;

import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 装配 {@link RoutedChatModel}：把配置翻译成对象图。
 *
 * <p>把装配逻辑集中在这一处的理由：一旦分散到各个 {@code @Bean} 方法里， 「某个配置项到底影响了什么」就再也说不清了 —— 而这正是排查线上问题时最需要的答案。
 */
@Component
public class RoutedChatModelFactory {

    private final SwitchyardProperties properties;
    private final QuotaStore quotaStore;
    private final CircuitBreakerRegistry breakers;
    private final UsageNormalizer normalizer;
    private final TokenEstimator estimator;
    private final CostCalculator costCalculator;
    private final PricingTable pricingTable;
    private final AuditSink auditSink;
    private final HttpClient httpClient;
    private final RetryPolicy retryPolicy;
    private final ErrorClassifier classifier;
    private final SwitchyardMetrics metrics;

    /**
     * 构造。
     *
     * @param properties 配置
     * @param quotaStore 配额存储
     * @param breakers 熔断器注册表
     * @param normalizer usage 归一化器
     * @param estimator 估算器
     * @param costCalculator 成本计算器
     * @param pricingTable 价格表
     * @param auditSink 审计出口
     * @param httpClient HTTP 客户端
     * @param retryPolicy 重试策略
     * @param classifier 异常分类器
     * @param metrics 指标累加器
     */
    public RoutedChatModelFactory(
            SwitchyardProperties properties,
            QuotaStore quotaStore,
            CircuitBreakerRegistry breakers,
            UsageNormalizer normalizer,
            TokenEstimator estimator,
            CostCalculator costCalculator,
            PricingTable pricingTable,
            AuditSink auditSink,
            HttpClient httpClient,
            RetryPolicy retryPolicy,
            ErrorClassifier classifier,
            SwitchyardMetrics metrics) {
        this.properties = properties;
        this.quotaStore = quotaStore;
        this.breakers = breakers;
        this.normalizer = normalizer;
        this.estimator = estimator;
        this.costCalculator = costCalculator;
        this.pricingTable = pricingTable;
        this.auditSink = auditSink;
        this.httpClient = httpClient;
        this.retryPolicy = retryPolicy;
        this.classifier = classifier;
        this.metrics = metrics;
    }

    /**
     * 创建一个路由的装饰器。
     *
     * @param routeName 路由名
     * @return 装饰器
     */
    public RoutedChatModel create(String routeName) {
        SwitchyardProperties.Retry retryConfig = properties.getRetry();
        RetryExecutor executor =
                retryConfig.isEnabled()
                        ? new RetryExecutor(retryPolicy, classifier, Thread::sleep)
                        : new RetryExecutor(
                                io.github.nephets.switchyard.core.resilience.RetryPolicy.noRetry(),
                                classifier,
                                Thread::sleep);

        return new RoutedChatModel(
                routeName,
                properties,
                this::resolveProvider,
                this::resolveChain,
                quotaStore,
                breakers,
                executor,
                classifier,
                normalizer,
                estimator,
                costCalculator,
                pricingTable,
                auditSink,
                metrics);
    }

    /** 解析模型链：主模型 + 降级链。 */
    private List<ModelRef> resolveChain(String routeName) {
        SwitchyardProperties.RouteConfig route = properties.getRoutes().get(routeName);
        List<ModelRef> chain = new ArrayList<>();
        if (route == null) {
            // 路由未配置时用默认路由兜底，让「忘记配了」不至于变成一个难懂的启动崩溃
            route = properties.getRoutes().get(properties.getDefaultRoute());
        }
        if (route == null || route.getPrimary() == null) {
            return chain;
        }
        chain.add(ModelRef.parse(route.getPrimary()));
        for (String fallback : route.getFallbacks()) {
            if (fallback != null && !fallback.isBlank()) {
                chain.add(ModelRef.parse(fallback));
            }
        }
        return chain;
    }

    /** 按 providerId 解析适配器（3 个适配器覆盖 9 家）。 */
    private ChatProvider resolveProvider(String providerId) {
        ProviderCatalogEntry entry = ProviderCatalog.builtin().require(providerId);
        SwitchyardProperties.ProviderConfig config = properties.getProviders().get(providerId);
        if (config != null && !config.isEnabled()) {
            return null;
        }
        String baseUrl =
                config != null && config.getBaseUrl() != null
                        ? config.getBaseUrl()
                        : entry.defaultBaseUrl();
        String apiKey = config == null ? null : config.getApiKey();
        Duration timeout = config == null ? null : config.getTimeout();

        return switch (entry.protocol()) {
            case OPENAI_COMPATIBLE ->
                    new OpenAiCompatibleProvider(
                            entry.providerId(),
                            baseUrl,
                            apiKey,
                            httpClient,
                            normalizer,
                            estimator,
                            timeout,
                            entry.capabilities(),
                            "Authorization");
            case ANTHROPIC ->
                    new AnthropicProvider(
                            baseUrl, apiKey, httpClient, normalizer, estimator, timeout);
            case OLLAMA -> new OllamaProvider(baseUrl, httpClient, normalizer, estimator, timeout);
        };
    }

    /** 用配置的熔断参数创建注册表。 */
    public CircuitBreakerRegistry createBreakerRegistry() {
        SwitchyardProperties.CircuitBreakerConfig config = properties.getCircuitBreaker();
        return new CircuitBreakerRegistry(
                name ->
                        new io.github.nephets.switchyard.core.resilience.CircuitBreaker(
                                name,
                                config.getWindowSize(),
                                config.getFailureThreshold(),
                                config.getOpenDuration(),
                                java.time.Clock.systemUTC()));
    }

    /** 用配置的计量参数创建估算器。 */
    public TokenEstimator createEstimator() {
        return new CharHeuristicTokenEstimator(
                properties.getMetering().getCjkPerToken(),
                properties.getMetering().getAsciiCharsPerToken());
    }

    /** 创建 HTTP 客户端。 */
    public HttpClient createHttpClient() {
        return ProviderHttpClientFactory.create(Duration.ofSeconds(10), null);
    }

    /** 用配置的重试参数创建策略。 */
    public RetryPolicy createRetryPolicy() {
        SwitchyardProperties.Retry config = properties.getRetry();
        return new RetryPolicy(
                config.getMaxAttempts(),
                config.getInitialBackoff(),
                config.getMaxBackoff(),
                config.getMultiplier(),
                config.getJitter());
    }

    /** 默认异常分类器。 */
    public ErrorClassifier createClassifier() {
        return new DefaultErrorClassifier();
    }
}
