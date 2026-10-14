/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 环形缓冲审计实现：把最近 N 条事件留在内存里，供控制台 {@code /_switchyard/audit} 与 CLI 演示。
 *
 * <p><b>首版的诚实定位</b>：这不是合规存储。进程一重启就没了。 它的价值是让「审计确实在记录、字段确实齐全」这件事<b>可被当场验证</b> —— README
 * 里那些「结构化审计字段」的能力，读者可以启动 demo 后自己看一眼。 真正的合规留存需要用户接自己的 AuditSink 实现（写 ClickHouse / 送 Kafka / 推
 * OTLP）。
 *
 * <p>用环形数组而不是 {@code ArrayList} + 定期清理：后者在高频写入下会因为 {@code remove(0)} 的数组搬移而产生 O(n)
 * 开销，而审计是<b>每次模型调用一次</b>的热路径。
 *
 * <p><b>写失败必须可见</b>：审计是旁路，写失败绝不能影响主链路（这条既定行为不变）， 但「吞掉」不等于「看不见」—— 一个空 catch
 * 会让审计后端的故障在运维侧完全隐身，表现为「审计一直在跑，记录却越来越少」， 等到要拿审计对账时才发现缺了一大段。因此写失败会累加 {@link #droppedCount()} 并打一条
 * WARNING。
 */
public class RingBufferAuditSink implements AuditSink {

    /** 默认容量。 */
    public static final int DEFAULT_CAPACITY = 1000;

    /**
     * core 模块刻意<b>不依赖任何日志框架</b>（守住「零第三方依赖」这条底线）， 所以这里用 JDK 自带的 {@link java.util.logging.Logger}：在
     * Spring Boot 应用里 JUL 会被桥接到统一日志， 在纯 Java 环境下照常输出到 stderr —— 两种场景都不会静默。
     */
    private static final Logger log = Logger.getLogger(RingBufferAuditSink.class.getName());

    /** 写入失败被丢弃的条数（故障计数，clear() 不重置）。 */
    private final AtomicLong droppedCount = new AtomicLong();

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
            // 审计是旁路：吞掉异常，绝不影响主链路（这一条既定行为不变）。
            // 但必须留痕：计数 + 告警，否则「审计后端挂了」和「审计后端很闲」在运维看来一模一样。
            long dropped = droppedCount.incrementAndGet();
            log.log(
                    Level.WARNING,
                    "审计事件写入失败，已丢弃（累计丢弃 " + dropped + " 条）。审计为旁路，不影响主链路，但这段时间的审计记录是不完整的。",
                    ex);
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

    /**
     * 累计丢弃条数：写入失败的次数。
     *
     * <p>与 {@link #totalWritten()} 配套使用即可算出审计的「丢表率」，这是判断审计是否可信的唯一依据 —— 没有它，{@code events().size()}
     * 少了一半也没人知道是缓冲满了还是写入在报错。
     *
     * <p>刻意<b>不</b>被 {@link #clear()} 重置：它是故障计数，不是业务数据。
     *
     * @return 丢弃条数
     */
    public long droppedCount() {
        return droppedCount.get();
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
