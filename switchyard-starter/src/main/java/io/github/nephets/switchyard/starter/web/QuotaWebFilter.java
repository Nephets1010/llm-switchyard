/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.web;

import io.github.nephets.switchyard.starter.RoutedChatModel;
import io.github.nephets.switchyard.starter.SwitchyardRouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.math.BigDecimal;
import java.util.Collection;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 把计量与配额结果写进响应头。
 *
 * <p>响应头是本项目对「可观测性最低成本方案」的判断：让业务方<b>不改任何代码</b> 就能从网关响应里看到这次调用花了多少钱、还剩多少额度。 业务方已经在用 APM 采集 HTTP
 * 响应头，这几个头会<b>自动</b>进入他们的成本看板。
 *
 * <p>头名：
 *
 * <ul>
 *   <li>{@code X-Switchyard-Trace-Id} —— 把 traceId 交给业务方，一次报错就能定位审计记录
 *   <li>{@code X-Switchyard-Cost-Cny} —— 本次成本（scale=4，展示口径）
 *   <li>{@code X-Switchyard-Quota-Remaining} —— 剩余额度
 *   <li>{@code X-Switchyard-Total-Tokens} —— 本次总 token
 *   <li>{@code X-Switchyard-Usage-Source} —— VENDOR_REPORTED / ESTIMATED / FALLBACK
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
public class QuotaWebFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(QuotaWebFilter.class);

    /** traceId 响应头。 */
    public static final String HEADER_TRACE_ID = "X-Switchyard-Trace-Id";

    /** 成本响应头。 */
    public static final String HEADER_COST = "X-Switchyard-Cost-Cny";

    /** 剩余配额响应头。 */
    public static final String HEADER_QUOTA_REMAINING = "X-Switchyard-Quota-Remaining";

    /** 总 token 响应头。 */
    public static final String HEADER_TOTAL_TOKENS = "X-Switchyard-Total-Tokens";

    /** 计量来源响应头。 */
    public static final String HEADER_USAGE_SOURCE = "X-Switchyard-Usage-Source";

    private final SwitchyardRouter router;

    /**
     * 构造。
     *
     * @param router 门面
     */
    public QuotaWebFilter(SwitchyardRouter router) {
        this.router = router;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            jakarta.servlet.FilterChain chain)
            throws jakarta.servlet.ServletException, java.io.IOException {
        chain.doFilter(request, response);
        try {
            writeHeaders(response);
        } catch (RuntimeException ex) {
            // 写响应头失败绝不能影响业务响应
            log.debug("写入 Switchyard 响应头失败", ex);
        } finally {
            clearContexts();
        }
    }

    /** 把最近一次调用的结果写进响应头。 */
    public void writeHeaders(HttpServletResponse response) {
        for (RoutedChatModel model : allModels()) {
            RoutedChatModel.CallContext ctx = model.lastCallContext();
            if (ctx == null) {
                continue;
            }
            if (ctx.trace() != null) {
                response.setHeader(HEADER_TRACE_ID, ctx.trace().traceId());
            }
            if (ctx.usage() != null) {
                response.setHeader(HEADER_TOTAL_TOKENS, String.valueOf(ctx.usage().totalTokens()));
                response.setHeader(HEADER_USAGE_SOURCE, ctx.usage().usageSource().name());
            }
            BigDecimal cost = ctx.costAmount();
            if (cost != null) {
                // 展示口径 scale=4（契约要求内部 8 位、展示 4 位）
                response.setHeader(
                        HEADER_COST,
                        cost.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
            }
            long remaining = ctx.remainingQuota();
            if (remaining >= 0) {
                response.setHeader(HEADER_QUOTA_REMAINING, String.valueOf(remaining));
            }
            return;
        }
    }

    private Collection<RoutedChatModel> allModels() {
        java.util.List<RoutedChatModel> models = new java.util.ArrayList<>();
        for (String routeName : router.routeNames()) {
            models.add(router.routedModel(routeName));
        }
        return models;
    }

    /** 清理线程绑定的上下文，避免线程复用串号。 */
    private void clearContexts() {
        allModels().forEach(RoutedChatModel::clearLastCallContext);
    }
}
