/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.audit;

import io.github.nephets.switchyard.core.pricing.PriceSource;
import io.github.nephets.switchyard.core.trace.W3CTraceCodec;
import io.github.nephets.switchyard.core.usage.Usage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * 审计事件（契约 EVT-001）—— 三仓共用的同一份 schema。
 *
 * <p><b>为什么审计要独立于业务日志</b>：业务日志会被业务方随意改级别、随手清理； 审计记录则要能被单独采集、单独脱敏、单独留存到合规介质上。混在一起的结果通常是审计需求
 * 被降级成「从业务日志里 grep」，然后 grep 不出来。
 *
 * <p><b>默认不落 Prompt 原文</b>（PRD P0-7 的「Prompt 原文支持脱敏开关，默认不落全量原文」）： {@link #payloadFull()} 默认
 * null，只保留 {@link #payloadDigest()}。 金融、医疗、客服场景的 prompt 里经常混着身份证号与手机号，落全量原文等于制造一个 PII 泄露点。
 * 需要留痕的用户显式开启 + 通过脱敏后才会填充。
 *
 * <p>⚠️ <b>缺失维度必须为 null，禁止填 {@code "unknown"}</b>（契约硬约束）： 用占位串会让归因统计把「没采集」误判成「叫 unknown
 * 的那个租户」，报表直接失真。
 */
public record AuditEvent(
        AuditEventType eventType,
        String traceId,
        String spanId,
        Instant occurredAt,
        String tenantId,
        String bizLine,
        String feature,
        String promptVersion,
        String agentId,
        String flowInstanceId,
        String stepId,
        Integer stepSeq,
        Usage usage,
        BigDecimal costAmount,
        String costCurrency,
        String payloadDigest,
        String payloadFull) {

    /** 紧凑构造器：填充默认值并做不变量校验。 */
    public AuditEvent {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType 不可为 null");
        }
        if (usage == null) {
            // 契约要求 usage 不可为 null：没有 token 的事件填 Usage.estimated()
            usage =
                    Usage.estimated(
                            io.github.nephets.switchyard.core.usage.UsageSource.ESTIMATED, false);
        }
        if (costAmount == null) {
            costAmount = BigDecimal.ZERO;
        }
        if (costCurrency == null || costCurrency.isBlank()) {
            costCurrency = "CNY";
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (payloadDigest == null) {
            throw new IllegalArgumentException("payloadDigest 不可为 null：审计必须能证明 payload 未被篡改");
        }
        if (!payloadDigest.startsWith("sha256:")) {
            throw new IllegalArgumentException(
                    "payloadDigest 必须形如 sha256:<hex>，实际为：" + payloadDigest);
        }
    }

    /**
     * 便捷工厂：一次模型调用。
     *
     * @param traceId traceId
     * @param spanId spanId
     * @param tenantId 租户
     * @param bizLine 业务线
     * @param feature 功能
     * @param usage 用量
     * @param cost 成本
     * @param payload 渲染后的 payload，用于算摘要（<b>不会被落进事件</b>）
     * @return AuditEvent
     */
    public static AuditEvent modelCall(
            String traceId,
            String spanId,
            String tenantId,
            String bizLine,
            String feature,
            Usage usage,
            BigDecimal cost,
            String payload) {
        return new AuditEvent(
                AuditEventType.MODEL_CALL,
                traceId,
                spanId,
                Instant.now(),
                tenantId,
                bizLine,
                feature,
                null,
                null,
                null,
                null,
                null,
                usage,
                cost,
                "CNY",
                W3CTraceCodec.digest(payload),
                null);
    }

    /** 计算 payload 摘要。 */
    public static String digestOf(String payload) {
        return W3CTraceCodec.digest(payload);
    }

    /**
     * 契约测试用：核对 eventType 枚举值集合。
     *
     * @return 全部事件类型名
     */
    public static java.util.List<String> typeNames() {
        return java.util.Arrays.stream(AuditEventType.values()).map(Enum::name).toList();
    }

    /** 契约测试用：核对成本币种常量。 */
    public static String currency() {
        return "CNY";
    }

    /**
     * 契约测试用：把 payload 摘要算出来，用于验证 {@code sha256:} 前缀与算法一致性。
     *
     * @param payload 载荷
     * @return 摘要
     */
    public static String verifyDigestAlgorithm(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash =
                    md.digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + W3CTraceCodec.toHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 契约测试用：核对价格来源枚举名（span 属性 {@code swy.price.source}）。 */
    public static String priceSourceName(PriceSource source) {
        return source == null ? PriceSource.ESTIMATED.name() : source.name();
    }
}
