/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.trace;

/**
 * {@code traceparent} / {@code tracestate} / {@code baggage} 中允许透传的业务维度键名（契约 CTX-001）。
 *
 * <p>这些键与 {@code swy.*} span 属性（契约 SPN-001）一一对应，构成「跨进程归因」的完整链路： HTTP 入口从 {@code baggage}
 * 读出租户与业务线，一路带到每一次模型调用， 最终写进审计事件与 span 属性。缺了它，多微服务场景下的成本归因就断了。
 *
 * <p>⚠️ 这里只允许出现<b>白名单内</b>的键：{@code baggage} 是客户端可写的， 不做白名单就等于允许任意用户往追踪系统里塞数据。
 */
public final class BaggageKeys {

    /** 租户标识，对应 span 属性 {@code swy.tenant.id}。 */
    public static final String TENANT_ID = "swy.tenant.id";

    /** 业务线，对应 span 属性 {@code swy.biz.line}。 */
    public static final String BIZ_LINE = "swy.biz.line";

    /** 业务功能，对应 span 属性 {@code swy.feature}。 */
    public static final String FEATURE = "swy.feature";

    /** Prompt 模板版本，对应 span 属性 {@code swy.prompt.version}。 */
    public static final String PROMPT_VERSION = "swy.prompt.version";

    /** 路由名，对应「哪条路由策略被选中」。 */
    public static final String ROUTE_NAME = "swy.route.name";

    private static final java.util.Set<String> ALLOWED =
            java.util.Set.of(TENANT_ID, BIZ_LINE, FEATURE, PROMPT_VERSION, ROUTE_NAME);

    private BaggageKeys() {
        // 工具类不允许实例化
    }

    /**
     * 判断某个 baggage 键是否在白名单内。
     *
     * @param key 键名
     * @return 白名单内返回 true
     */
    public static boolean isAllowed(String key) {
        return key != null && ALLOWED.contains(key);
    }

    /**
     * 返回全部允许的键名。
     *
     * @return 不可变集合
     */
    public static java.util.Set<String> allowedKeys() {
        return ALLOWED;
    }
}
