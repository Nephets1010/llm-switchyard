/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.core.audit.AuditEvent;
import io.github.nephets.switchyard.core.audit.AuditSink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构化审计日志实现（默认）。
 *
 * <p><b>为什么审计走独立 logger 而不是业务 logger</b>：审计记录需要能被单独采集、 单独脱敏、单独留存到合规介质。混进业务日志的结果通常是「审计需求被降级成从业务日志里
 * grep」。
 *
 * <p><b>禁止落 Prompt 原文</b>（架构 §8.5）：只输出 {@code payloadDigest} 与长度。 这不是保守，而是因为金融/客服场景的 prompt
 * 里经常混着身份证号与手机号。
 */
public class LoggingAuditSinkAdapter implements AuditSink {

    private static final Logger AUDIT = LoggerFactory.getLogger("switchyard.audit");
    private static final Logger log = LoggerFactory.getLogger(LoggingAuditSinkAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "logging";
    }

    @Override
    public void write(AuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            // 一条一行 JSON，便于日志采集侧按行解析
            AUDIT.info(toJson(event));
        } catch (Exception ex) {
            // 审计是旁路：序列化失败也绝不能影响主链路
            log.debug("审计序列化失败，已忽略：traceId={}", event.traceId(), ex);
        }
    }

    /** 序列化为单行 JSON。 */
    String toJson(AuditEvent event) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"ts\":\"").append(event.occurredAt()).append('"');
        sb.append(",\"eventType\":\"").append(event.eventType()).append('"');
        sb.append(",\"traceId\":\"").append(event.traceId()).append('"');
        sb.append(",\"spanId\":\"").append(event.spanId()).append('"');
        appendIfPresent(sb, "tenantId", event.tenantId());
        appendIfPresent(sb, "bizLine", event.bizLine());
        appendIfPresent(sb, "feature", event.feature());
        appendIfPresent(sb, "promptVersion", event.promptVersion());

        var usage = event.usage();
        sb.append(",\"usage\":{")
                .append("\"input\":")
                .append(usage.inputTokens())
                .append(",\"output\":")
                .append(usage.outputTokens())
                .append(",\"reasoning\":")
                .append(usage.reasoningTokens())
                .append(",\"cached\":")
                .append(usage.cachedInputTokens())
                .append(",\"total\":")
                .append(usage.totalTokens())
                .append(",\"source\":\"")
                .append(usage.usageSource())
                .append('"')
                .append(",\"streamReported\":")
                .append(usage.streamUsageReported())
                .append('}');

        sb.append(",\"cost\":{\"amount\":")
                .append(event.costAmount())
                .append(",\"currency\":\"")
                .append(event.costCurrency())
                .append("\"}");
        sb.append(",\"payloadDigest\":\"").append(event.payloadDigest()).append('"');
        sb.append(",\"payloadFull\":").append(event.payloadFull() == null ? "null" : "true");
        sb.append('}');
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(",\"").append(key).append("\":\"").append(escape(value)).append('"');
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
