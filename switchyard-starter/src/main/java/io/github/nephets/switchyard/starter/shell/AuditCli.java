/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.shell;

import io.github.nephets.switchyard.core.audit.AuditEvent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import picocli.CommandLine;

/**
 * {@code switchyard audit}：按租户 / traceId 翻审计记录（PRD 的 {@code audit tail --tenant vip --since 2h}）。
 *
 * <p>支持两种输出：人类可读的表格（默认）与 JSON（{@code --format json}）。 后者是给 CI 用的 —— 比如「查过去 2 小时有没有 {@code
 * ESTIMATED} 计量超过阈值」这类断言。
 */
@CommandLine.Command(name = "audit", mixinStandardHelpOptions = true, description = "查看审计记录")
public class AuditCli implements Runnable {

    private final SwitchyardCli parent;

    @CommandLine.Option(
            names = {"-t", "--tenant"},
            description = "按租户过滤")
    private String tenant;

    @CommandLine.Option(
            names = {"--trace-id"},
            description = "按 traceId 精确检索")
    private String traceId;

    @CommandLine.Option(
            names = {"--since"},
            defaultValue = "2h",
            description = "只看最近这段时间内的记录，如 30m / 2h / 7d")
    private String since;

    @CommandLine.Option(
            names = {"--format"},
            defaultValue = "table",
            description = "输出格式：table / json")
    private String format;

    @CommandLine.Option(
            names = {"-n", "--limit"},
            defaultValue = "50",
            description = "最多输出多少条")
    private int limit;

    /**
     * 构造。
     *
     * @param parent 根命令
     */
    public AuditCli(SwitchyardCli parent) {
        this.parent = parent;
    }

    @Override
    public void run() {
        onCommand();
    }

    /** picocli 约定的命令入口。 */
    public void onCommand() {
        Instant threshold = parseSince(since);
        List<AuditEvent> events = parent.auditTrail().recent(tenant);

        List<AuditEvent> filtered =
                events.stream()
                        .filter(e -> traceId == null || traceId.equals(e.traceId()))
                        .filter(e -> e.occurredAt() == null || !e.occurredAt().isBefore(threshold))
                        .limit(Math.max(1, limit))
                        .toList();

        if ("json".equalsIgnoreCase(format)) {
            for (AuditEvent event : filtered) {
                System.out.println(toJson(event));
            }
            return;
        }

        System.out.printf(
                "%-34s %-10s %8s %8s %-17s %12s %s%n",
                "TRACED", "TENANT", "INPUT", "OUTPUT", "SOURCE", "COST(CNY)", "MODEL");
        System.out.println("-".repeat(120));
        for (AuditEvent event : filtered) {
            var usage = event.usage();
            System.out.printf(
                    "%-34s %-10s %8d %8d %-17s %12s %s%n",
                    event.traceId(),
                    event.tenantId() == null ? "-" : event.tenantId(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.usageSource(),
                    event.costAmount().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString(),
                    "-");
        }
        System.out.println();
        System.out.println(
                "提示：审计默认不落 prompt 原文（见 docs/configuration.md 的 llm.switchyard.audit.capture-payload）。");
        return;
    }

    private String toJson(AuditEvent event) {
        var usage = event.usage();
        return String.format(
                "{\"traceId\":\"%s\",\"spanId\":\"%s\",\"occurredAt\":\"%s\",\"tenantId\":%s,"
                        + "\"usage\":{\"input\":%d,\"output\":%d,\"reasoning\":%d,\"cached\":%d,\"total\":%d,\"source\":\"%s\",\"streamReported\":%s},"
                        + "\"cost\":{\"amount\":%s,\"currency\":\"%s\"},\"payloadDigest\":\"%s\",\"payloadFull\":%s}",
                event.traceId(),
                event.spanId(),
                event.occurredAt(),
                event.tenantId() == null ? "null" : "\"" + event.tenantId() + "\"",
                usage.inputTokens(),
                usage.outputTokens(),
                usage.reasoningTokens(),
                usage.cachedInputTokens(),
                usage.totalTokens(),
                usage.usageSource(),
                usage.streamUsageReported(),
                event.costAmount().toPlainString(),
                event.costCurrency(),
                event.payloadDigest(),
                event.payloadFull() == null ? "null" : "true");
    }

    /** 把 {@code 30m / 2h / 7d} 解析成时间点。 */
    public static Instant parseSince(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            long amount = Long.parseLong(value.substring(0, value.length() - 1).trim());
            char unit = value.charAt(value.length() - 1);
            Instant now = Instant.now();
            return switch (unit) {
                case 'm' -> now.minus(amount, ChronoUnit.MINUTES);
                case 'h' -> now.minus(amount, ChronoUnit.HOURS);
                case 'd' -> now.minus(amount, ChronoUnit.DAYS);
                default -> now.minus(amount, ChronoUnit.HOURS);
            };
        } catch (RuntimeException ex) {
            return Instant.EPOCH;
        }
    }
}
