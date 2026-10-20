/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.shell;

import io.github.nephets.switchyard.core.pricing.PricingTable;
import io.github.nephets.switchyard.core.quota.QuotaKey;
import io.github.nephets.switchyard.core.quota.QuotaSnapshot;
import io.github.nephets.switchyard.starter.SwitchyardRouter;

import picocli.CommandLine;

/**
 * CLI 根命令。
 *
 * <p><b>为什么要有 CLI</b>：PRD 把它列为「调试主力，CI 友好」。 排查生产问题时，最快的路径往往是一条能直接拷进终端的命令 —— 比翻日志、比登录跳板机查数据库都快一个数量级。
 *
 * <p>三个子命令对应三类日常问题：钱（{@code quota}）、发生了什么（{@code audit}）、 能不能用（{@code doctor}）。
 */
@CommandLine.Command(
        name = "switchyard",
        mixinStandardHelpOptions = true,
        version = "llm-switchyard 0.1.0",
        description = "LLM 道岔运维命令行：查配额、翻审计、跑体检",
        subcommands = {QuotaCli.class, AuditCli.class, DoctorCli.class})
public class SwitchyardCli implements Runnable {

    private final SwitchyardRouter router;
    private final PricingTable pricingTable;
    private final AuditTrailSource auditTrail;
    private final io.github.nephets.switchyard.core.quota.QuotaStore quotaStore;

    /**
     * 构造。
     *
     * @param router 门面
     * @param pricingTable 价格表
     * @param auditTrail 审计读取源
     * @param quotaStore 配额存储，供 {@code quota set} 写入策略
     */
    public SwitchyardCli(
            SwitchyardRouter router,
            PricingTable pricingTable,
            AuditTrailSource auditTrail,
            io.github.nephets.switchyard.core.quota.QuotaStore quotaStore) {
        this.router = router;
        this.pricingTable = pricingTable;
        this.auditTrail = auditTrail;
        this.quotaStore = quotaStore;
    }

    /** 配额存储访问器（供子命令设置配额用）。 */
    io.github.nephets.switchyard.core.quota.QuotaStore quotaStore() {
        return quotaStore;
    }

    @Override
    public void run() {
        onCommand();
    }

    /**
     * picocli 约定的命令入口：根命令不带子命令时打印用法。
     *
     * <p>输出走 {@link CliConsole}，与其余子命令共用同一个出口。 为什么不能缓存流，见 {@link CliConsole#out()}。
     */
    public void onCommand() {
        CommandLine.usage(this, CliConsole.out());
    }

    /**
     * 运行 CLI。
     *
     * @param args 参数
     * @param router 门面
     * @param pricingTable 价格表
     * @param auditTrail 审计读取源
     */
    public static void run(
            String[] args,
            SwitchyardRouter router,
            PricingTable pricingTable,
            AuditTrailSource auditTrail,
            io.github.nephets.switchyard.core.quota.QuotaStore quotaStore) {
        System.exit(
                new CommandLine(new SwitchyardCli(router, pricingTable, auditTrail, quotaStore))
                        .execute(args));
    }

    /** 审计读取源（解耦具体的 AuditSink 实现）。 */
    @FunctionalInterface
    public interface AuditTrailSource {
        /**
         * 读取审计记录。
         *
         * @param tenantId 租户；为空表示不限
         * @return 审计事件列表
         */
        java.util.List<io.github.nephets.switchyard.core.audit.AuditEvent> recent(String tenantId);
    }

    /** 门面访问器（供子命令使用）。 */
    SwitchyardRouter router() {
        return router;
    }

    /** 价格表访问器（供子命令使用）。 */
    PricingTable pricingTable() {
        return pricingTable;
    }

    /** 审计源访问器（供子命令使用）。 */
    AuditTrailSource auditTrail() {
        return auditTrail;
    }

    /** 便捷的配额查询。 */
    QuotaSnapshot snapshotOf(QuotaKey key) {
        return router.quotaOf(key);
    }
}
