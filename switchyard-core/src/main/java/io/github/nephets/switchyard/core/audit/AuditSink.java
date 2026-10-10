/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.audit;

/**
 * 审计出口（SPI，契约 EVT-001）。
 *
 * <p>首版只定义接口与一个内存实现，把「完整实现」明确推到 P1 —— 但<b>接口首版就建</b>，不留返工：一旦业务方开始集成，就会依赖这个接口的形状， P1 再改签名就是破坏性变更。
 *
 * <p>实现要求：
 *
 * <ul>
 *   <li><b>不得抛异常</b>：审计失败绝不能影响主链路。默认实现吞掉异常并计数；
 *   <li><b>不得阻塞</b>：同步实现必须是纯内存操作，耗时 I/O 应由实现方自行异步化。
 * </ul>
 */
public interface AuditSink {

    /**
     * 写出一条审计记录。
     *
     * <p>本方法<b>不允许抛异常</b>。实现必须自行兜住所有异常 —— 审计是旁路， 它挂了不能把用户的 LLM 调用一起带挂。
     *
     * @param event 审计事件
     */
    void write(AuditEvent event);

    /**
     * 实现标识，写入日志便于排查「审计到底写到哪去了」。
     *
     * @return sink 名
     */
    String name();
}
