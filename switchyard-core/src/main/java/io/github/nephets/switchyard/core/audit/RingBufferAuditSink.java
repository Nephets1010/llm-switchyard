/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 环形缓冲审计实现：把最近 N 条事件留在内存里，供控制台 {@code /_switchyard/audit} 与 CLI 演示。
 *
 * <p><b>首版的诚实定位</b>：这不是合规存储。进程一重启就没了。 它的价值是让「审计确实在记录、字段确实齐全」这件事<b>可被当场验证</b> —— README
 * 里那些「结构化审计字段」的能力，读者可以启动 demo 后自己看一眼。 真正的合规留存需要用户接自己的 AuditSink 实现（写 ClickHouse / 送 Kafka / 推
 * OTLP）。
 *
 * <p>用环形数组而不是 {@code ArrayList} + 定期清理：后者在高频写入下会因为 {@code remove(0)} 的数组搬移而产生 O(n)
 * 开销，而审计是<b>每次模型调用一次</b>的热路径。
 */
public class RingBufferAuditSink implements AuditSink {

    /** 默认容量。 */
    public static final int DEFAULT_CAPACITY = 1000;

    private final Object lock = new Object();
    private final AuditEvent[] buffer;
    private final int capacity;
    private int writeIndex;
    private long totalWritten;

    /** 用默认容量构造。 */
    public RingBufferAuditSink() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 构造。
     *
     * @param capacity 容量；小于 1 时按 1 处理
     */
    public RingBufferAuditSink(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.buffer = new AuditEvent[this.capacity];
    }

    @Override
    public void write(AuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            synchronized (lock) {
                buffer[writeIndex] = event;
                writeIndex = (writeIndex + 1) % capacity;
                totalWritten++;
            }
        } catch (RuntimeException ex) {
            // 审计是旁路：吞掉异常，绝不影响主链路
        }
    }

    @Override
    public String name() {
        return "ring-buffer";
    }

    /**
     * 读取当前保留的事件，按写入顺序。
     *
     * @return 事件列表（不可变）
     */
    public List<AuditEvent> events() {
        synchronized (lock) {
            int size = (int) Math.min(totalWritten, capacity);
            List<AuditEvent> result = new ArrayList<>(size);
            int start = (writeIndex - size + capacity) % capacity;
            for (int i = 0; i < size; i++) {
                result.add(buffer[(start + i) % capacity]);
            }
            return List.copyOf(result);
        }
    }

    /** 按 traceId 检索（PRD P0-7：「可按 traceId / 租户 / 时间范围检索」）。 */
    public List<AuditEvent> findByTraceId(String traceId) {
        return events().stream()
                .filter(e -> traceId != null && traceId.equals(e.traceId()))
                .toList();
    }

    /** 按租户检索。 */
    public List<AuditEvent> findByTenant(String tenantId) {
        return events().stream()
                .filter(e -> tenantId != null && tenantId.equals(e.tenantId()))
                .toList();
    }

    /** 累计写入条数（可能大于保留容量）。 */
    public long totalWritten() {
        synchronized (lock) {
            return totalWritten;
        }
    }

    /** 清空。 */
    public void clear() {
        synchronized (lock) {
            java.util.Arrays.fill(buffer, null);
            writeIndex = 0;
            totalWritten = 0;
        }
    }
}
