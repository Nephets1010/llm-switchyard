/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import io.github.nephets.switchyard.core.audit.AuditSink;
import io.github.nephets.switchyard.core.audit.RingBufferAuditSink;
import io.github.nephets.switchyard.core.pricing.CostCalculator;
import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.pricing.PricingTableLoader;
import io.github.nephets.switchyard.core.quota.InMemoryQuotaStore;
import io.github.nephets.switchyard.core.quota.QuotaStore;
import io.github.nephets.switchyard.core.resilience.CircuitBreakerRegistry;
import io.github.nephets.switchyard.core.resilience.DefaultErrorClassifier;
import io.github.nephets.switchyard.core.resilience.RetryPolicy;
import io.github.nephets.switchyard.core.usage.DefaultUsageNormalizer;
import io.github.nephets.switchyard.core.usage.TokenEstimator;
import io.github.nephets.switchyard.core.usage.UsageNormalizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;

/**
 * 自动装配入口。
 *
 * <p><b>嵌入式网关的核心是「零侵入」</b>：用户加了依赖、改了几行配置， 业务代码里就多了一个 {@code router.route("default")}，其余一行不改。
 * 要做到这一点，就要求本starter 在用户没显式声明任何 Bean 时提供一整套默认实现， 而用户一旦声明了自己的实现，我们的默认值就自动让位。
 *
 * <p>这就是本类里所有 {@code @ConditionalOnMissingBean} 的意义 —— 它们是「可覆盖」的技术保证，也是嵌入式 starter
 * 与「必须读我文档」的配置框架的分界线。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SwitchyardProperties.class)
@ConditionalOnProperty(
        prefix = "llm.switchyard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ComponentScan(basePackages = "io.github.nephets.switchyard.starter")
@EnableScheduling
public class SwitchyardAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SwitchyardAutoConfiguration.class);

    /** usage 归一化器：用户可替换为自己的实现。 */
    @Bean
    @ConditionalOnMissingBean
    public UsageNormalizer switchyardUsageNormalizer() {
        return new DefaultUsageNormalizer();
    }

    /** token 估算器：字符启发式，用户可替换为 tiktoken 等更精确的实现。 */
    @Bean
    @ConditionalOnMissingBean
    public TokenEstimator switchyardTokenEstimator() {
        return new io.github.nephets.switchyard.core.usage.CharHeuristicTokenEstimator();
    }

    /** 成本计算器。 */
    @Bean
    @ConditionalOnMissingBean
    public CostCalculator switchyardCostCalculator() {
        return new CostCalculator();
    }

    /** 价格表 + 热加载器。 */
    @Bean
    @ConditionalOnMissingBean
    public PricingTable switchyardPricingTable() {
        PricingTable table = new PricingTable(java.util.List.of());
        PricingTableLoader loader = new PricingTableLoader(table);
        int count = loader.loadFromClasspath();
        log.info("switchyard: 内置价格表已加载 {} 条", count);
        return table;
    }

    /** 配额存储（首版单实例）。 */
    @Bean
    @ConditionalOnMissingBean
    public QuotaStore switchyardQuotaStore(SwitchyardProperties properties) {
        return new InMemoryQuotaStore(
                java.time.Clock.systemUTC(), properties.getQuota().getReservationTtl());
    }

    /** 熔断器注册表。 */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry switchyardCircuitBreakerRegistry(
            SwitchyardProperties properties) {
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

    /** 异常分类器。 */
    @Bean
    @ConditionalOnMissingBean
    public DefaultErrorClassifier switchyardErrorClassifier() {
        return new DefaultErrorClassifier();
    }

    /** 重试策略。 */
    @Bean
    @ConditionalOnMissingBean
    public RetryPolicy switchyardRetryPolicy(SwitchyardProperties properties) {
        SwitchyardProperties.Retry config = properties.getRetry();
        return new RetryPolicy(
                config.getMaxAttempts(),
                config.getInitialBackoff(),
                config.getMaxBackoff(),
                config.getMultiplier(),
                config.getJitter());
    }

    /** HTTP 客户端。 */
    @Bean
    @ConditionalOnMissingBean
    public HttpClient switchyardHttpClient() {
        return io.github.nephets.switchyard.provider.support.ProviderHttpClientFactory.create(
                java.time.Duration.ofSeconds(10), null);
    }

    /** 审计出口。 */
    @Bean
    @ConditionalOnMissingBean
    public AuditSink switchyardAuditSink(SwitchyardProperties properties) {
        return switch (properties.getAudit().getType()) {
            case RINGBUFFER ->
                    new RingBufferAuditSink(properties.getAudit().getRingBufferCapacity());
            case NONE ->
                    new LoggingAuditSinkAdapter() {
                        @Override
                        public String name() {
                            return "noop";
                        }

                        @Override
                        public void write(
                                io.github.nephets.switchyard.core.audit.AuditEvent event) {
                            // 显式关闭审计：什么都不做
                        }
                    };
            case LOGGING -> new LoggingAuditSinkAdapter();
        };
    }

    /** 指标累加器（供控制台 /overview 提供真实数字）。 */
    @Bean
    @ConditionalOnMissingBean
    public SwitchyardMetrics switchyardMetrics() {
        return new SwitchyardMetrics();
    }

    /** 配额快照读取器。 */
    @Bean
    @ConditionalOnMissingBean
    public QuotaSnapshotProvider switchyardQuotaSnapshotProvider(QuotaStore quotaStore) {
        return key -> quotaStore.snapshot(key);
    }

    /** 装饰器工厂。 */
    @Bean
    @ConditionalOnMissingBean
    public RoutedChatModelFactory switchyardRoutedChatModelFactory(
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
            DefaultErrorClassifier classifier,
            SwitchyardMetrics metrics) {
        return new RoutedChatModelFactory(
                properties,
                quotaStore,
                breakers,
                normalizer,
                estimator,
                costCalculator,
                pricingTable,
                auditSink,
                httpClient,
                retryPolicy,
                classifier,
                metrics);
    }

    /**
     * 门面。
     *
     * @param factory 工厂
     * @param quotaSnapshots 配额快照读取器
     * @param breakers 熔断器注册表
     * @param pricingTable 价格表
     * @param properties 配置
     * @return 门面
     */
    @Bean
    @ConditionalOnMissingBean
    public SwitchyardRouter switchyardRouter(
            RoutedChatModelFactory factory,
            QuotaSnapshotProvider quotaSnapshots,
            CircuitBreakerRegistry breakers,
            PricingTable pricingTable,
            SwitchyardProperties properties) {
        return new SwitchyardRouter(properties, factory, quotaSnapshots, breakers, pricingTable);
    }
}
