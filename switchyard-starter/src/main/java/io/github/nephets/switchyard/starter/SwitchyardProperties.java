/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关配置（{@code llm.switchyard.*}）。
 *
 * <p><b>设计取向：显式优于魔法</b>。每一个会影响钱、影响可用性、影响合规的开关都暴露为配置， 而不是藏在代码里。对企业用户来说，「我能看见并修改这个值」比「它工作得很好」更重要 ——
 * 因为他们要向审计解释。
 */
@ConfigurationProperties(prefix = "llm.switchyard")
public class SwitchyardProperties {

    /** 是否启用网关。关掉后 starter 完全不装配，业务回到原生 ChatModel。 */
    private boolean enabled = true;

    /** 默认路由名。 */
    private String defaultRoute = "default";

    /** 路由表：路由名 → 路由定义。 */
    private Map<String, RouteConfig> routes = new LinkedHashMap<>();

    /** 供应商配置表：别名 → 供应商定义。 */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    /** 计量相关配置。 */
    private Metering metering = new Metering();

    /** 配额相关配置。 */
    private Quota quota = new Quota();

    /** 重试相关配置。 */
    private Retry retry = new Retry();

    /** 熔断相关配置。 */
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();

    /** 审计相关配置。 */
    private Audit audit = new Audit();

    /** 控制台端点配置。 */
    private Console console = new Console();

    /**
     * 判断是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置启用状态。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 取默认路由名。
     *
     * @return 默认路由名
     */
    public String getDefaultRoute() {
        return defaultRoute;
    }

    /**
     * 设置默认路由名。
     *
     * @param defaultRoute 路由名
     */
    public void setDefaultRoute(String defaultRoute) {
        this.defaultRoute = defaultRoute;
    }

    /**
     * 取路由表。
     *
     * @return 路由表
     */
    public Map<String, RouteConfig> getRoutes() {
        return routes;
    }

    /**
     * 设置路由表。
     *
     * @param routes 路由表
     */
    public void setRoutes(Map<String, RouteConfig> routes) {
        this.routes = routes == null ? new LinkedHashMap<>() : routes;
    }

    /**
     * 取供应商表。
     *
     * @return 供应商表
     */
    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    /**
     * 设置供应商表。
     *
     * @param providers 供应商表
     */
    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    /**
     * 取计量配置。
     *
     * @return 计量配置
     */
    public Metering getMetering() {
        return metering;
    }

    /**
     * 设置计量配置。
     *
     * @param metering 计量配置
     */
    public void setMetering(Metering metering) {
        this.metering = metering == null ? new Metering() : metering;
    }

    /**
     * 取配额配置。
     *
     * @return 配额配置
     */
    public Quota getQuota() {
        return quota;
    }

    /**
     * 设置配额配置。
     *
     * @param quota 配额配置
     */
    public void setQuota(Quota quota) {
        this.quota = quota == null ? new Quota() : quota;
    }

    /**
     * 取重试配置。
     *
     * @return 重试配置
     */
    public Retry getRetry() {
        return retry;
    }

    /**
     * 设置重试配置。
     *
     * @param retry 重试配置
     */
    public void setRetry(Retry retry) {
        this.retry = retry == null ? new Retry() : retry;
    }

    /**
     * 取熔断配置。
     *
     * @return 熔断配置
     */
    public CircuitBreakerConfig getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * 设置熔断配置。
     *
     * @param circuitBreaker 熔断配置
     */
    public void setCircuitBreaker(CircuitBreakerConfig circuitBreaker) {
        this.circuitBreaker = circuitBreaker == null ? new CircuitBreakerConfig() : circuitBreaker;
    }

    /**
     * 取审计配置。
     *
     * @return 审计配置
     */
    public Audit getAudit() {
        return audit;
    }

    /**
     * 设置审计配置。
     *
     * @param audit 审计配置
     */
    public void setAudit(Audit audit) {
        this.audit = audit == null ? new Audit() : audit;
    }

    /**
     * 取控制台配置。
     *
     * @return 控制台配置
     */
    public Console getConsole() {
        return console;
    }

    /**
     * 设置控制台配置。
     *
     * @param console 控制台配置
     */
    public void setConsole(Console console) {
        this.console = console == null ? new Console() : console;
    }

    /** 路由定义。 */
    public static class RouteConfig {
        /** 主模型，形如 {@code openai/gpt-5-mini}。 */
        private String primary;

        /** 降级链。 */
        private List<String> fallbacks = new ArrayList<>();

        /**
         * 取主模型。
         *
         * @return 主模型
         */
        public String getPrimary() {
            return primary;
        }

        /**
         * 设置主模型。
         *
         * @param primary 主模型
         */
        public void setPrimary(String primary) {
            this.primary = primary;
        }

        /**
         * 取降级链。
         *
         * @return 降级链
         */
        public List<String> getFallbacks() {
            return fallbacks;
        }

        /**
         * 设置降级链。
         *
         * @param fallbacks 降级链
         */
        public void setFallbacks(List<String> fallbacks) {
            this.fallbacks = fallbacks == null ? new ArrayList<>() : fallbacks;
        }
    }

    /** 供应商定义。 */
    public static class ProviderConfig {
        /** API Key。强烈建议用环境变量注入，不要写进配置文件。 */
        private String apiKey;

        /** base-url 覆盖（用于代理、区域端点、私有部署）。 */
        private String baseUrl;

        /** 请求超时。 */
        private Duration timeout = Duration.ofSeconds(120);

        /** 是否启用。 */
        private boolean enabled = true;

        /**
         * 取 API Key。
         *
         * @return API Key
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 设置 API Key。
         *
         * @param apiKey API Key
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 取 base-url。
         *
         * @return base-url
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置 base-url。
         *
         * @param baseUrl base-url
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 取超时。
         *
         * @return 超时
         */
        public Duration getTimeout() {
            return timeout;
        }

        /**
         * 设置超时。
         *
         * @param timeout 超时
         */
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * 是否启用。
         *
         * @return 启用返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置启用状态。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 计量配置。 */
    public static class Metering {
        /**
         * 中文与 CJK 字符折算的 token 数。
         *
         * <p>默认 1.0 是保守中值：宁可略微高估也不要低估配额。
         */
        private double cjkPerToken = 1.0d;

        /** ASCII 字符每 token 对应的字符数。 */
        private double asciiCharsPerToken = 4.0d;

        /** 预估单请求的 token 数（用于配额预留时的粗估）。 */
        private int estimatedTokensPerRequest = 1000;

        /**
         * 取中文折算系数。
         *
         * @return 系数
         */
        public double getCjkPerToken() {
            return cjkPerToken;
        }

        /**
         * 设置中文折算系数。
         *
         * @param cjkPerToken 系数
         */
        public void setCjkPerToken(double cjkPerToken) {
            this.cjkPerToken = cjkPerToken;
        }

        /**
         * 取 ASCII 折算参数。
         *
         * @return 参数
         */
        public double getAsciiCharsPerToken() {
            return asciiCharsPerToken;
        }

        /**
         * 设置 ASCII 折算参数。
         *
         * @param asciiCharsPerToken 参数
         */
        public void setAsciiCharsPerToken(double asciiCharsPerToken) {
            this.asciiCharsPerToken = asciiCharsPerToken;
        }

        /**
         * 取单请求预估 token。
         *
         * @return token 数
         */
        public int getEstimatedTokensPerRequest() {
            return estimatedTokensPerRequest;
        }

        /**
         * 设置单请求预估 token。
         *
         * @param estimatedTokensPerRequest token 数
         */
        public void setEstimatedTokensPerRequest(int estimatedTokensPerRequest) {
            this.estimatedTokensPerRequest = estimatedTokensPerRequest;
        }
    }

    /** 配额配置。 */
    public static class Quota {
        /** 是否启用配额。 */
        private boolean enabled = true;

        /** 超限行为。 */
        private OverLimitBehaviorName onExceeded = OverLimitBehaviorName.FAIL_SOFT;

        /** 默认租户。 */
        private String defaultTenant = "_default";

        /** 预留有效期；超时未结算的预留会被自动回收，防止额度泄漏。 */
        private Duration reservationTtl = Duration.ofMinutes(10);

        /**
         * 是否启用。
         *
         * @return 启用返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置启用状态。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 取超限行为。
         *
         * @return 超限行为
         */
        public OverLimitBehaviorName getOnExceeded() {
            return onExceeded;
        }

        /**
         * 设置超限行为。
         *
         * @param onExceeded 超限行为
         */
        public void setOnExceeded(OverLimitBehaviorName onExceeded) {
            this.onExceeded = onExceeded == null ? OverLimitBehaviorName.FAIL_SOFT : onExceeded;
        }

        /**
         * 取默认租户。
         *
         * @return 租户
         */
        public String getDefaultTenant() {
            return defaultTenant;
        }

        /**
         * 设置默认租户。
         *
         * @param defaultTenant 租户
         */
        public void setDefaultTenant(String defaultTenant) {
            this.defaultTenant = defaultTenant;
        }

        /**
         * 取预留有效期。
         *
         * @return 有效期
         */
        public Duration getReservationTtl() {
            return reservationTtl;
        }

        /**
         * 设置预留有效期。
         *
         * @param reservationTtl 有效期
         */
        public void setReservationTtl(Duration reservationTtl) {
            this.reservationTtl = reservationTtl == null ? Duration.ofMinutes(10) : reservationTtl;
        }
    }

    /** 超限行为枚举（与 core 的 OverLimitBehavior 字面一致，便于配置绑定）。 */
    public enum OverLimitBehaviorName {
        /** 返回 429 业务响应，不抛异常。 */
        FAIL_SOFT,
        /** 触发熔断。 */
        TRIP_BREAKER
    }

    /** 重试配置。 */
    public static class Retry {
        /** 是否启用重试。 */
        private boolean enabled = true;

        /** 最大尝试次数（含首次）。 */
        private int maxAttempts = 3;

        /** 首次退避。 */
        private Duration initialBackoff = Duration.ofMillis(200);

        /** 退避上限。 */
        private Duration maxBackoff = Duration.ofSeconds(2);

        /** 退避倍数。 */
        private double multiplier = 2.0d;

        /** 抖动比例，防重试风暴。 */
        private double jitter = 0.2d;

        /**
         * 是否启用。
         *
         * @return 启用返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置启用状态。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 取最大尝试次数。
         *
         * @return 次数
         */
        public int getMaxAttempts() {
            return maxAttempts;
        }

        /**
         * 设置最大尝试次数。
         *
         * @param maxAttempts 次数
         */
        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        /**
         * 取首次退避。
         *
         * @return 退避时长
         */
        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        /**
         * 设置首次退避。
         *
         * @param initialBackoff 退避时长
         */
        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        /**
         * 取退避上限。
         *
         * @return 退避上限
         */
        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        /**
         * 设置退避上限。
         *
         * @param maxBackoff 退避上限
         */
        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        /**
         * 取退避倍数。
         *
         * @return 倍数
         */
        public double getMultiplier() {
            return multiplier;
        }

        /**
         * 设置退避倍数。
         *
         * @param multiplier 倍数
         */
        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        /**
         * 取抖动比例。
         *
         * @return 抖动比例
         */
        public double getJitter() {
            return jitter;
        }

        /**
         * 设置抖动比例。
         *
         * @param jitter 抖动比例
         */
        public void setJitter(double jitter) {
            this.jitter = jitter;
        }
    }

    /** 熔断配置。 */
    public static class CircuitBreakerConfig {
        /** 是否启用熔断。 */
        private boolean enabled = true;

        /** 滑动窗口大小。 */
        private int windowSize = 20;

        /** 失败率阈值。 */
        private double failureThreshold = 0.5d;

        /** 熔断打开时长。 */
        private Duration openDuration = Duration.ofSeconds(30);

        /**
         * 是否启用。
         *
         * @return 启用返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置启用状态。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 取窗口大小。
         *
         * @return 窗口大小
         */
        public int getWindowSize() {
            return windowSize;
        }

        /**
         * 设置窗口大小。
         *
         * @param windowSize 窗口大小
         */
        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        /**
         * 取失败率阈值。
         *
         * @return 阈值
         */
        public double getFailureThreshold() {
            return failureThreshold;
        }

        /**
         * 设置失败率阈值。
         *
         * @param failureThreshold 阈值
         */
        public void setFailureThreshold(double failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        /**
         * 取打开时长。
         *
         * @return 打开时长
         */
        public Duration getOpenDuration() {
            return openDuration;
        }

        /**
         * 设置打开时长。
         *
         * @param openDuration 打开时长
         */
        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }
    }

    /** 审计配置。 */
    public static class Audit {
        /** 审计类型：logging / ringbuffer / none。 */
        private AuditType type = AuditType.LOGGING;

        /** ★ 是否落完整 payload 原文。默认 false —— 金融/客服场景的 prompt 里常含身份证与手机号， 落全量原文等于制造 PII 泄露点。 */
        private boolean capturePayload = false;

        /** 内存环形缓冲容量（type=ringbuffer 时生效）。 */
        private int ringBufferCapacity = 1000;

        /**
         * 取审计类型。
         *
         * @return 审计类型
         */
        public AuditType getType() {
            return type;
        }

        /**
         * 设置审计类型。
         *
         * @param type 审计类型
         */
        public void setType(AuditType type) {
            this.type = type == null ? AuditType.LOGGING : type;
        }

        /**
         * 是否落完整 payload。
         *
         * @return 落原文返回 true
         */
        public boolean isCapturePayload() {
            return capturePayload;
        }

        /**
         * 设置是否落完整 payload。
         *
         * @param capturePayload 是否落原文
         */
        public void setCapturePayload(boolean capturePayload) {
            this.capturePayload = capturePayload;
        }

        /**
         * 取环形缓冲容量。
         *
         * @return 容量
         */
        public int getRingBufferCapacity() {
            return ringBufferCapacity;
        }

        /**
         * 设置环形缓冲容量。
         *
         * @param ringBufferCapacity 容量
         */
        public void setRingBufferCapacity(int ringBufferCapacity) {
            this.ringBufferCapacity = ringBufferCapacity;
        }
    }

    /** 审计实现类型。 */
    public enum AuditType {
        /** 结构化 JSON 日志（logback）。默认。 */
        LOGGING,
        /** 内存环形缓冲，供控制台与演示查看。 */
        RINGBUFFER,
        /** 关闭审计。 */
        NONE
    }

    /** 控制台配置。 */
    public static class Console {
        /** 是否启用控制台 JSON 端点。 */
        private boolean enabled = true;

        /** 端点前缀。 */
        private String basePath = "/_switchyard";

        /**
         * 是否启用。
         *
         * @return 启用返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置启用状态。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 取端点前缀。
         *
         * @return 前缀
         */
        public String getBasePath() {
            return basePath;
        }

        /**
         * 设置端点前缀。
         *
         * @param basePath 前缀
         */
        public void setBasePath(String basePath) {
            this.basePath = basePath == null || basePath.isBlank() ? "/_switchyard" : basePath;
        }
    }
}
