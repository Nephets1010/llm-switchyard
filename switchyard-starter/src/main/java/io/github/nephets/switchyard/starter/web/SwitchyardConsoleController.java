/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.web;

import io.github.nephets.switchyard.core.provider.ProviderCatalogEntry;
import io.github.nephets.switchyard.starter.SwitchyardRouter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台 JSON 端点：首版只出 3 个端点 + 一张静态图，<b>不做 SPA</b>。
 *
 * <p><b>为什么首版不做前端</b>（架构开放问题 Q-C）：PRD 的 UI 图很完整， 但做一套 SPA 会把工期吃掉 8–10 天，而那段时间本该花在「计量精度」这个护城河上。
 * 首版的选择是：把数据以稳定的 JSON 契约暴露出来，让用户在现有监控/BI 系统里直接接； 前端放到 P1，届时 JSON 契约已经稳定，重做 UI 不会返工。
 *
 * <p>三个端点分别是：{@code /overview}（总览）、{@code /quota}（配额）、{@code /models}（模型与能力矩阵）。
 */
@RestController
@RequestMapping("${llm.switchyard.console.base-path:/_switchyard}")
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
@ConditionalOnProperty(
        prefix = "llm.switchyard.console",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SwitchyardConsoleController {

    private final SwitchyardRouter router;
    private final io.github.nephets.switchyard.starter.SwitchyardMetrics metrics;

    /**
     * 构造。
     *
     * @param router 门面
     * @param metrics 指标累加器
     */
    public SwitchyardConsoleController(
            SwitchyardRouter router,
            io.github.nephets.switchyard.starter.SwitchyardMetrics metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    /**
     * 总览：今日成本、token 用量、熔断状态。
     *
     * <p>PRD UI 图上的三张核心卡片对应的就是这个端点的三个 key： {@code cost} / {@code tokens} / {@code circuitBreakers}。
     *
     * @return 总览 JSON
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> snapshot = metrics.snapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> counters = (Map<String, Object>) snapshot.get("counters");

        Map<String, SwitchyardRouter.ModelHealth> health = router.health();
        result.put("models", new LinkedHashMap<String, Object>(health));
        result.put(
                "circuitBreakers",
                health.values().stream()
                        .filter(h -> "OPEN".equals(h.circuitState()))
                        .map(SwitchyardRouter.ModelHealth::model)
                        .toList());

        // ★ 成本与 token 来自真实调用累加，不是占位 0 —— README 承诺「30 秒看到控制台」，
        //   如果数字全是 0，读者就无法判断「是不是真的通了」。
        result.put(
                "cost",
                Map.of(
                        "currency", "CNY",
                        "totalAmount", String.valueOf(snapshot.get("totalCostCny")),
                        "scope", String.valueOf(snapshot.get("scope"))));

        result.put(
                "tokens",
                Map.of(
                        "input", counters.getOrDefault("tokens.input", 0L),
                        "output", counters.getOrDefault("tokens.output", 0L),
                        "cachedInput", counters.getOrDefault("tokens.cached", 0L),
                        "reasoning", counters.getOrDefault("tokens.reasoning", 0L),
                        "total", counters.getOrDefault("tokens.total", 0L),
                        // ★ 流式计量是否真的拿到了 usage —— PRD 痛点 4 的核心指标
                        "streamReported", counters.getOrDefault("usage.streamReported", 0L),
                        "streamNotReported", counters.getOrDefault("usage.streamNotReported", 0L)));

        result.put("totalCalls", snapshot.get("totalCalls"));
        result.put("totalFailures", snapshot.get("totalFailures"));
        result.put("degradedCalls", snapshot.get("degradedCalls"));
        result.put("quotaRejected", counters.getOrDefault("quota.rejected", 0L));
        result.put("routes", router.routeNames());
        return ResponseEntity.ok(result);
    }

    /**
     * 配额视图。
     *
     * @return 配额 JSON
     */
    @GetMapping("/quota")
    public ResponseEntity<Map<String, Object>> quota() {
        Map<String, Object> snapshots = new LinkedHashMap<>();
        for (String routeName : router.routeNames()) {
            var route = router.properties().getRoutes().get(routeName);
            if (route == null || route.getPrimary() == null) {
                continue;
            }
            var ref = io.github.nephets.switchyard.core.model.ModelRef.parse(route.getPrimary());
            var key =
                    new io.github.nephets.switchyard.core.quota.QuotaKey(
                            router.properties().getQuota().getDefaultTenant(),
                            null,
                            ref.providerId(),
                            ref.modelId());
            var snapshot = router.quotaOf(key);
            snapshots.put(
                    key.text(),
                    Map.of(
                            "used", snapshot.used(),
                            "limit", snapshot.limit(),
                            "remaining", snapshot.remaining(),
                            "utilization", snapshot.utilization(),
                            "inFlightReservations", snapshot.reservations(),
                            "resetAt", String.valueOf(snapshot.resetAt())));
        }
        return ResponseEntity.ok(Map.of("quota", snapshots));
    }

    /**
     * 模型与能力矩阵。
     *
     * @return 模型 JSON
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models() {
        List<Map<String, Object>> providers = new ArrayList<>();
        for (ProviderCatalogEntry entry : router.catalog().all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("providerId", entry.providerId());
            item.put("alias", entry.alias());
            item.put("protocol", entry.protocol().name());
            item.put("defaultBaseUrl", entry.defaultBaseUrl());
            item.put("capabilities", entry.capabilities().names());
            item.put("defaultModels", entry.defaultModels());
            providers.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providers", providers);
        result.put("health", router.health());
        result.put("routes", router.routeNames());
        return ResponseEntity.ok(result);
    }
}
