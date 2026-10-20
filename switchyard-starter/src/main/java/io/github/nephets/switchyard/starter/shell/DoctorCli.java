/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.shell;

import io.github.nephets.switchyard.core.provider.ProviderCatalogEntry;

import picocli.CommandLine;

/**
 * {@code switchyard doctor}：体检（PRD 的 {@code doctor # 校验各供应商连通性与配额生效情况}）。
 *
 * <p><b>为什么值得单独做一个命令</b>：出问题时第一个要回答的是 「是网关没配好，还是上游挂了，还是配额把流量挡住了」。这三个原因的处理方式完全不同， 而看日志很难一眼分辨。doctor
 * 把答案直接摆出来。
 */
@CommandLine.Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "体检：检查路由、配额、价格表与熔断状态")
public class DoctorCli implements Runnable {

    private final SwitchyardCli parent;

    @CommandLine.Option(
            names = {"--reset-breakers"},
            description = "重置全部熔断器后再检查")
    private boolean resetBreakers;

    /**
     * 构造。
     *
     * @param parent 根命令
     */
    public DoctorCli(SwitchyardCli parent) {
        this.parent = parent;
    }

    @Override
    public void run() {
        onCommand();
    }

    /** picocli 约定的命令入口。 */
    public void onCommand() {
        int problems = 0;

        CliConsole.println("=== 1. 路由配置 ===");
        var routes = parent.router().routeNames();
        if (routes.isEmpty()) {
            CliConsole.println("  [问题] 没有任何路由配置：请设置 llm.switchyard.routes.<name>.primary");
            problems++;
        } else {
            routes.forEach(this::describeRoute);
        }

        CliConsole.println();
        CliConsole.println("=== 2. 价格表 ===");
        int priceEntries = parent.pricingTable().size();
        CliConsole.println("  已加载价格条目：" + priceEntries);
        if (priceEntries == 0) {
            CliConsole.println("  [问题] 价格表为空：成本将为 0，检查 switchyard-prices.yml 是否可读");
            problems++;
        }

        CliConsole.println();
        CliConsole.println("=== 3. 供应商目录 ===");
        parent.router().catalog().all().forEach(this::describeProvider);

        CliConsole.println();
        CliConsole.println("=== 4. 模型健康 ===");
        // 用 AtomicInteger 而非 int：lambda 里需要自增，普通局部变量不是 effectively final
        java.util.concurrent.atomic.AtomicInteger modelProblems =
                new java.util.concurrent.atomic.AtomicInteger();
        var health = parent.router().health();
        health.forEach(
                (model, item) -> {
                    String mark = item.available() ? "[正常]" : "[熔断]";
                    CliConsole.printf(
                            "  %s %-34s state=%-9s calls=%d failureRate=%.0f%% price=%s%n",
                            mark,
                            item.model(),
                            item.circuitState(),
                            item.totalCalls(),
                            item.failureRate() * 100,
                            item.priceEntryKey());
                    if (!item.available()) {
                        modelProblems.incrementAndGet();
                    }
                });
        problems += modelProblems.get();
        if (resetBreakers) {
            CliConsole.println(
                    "  （--reset-breakers 已指定：可通过重启网关或调用 CircuitBreakerRegistry.resetAll() 重置）");
        }

        CliConsole.println();
        if (problems == 0) {
            CliConsole.println("结论：未发现问题");
            return;
        }
        CliConsole.println("结论：发现 " + problems + " 处问题，见上方 [问题] / [熔断] 标记");
        return;
    }

    private void describeRoute(String routeName) {
        var route = parent.router().properties().getRoutes().get(routeName);
        if (route == null || route.getPrimary() == null) {
            CliConsole.printf("  [问题] 路由 %s 未配置 primary%n", routeName);
            return;
        }
        CliConsole.printf(
                "  %s → 主模型 %s，降级链 %s%n",
                routeName,
                route.getPrimary(),
                route.getFallbacks().isEmpty() ? "(无)" : String.join(" → ", route.getFallbacks()));
        if (route.getFallbacks().isEmpty()) {
            CliConsole.println("      提示：该路由没有降级链，上游故障时将直接失败（P1 会提供动态降权）");
        }
    }

    private void describeProvider(ProviderCatalogEntry entry) {
        var configured = parent.router().properties().getProviders().get(entry.providerId());
        String keyState =
                configured == null || configured.getApiKey() == null ? "未配置 apiKey" : "apiKey 已配置";
        String baseUrl =
                configured != null && configured.getBaseUrl() != null
                        ? configured.getBaseUrl()
                        : entry.defaultBaseUrl();
        CliConsole.printf(
                "  %-12s 协议=%-18s %s  baseUrl=%s 能力=%s%n",
                entry.alias(), entry.protocol(), keyState, baseUrl, entry.capabilities().names());
    }
}
