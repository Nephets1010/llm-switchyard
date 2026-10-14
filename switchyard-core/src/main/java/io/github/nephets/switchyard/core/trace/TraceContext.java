/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.trace;

/**
 * W3C TraceContext（契约 CTX-001）。
 *
 * <p>三个仓库共享的唯一主键就是 {@code traceId}：①网关产生它，②Agent 编排继承它，③可观测按它聚合。 「一个 traceId 串起三仓」是这个家族的核心卖点之一，所以本
 * record 的字段名与顺序被 {@code contract/contract-v1.json} 逐字符锁定。
 *
 * <p>⚠️ <b>虚拟线程下的陷阱</b>：裸 {@code ThreadLocal} 在虚拟线程池里会丢上下文 （虚拟线程可能被挂起后迁移到别的载体线程执行）。因此 {@link
 * #current()} 内部用的是 {@link InheritableThreadLocal}，并且新建虚拟线程时必须显式继承 （见 {@code
 * InheritableThreadLocal} 在虚拟线程中的语义：{@code virtual-threads} 属性下它是 inheritable 的）。
 *
 * @param traceId 32 位小写 hex，全三仓唯一主键
 * @param spanId 16 位小写 hex
 * @param parentSpanId 父 span，根节点为 null
 * @param traceFlags {@code "01"} 表示 sampled，{@code "00"} 表示不采样
 * @param traceState 透传，不解析，可为 null
 */
public record TraceContext(
        String traceId, String spanId, String parentSpanId, String traceFlags, String traceState) {

    /** 不采样的标记值。 */
    public static final String FLAGS_NOT_SAMPLED = "00";

    /** 采样的标记值。 */
    public static final String FLAGS_SAMPLED = "01";

    /**
     * 虚拟线程安全的当前上下文持有者。
     *
     * <p>用 {@link InheritableThreadLocal} 而非 {@link ThreadLocal}：本项目明确支持虚拟线程 （{@code
     * spring.threads.virtual.enabled=true}），裸 ThreadLocal 会在 {@code Flux} 的调度切换与虚拟线程的载体切换处丢失
     * traceId，导致审计记录无法归链。
     */
    private static final InheritableThreadLocal<TraceContext> CURRENT =
            new InheritableThreadLocal<>();

    /**
     * 构造并校验 TraceContext。
     *
     * @throws IllegalArgumentException traceId / spanId 非小写 hex 或长度不符时
     */
    public TraceContext {
        traceId = requireHex(traceId, 32, "traceId");
        spanId = requireHex(spanId, 16, "spanId");
        parentSpanId =
                parentSpanId == null || parentSpanId.isEmpty()
                        ? null
                        : requireHex(parentSpanId, 16, "parentSpanId");
        traceFlags = (traceFlags == null || traceFlags.isEmpty()) ? FLAGS_SAMPLED : traceFlags;
        if (!FLAGS_SAMPLED.equals(traceFlags) && !FLAGS_NOT_SAMPLED.equals(traceFlags)) {
            throw new IllegalArgumentException("traceFlags 只能是 \"00\" 或 \"01\"，实际为：" + traceFlags);
        }
    }

    /** 新建一个根上下文，traceId 与 spanId 由随机源生成。 */
    public static TraceContext createRoot() {
        long[] ids = new long[3];
        W3CTraceCodec.randomize(ids);
        // traceId = 两个 64 位 id 拼接（共 32 位）；spanId = 一个 64 位 id（16 位）
        String traceId = W3CTraceCodec.toHex(ids[0]) + W3CTraceCodec.toHex(ids[1]);
        return new TraceContext(traceId, W3CTraceCodec.toHex(ids[2]), null, FLAGS_SAMPLED, null);
    }

    /** 新建一个根上下文，traceId 与 spanId 使用调用方提供的随机源（便于测试可复现）。 */
    public static TraceContext createRoot(java.util.random.RandomGenerator random) {
        long[] ids = new long[3];
        W3CTraceCodec.randomize(random, ids);
        String traceId = W3CTraceCodec.toHex(ids[0]) + W3CTraceCodec.toHex(ids[1]);
        return new TraceContext(traceId, W3CTraceCodec.toHex(ids[2]), null, FLAGS_SAMPLED, null);
    }

    /** 基于当前上下文派生子 span；无当前上下文时自动创建根上下文。 */
    public TraceContext newChild() {
        long[] ids = new long[1];
        W3CTraceCodec.randomize(ids);
        return new TraceContext(
                traceId, W3CTraceCodec.toHex(ids[0]), spanId, traceFlags, traceState);
    }

    /** 取当前线程上下文；不存在时返回 null。 */
    public static TraceContext currentOrNull() {
        return CURRENT.get();
    }

    /**
     * 取当前线程上下文；不存在时创建一个根上下文并绑定。
     *
     * <p>「不存在就造一个」而不是抛异常，是为了让业务代码在没有任何埋点的情况下 也能立刻拥有可用的 traceId —— 网关不应该要求用户先正确初始化才能工作。
     */
    public static TraceContext current() {
        TraceContext ctx = CURRENT.get();
        if (ctx == null) {
            ctx = createRoot();
            CURRENT.set(ctx);
        }
        return ctx;
    }

    /** 绑定到当前线程，返回原有的上下文（便于在 finally 里恢复）。 */
    public static TraceContext set(TraceContext ctx) {
        TraceContext previous = CURRENT.get();
        if (ctx == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(ctx);
        }
        return previous;
    }

    /** 清空当前线程的上下文，避免线程复用时把上一个请求的 traceId 带给下一个请求。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在给定上下文下执行一段逻辑，执行完自动恢复原上下文。
     *
     * <p>这是本项目推荐的 traceId 作用域写法：比 try/finally 手写 set/restore 更难用错。
     *
     * @param ctx 要绑定的上下文，可为 null（等价于清空）
     * @param action 要执行的逻辑
     * @param <T> 返回值类型
     * @return action 的返回值
     */
    public static <T> T with(TraceContext ctx, java.util.function.Supplier<T> action) {
        TraceContext previous = set(ctx);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    /** {@link #with(TraceContext, Supplier)} 的 void 版本。 */
    public static void with(TraceContext ctx, Runnable action) {
        with(
                ctx,
                () -> {
                    action.run();
                    return null;
                });
    }

    /** 是否被采样。 */
    public boolean sampled() {
        return FLAGS_SAMPLED.equals(traceFlags);
    }

    private static String requireHex(String value, int expectedLength, String field) {
        if (value == null || value.length() != expectedLength) {
            throw new IllegalArgumentException(
                    field + " 必须是 " + expectedLength + " 位小写 hex，实际为：" + value);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException(field + " 含非小写 hex 字符：" + value);
            }
        }
        return value;
    }
}
