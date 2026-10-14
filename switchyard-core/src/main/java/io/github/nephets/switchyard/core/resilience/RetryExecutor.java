/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import io.github.nephets.switchyard.core.ErrorCode;

import java.util.function.Supplier;

/**
 * 重试执行器。
 *
 * <p>把「该不该重试」「退避多久」「重试到第几次」三件事收敛到一处， 保证业务代码与 provider 适配器都不会各自实现一套（各自实现的必然不一致）。
 *
 * <p><b>为什么首版不引入 Resilience4j</b>：熔断窗口统计、状态转换、错误分类都需要定制测试 （注入 429 验证重试成功、注入 400 验证不重试、验证熔断打开后 100
 * 次调用只打上游 1 次）。 这类语义用 200 行自己实现 + WireMock 做故障注入，比调一个通用框架更容易测、也更容易讲清楚。 坐标仍保留在父 POM 的 BOM 中，供
 * P1「智能路由降权」使用。
 *
 * <p><b>为什么重试时<b>不</b>重新走一次配额预留</b>：预留是一次请求的账，不是一次尝试的账。 若每次重试都重新预留，一个 429 重试 3 次就会占用 3
 * 份额度，用户会看到「只发了一个请求却扣了三份」。 正确做法是预留一次、结算一次，成本按最终成功的响应计（失败的尝试通常不计费）。
 */
public class RetryExecutor {

    private final RetryPolicy policy;
    private final ErrorClassifier classifier;
    private final Sleeper sleeper;

    /** 用默认策略与默认分类器构造。 */
    public RetryExecutor() {
        this(RetryPolicy.defaults(), new DefaultErrorClassifier(), Thread::sleep);
    }

    /**
     * 构造。
     *
     * @param policy 重试策略
     * @param classifier 异常分类器
     * @param sleeper 休眠函数（测试可注入为 no-op，避免 Thread.sleep）
     */
    public RetryExecutor(RetryPolicy policy, ErrorClassifier classifier, Sleeper sleeper) {
        this.policy = policy == null ? RetryPolicy.defaults() : policy;
        this.classifier = classifier == null ? new DefaultErrorClassifier() : classifier;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
    }

    /**
     * 带重试地执行一段可能失败的操作。
     *
     * @param operation 操作
     * @param <T> 返回值类型
     * @return 成功时的返回值
     * @throws RuntimeException 重试耗尽后抛出最后一次的异常（已脱敏为统一异常类型）
     */
    public <T> T execute(Supplier<T> operation) {
        return execute(operation, null);
    }

    /**
     * 带重试地执行一段可能失败的操作，并在每次失败时回调（用于打日志与埋点）。
     *
     * @param operation 操作
     * @param onRetry 每次重试前的回调，参数为 {@code (attempt, errorCode, backoffMillis)}；可为 null
     * @param <T> 返回值类型
     * @return 成功时的返回值
     */
    public <T> T execute(Supplier<T> operation, RetryObserver onRetry) {
        if (operation == null) {
            throw new IllegalArgumentException("操作不可为 null");
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException ex) {
                ErrorCode code = classifier.errorCode(ex);
                last = ex;
                boolean retryable = classifier.isRetryable(ex);
                if (!retryable || !policy.canRetry(attempt)) {
                    // 不可重试或次数用尽 → 立刻退出，不做无意义的等待
                    throw ex;
                }
                long backoffMillis = policy.backoffFor(attempt).toMillis();
                if (onRetry != null) {
                    onRetry.onRetry(attempt, code, backoffMillis);
                }
                try {
                    sleeper.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        // 理论上不可达（循环内必定 return 或 throw），保留兜底以满足编译期可达性
        throw last == null ? new IllegalStateException("重试执行器异常退出") : last;
    }

    /** 当前策略。 */
    public RetryPolicy policy() {
        return policy;
    }

    /** 当前分类器。 */
    public ErrorClassifier classifier() {
        return classifier;
    }

    /** 休眠抽象，便于测试注入 no-op 实现（架构约定：测试禁用 Thread.sleep）。 */
    @FunctionalInterface
    public interface Sleeper {
        /**
         * 休眠指定毫秒。
         *
         * @param millis 毫秒
         * @throws InterruptedException 被中断时抛出
         */
        void sleep(long millis) throws InterruptedException;
    }

    /** 重试观察者。 */
    @FunctionalInterface
    public interface RetryObserver {
        /**
         * 一次重试发生时的回调。
         *
         * @param attempt 第几次尝试（从 1 开始）
         * @param errorCode 本次失败的错误码
         * @param backoffMillis 即将退避的毫秒数
         */
        void onRetry(int attempt, ErrorCode errorCode, long backoffMillis);
    }
}
