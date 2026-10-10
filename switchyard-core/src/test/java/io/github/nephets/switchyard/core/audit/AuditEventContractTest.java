/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.audit;

import io.github.nephets.switchyard.core.pricing.PriceSource;
import io.github.nephets.switchyard.core.trace.W3CTraceCodec;
import io.github.nephets.switchyard.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("审计事件与审计出口（EVT-001）")
class AuditEventContractTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    @DisplayName("★ 默认不落 prompt 原文，只保留摘要（PII 泄露防护）")
    void payloadFullIsNullByDefault() {
        String prompt = "客户身份证号 110101199003071234，请帮我核对";
        AuditEvent event =
                AuditEvent.modelCall(
                        TRACE_ID,
                        SPAN_ID,
                        "vip",
                        "customer-service",
                        "id-verify",
                        Usage.of(100L, 50L),
                        new BigDecimal("0.0012"),
                        prompt);

        assertThat(event.payloadFull()).as("默认必须为 null —— 金融/客服场景的 prompt 里常有身份证与手机号").isNull();
        assertThat(event.payloadDigest())
                .startsWith("sha256:")
                .doesNotContain("110101199003071234");
        assertThat(event.payloadDigest()).isEqualTo(W3CTraceCodec.digest(prompt));
    }

    @Test
    @DisplayName("摘要可用于验证脱敏逻辑生效（同样内容→同样摘要）")
    void digestVerifiesRedaction() {
        String original = "手机号 13800138000";
        String redacted = original.replaceAll("\\d{11}", "138****8000");

        assertThat(AuditEvent.digestOf(original)).isNotEqualTo(AuditEvent.digestOf(redacted));
        assertThat(AuditEvent.digestOf(original)).isEqualTo(AuditEvent.digestOf(original));
    }

    @Test
    @DisplayName("usage 不可为 null：缺失时自动填零值对象而不是 null")
    void usageNeverNull() {
        AuditEvent event =
                new AuditEvent(
                        AuditEventType.TOOL_CALL,
                        TRACE_ID,
                        SPAN_ID,
                        Instant.now(),
                        "vip",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "sha256:" + "0".repeat(64),
                        null);

        assertThat(event.usage()).as("契约要求 usage 不可为 null").isNotNull();
        assertThat(event.usage().totalTokens()).isZero();
        assertThat(event.costAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(event.costCurrency()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("payloadDigest 缺失或格式不对直接拒绝（审计必须能证明 payload 未被篡改）")
    void digestIsRequired() {
        assertThatThrownBy(
                        () ->
                                new AuditEvent(
                                        AuditEventType.MODEL_CALL,
                                        TRACE_ID,
                                        SPAN_ID,
                                        Instant.now(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Usage.of(1L, 1L),
                                        BigDecimal.ONE,
                                        "CNY",
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadDigest");

        assertThatThrownBy(
                        () ->
                                new AuditEvent(
                                        AuditEventType.MODEL_CALL,
                                        TRACE_ID,
                                        SPAN_ID,
                                        Instant.now(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Usage.of(1L, 1L),
                                        BigDecimal.ONE,
                                        "CNY",
                                        "md5:abc",
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256:");
    }

    @Test
    @DisplayName("eventType 不可为 null")
    void eventTypeRequired() {
        assertThatThrownBy(
                        () ->
                                new AuditEvent(
                                        null,
                                        TRACE_ID,
                                        SPAN_ID,
                                        Instant.now(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Usage.of(1L, 1L),
                                        BigDecimal.ONE,
                                        "CNY",
                                        "sha256:" + "0".repeat(64),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("★ 缺失维度保持为 null，不得填 unknown 占位（否则污染归因统计）")
    void missingDimensionsStayNull() {
        AuditEvent event =
                AuditEvent.modelCall(
                        TRACE_ID,
                        SPAN_ID,
                        "vip",
                        null,
                        null,
                        Usage.of(10L, 10L),
                        BigDecimal.ONE,
                        "x");

        assertThat(event.bizLine()).isNull();
        assertThat(event.feature()).isNull();
        assertThat(event.agentId()).isNull();
        assertThat(event.stepSeq()).isNull();
    }

    @Test
    @DisplayName("显式开启留痕后可携带 payloadFull（调用方需自行完成脱敏）")
    void payloadFullCanBeEnabled() {
        String redacted = "手机号 138****8000";
        AuditEvent event =
                new AuditEvent(
                        AuditEventType.MODEL_CALL,
                        TRACE_ID,
                        SPAN_ID,
                        Instant.now(),
                        "vip",
                        "customer-service",
                        "verify",
                        "v3",
                        null,
                        null,
                        null,
                        null,
                        Usage.of(10L, 10L),
                        new BigDecimal("0.001"),
                        "CNY",
                        AuditEvent.digestOf(redacted),
                        redacted);

        assertThat(event.payloadFull()).isEqualTo(redacted);
        assertThat(event.promptVersion()).isEqualTo("v3");
    }

    @Test
    @DisplayName("契约辅助方法与契约文件一致")
    void contractHelpers() {
        assertThat(AuditEvent.currency()).isEqualTo("CNY");
        assertThat(AuditEvent.typeNames())
                .containsExactly("MODEL_CALL", "TOOL_CALL", "RETRIEVAL", "APPROVAL", "FLOW_STEP");
        assertThat(AuditEvent.priceSourceName(null)).isEqualTo("ESTIMATED");
        assertThat(AuditEvent.priceSourceName(PriceSource.OFFICIAL)).isEqualTo("OFFICIAL");
        assertThat(AuditEvent.verifyDigestAlgorithm("x")).startsWith("sha256:");
        assertThat(AuditEvent.digestOf(null)).isNull();
    }

    @Test
    @DisplayName("★ 环形缓冲审计：按 traceId / 租户可检索（PRD P0-7）")
    void ringBufferRetrieval() {
        RingBufferAuditSink sink = new RingBufferAuditSink(10);
        sink.write(
                AuditEvent.modelCall(
                        TRACE_ID,
                        SPAN_ID,
                        "vip",
                        null,
                        null,
                        Usage.of(1L, 1L),
                        BigDecimal.ONE,
                        "a"));
        sink.write(
                AuditEvent.modelCall(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        SPAN_ID,
                        "free",
                        null,
                        null,
                        Usage.of(1L, 1L),
                        BigDecimal.ONE,
                        "b"));
        sink.write(null);

        assertThat(sink.name()).isEqualTo("ring-buffer");
        assertThat(sink.findByTraceId(TRACE_ID)).hasSize(1);
        assertThat(sink.findByTenant("vip")).hasSize(1);
        assertThat(sink.findByTenant("nobody")).isEmpty();
        assertThat(sink.events()).hasSize(2);
        assertThat(sink.totalWritten()).isEqualTo(2);
    }

    @Test
    @DisplayName("环形缓冲超出容量时覆盖最旧的记录")
    void ringBufferOverwrites() {
        RingBufferAuditSink sink = new RingBufferAuditSink(3);
        for (int i = 0; i < 10; i++) {
            sink.write(
                    AuditEvent.modelCall(
                            String.format("%032x", i),
                            SPAN_ID,
                            "vip",
                            null,
                            null,
                            Usage.of(i, i),
                            BigDecimal.ONE,
                            "p" + i));
        }

        assertThat(sink.events()).hasSize(3);
        assertThat(sink.totalWritten()).isEqualTo(10L);
        // 保留的是最后 3 条
        assertThat(sink.events().get(2).traceId()).isEqualTo(String.format("%032x", 9));

        sink.clear();
        assertThat(sink.events()).isEmpty();
        assertThat(sink.totalWritten()).isZero();
    }

    @Test
    @DisplayName("容量为 0 或负数时按 1 处理，不抛异常")
    void ringBufferCapacityFloor() {
        RingBufferAuditSink sink = new RingBufferAuditSink(0);
        sink.write(
                AuditEvent.modelCall(
                        TRACE_ID,
                        SPAN_ID,
                        "vip",
                        null,
                        null,
                        Usage.of(1L, 1L),
                        BigDecimal.ONE,
                        "a"));

        assertThat(sink.events()).hasSize(1);
    }

    @Test
    @DisplayName("★ 审计写入失败被实现方兜住，绝不影响主链路（SPI 契约）")
    void auditFailureIsSwallowed() {
        // 默认实现必须自己吞掉异常：审计是旁路，它挂了不能把用户的 LLM 调用一起带挂
        RingBufferAuditSink sink =
                new RingBufferAuditSink(4) {
                    @Override
                    public void write(AuditEvent event) {
                        // 模拟「审计后端已挂」，默认实现应捕获并继续
                        super.write(
                                new AuditEvent(
                                        AuditEventType.MODEL_CALL,
                                        TRACE_ID,
                                        SPAN_ID,
                                        Instant.now(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Usage.of(1L, 1L),
                                        BigDecimal.ONE,
                                        "CNY",
                                        "sha256:" + "0".repeat(64),
                                        null));
                    }
                };

        sink.write(null);

        assertThat(sink.events()).hasSize(1);
        assertThat(sink.name()).isEqualTo("ring-buffer");
    }
}
