/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.shell;

import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaSnapshot;

import picocli.CommandLine;

/**
 * {@code switchyard quota}：查看与设置配额（PRD UI 图里的 {@code quota set --tenant vip --model ...}）。
 *
 * <p>输出刻意用<b>人类可读的对齐表格</b>而不是 JSON：这条命令的使用场景是 「人在终端里看数字」，JSON 是给机器的。
 */
@CommandLine.Command(
        name = "quota",
        mixinStandardHelpOptions = true,
        description = "查看配额使用情况",
        subcommands = QuotaCli.SetCommand.class)
public class QuotaCli implements Runnable {

    private final SwitchyardCli parent;

    @CommandLine.Option(
            names = {"-t", "--tenant"},
            description = "租户标识，默认取配置里的默认租户")
    private String tenant;

    @CommandLine.Option(
            names = {"-m", "--model"},
            description = "模型，形如 openai/gpt-5-mini")
    private String model;

    /** picocli 用的无参构造：子命令必须可被反射实例化。 */
    public QuotaCli() {
        this(null);
    }

    /**
     * 构造。
     *
     * @param parent 根命令
     */
    public QuotaCli(SwitchyardCli parent) {
        this.parent = parent;
    }

    @Override
    public void run() {
        onCommand();
    }

    /** picocli 约定的命令入口。 */
    public void onCommand() {
        String effectiveTenant =
                tenant == null || tenant.isBlank()
                        ? parent.router().properties().getQuota().getDefaultTenant()
                        : tenant;

        if (model == null || model.isBlank()) {
            // 未指定模型时打印所有已配置路由的主模型配额
            System.out.printf(
                    "%-12s %-34s %14s %14s %14s %8s%n",
                    "TENANT", "MODEL", "USED", "LIMIT", "REMAINING", "UTIL%");
            System.out.println("-".repeat(102));
            parent.router()
                    .routeNames()
                    .forEach(
                            route -> {
                                var routeConfig =
                                        parent.router().properties().getRoutes().get(route);
                                if (routeConfig == null || routeConfig.getPrimary() == null) {
                                    return;
                                }
                                var ref =
                                        io.github.nephets.switchyard.core.model.ModelRef.parse(
                                                routeConfig.getPrimary());
                                QuotaKey key =
                                        new QuotaKey(
                                                effectiveTenant,
                                                null,
                                                ref.providerId(),
                                                ref.modelId());
                                printRow(effectiveTenant, ref.text(), parent.snapshotOf(key));
                            });
            return;
        }

        var ref = io.github.nephets.switchyard.core.model.ModelRef.parse(model);
        QuotaKey key = new QuotaKey(effectiveTenant, null, ref.providerId(), ref.modelId());
        printRow(effectiveTenant, ref.text(), parent.snapshotOf(key));
        return;
    }

    private void printRow(String tenantText, String modelText, QuotaSnapshot snapshot) {
        String limit = snapshot.unlimited() ? "unlimited" : String.valueOf(snapshot.limit());
        String remaining =
                snapshot.unlimited() ? "unlimited" : String.valueOf(snapshot.remaining());
        System.out.printf(
                "%-12s %-34s %14s %14s %14s %7.1f%%%n",
                tenantText,
                modelText,
                snapshot.used(),
                limit,
                remaining,
                snapshot.utilization() * 100);
    }

    /** {@code switchyard quota set}：设置配额上限。 */
    @CommandLine.Command(name = "set", mixinStandardHelpOptions = true, description = "设置配额上限")
    public static class SetCommand implements Runnable {

        private final SwitchyardCli parent;

        @CommandLine.Option(
                names = {"-t", "--tenant"},
                description = "租户标识")
        private String tenant;

        @CommandLine.Option(
                names = {"-m", "--model"},
                required = true,
                description = "模型，形如 openai/gpt-5-mini")
        private String model;

        @CommandLine.Option(
                names = {"-l", "--limit"},
                required = true,
                description = "token 上限；0 表示不限额")
        private long limit;

        @CommandLine.Option(
                names = {"-p", "--period"},
                defaultValue = "MONTHLY",
                description = "周期：MINUTE / HOURLY / DAILY / MONTHLY")
        private String period;

        /** picocli 用的无参构造：子命令必须可被反射实例化。 */
        public SetCommand() {
            this(null);
        }

        /**
         * 构造。
         *
         * @param parent 根命令
         */
        public SetCommand(SwitchyardCli parent) {
            this.parent = parent;
        }

        @Override
        public void run() {
            onCommand();
        }

        /** picocli 约定的命令入口。 */
        public void onCommand() {
            String effectiveTenant =
                    tenant == null || tenant.isBlank()
                            ? parent.router().properties().getQuota().getDefaultTenant()
                            : tenant;
            var ref = io.github.nephets.switchyard.core.model.ModelRef.parse(model);
            QuotaKey key = new QuotaKey(effectiveTenant, null, ref.providerId(), ref.modelId());

            var quotaStore = parent.quotaStore();
            if (quotaStore == null) {
                System.err.println("配额存储未装配，无法设置配额");
                return;
            }
            var periodValue =
                    io.github.nephets.switchyard.core.quota.QuotaPolicy.Period.valueOf(
                            period.toUpperCase(java.util.Locale.ROOT));
            var behavior = parent.router().properties().getQuota().getOnExceeded();
            quotaStore.putPolicy(
                    key,
                    new io.github.nephets.switchyard.core.quota.QuotaPolicy(
                            periodValue,
                            limit,
                            "TRIP_BREAKER".equals(behavior.name())
                                    ? io.github.nephets.switchyard.core.quota.OverLimitBehavior
                                            .TRIP_BREAKER
                                    : io.github.nephets.switchyard.core.quota.OverLimitBehavior
                                            .FAIL_SOFT));

            System.out.printf("已设置配额：%s 上限=%d 周期=%s%n", key.text(), limit, periodValue);
            printRow(effectiveTenant, ref.text(), parent.snapshotOf(key));
            return;
        }

        private void printRow(String tenantText, String modelText, QuotaSnapshot snapshot) {
            System.out.printf(
                    "%-12s %-34s %14s %14s %14s %7.1f%%%n",
                    tenantText,
                    modelText,
                    snapshot.used(),
                    snapshot.unlimited() ? "unlimited" : String.valueOf(snapshot.limit()),
                    snapshot.unlimited() ? "unlimited" : String.valueOf(snapshot.remaining()),
                    snapshot.utilization() * 100);
        }
    }
}
