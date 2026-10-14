/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import io.github.nephets.switchyard.core.ErrorCode;

/**
 * 异常分类器（SPI）：判断「这个错误该不该重试」。
 *
 * <p><b>这是本项目与「无脑重试」网关最重要的分界线。</b>
 *
 * <p>常见的错误做法是「凡是非 2xx 就重试三次」。后果是：参数写错（400）时， 网关会对着一个永远会失败的请求重试 3 次，每次还都要消耗一次上游的限流额度 ——
 * 不仅救不回来，还把本可用于真实流量的配额烧掉了。
 *
 * <p>本项目的规则直接编码在 {@link ErrorCode#retryable()} 里：
 *
 * <table border="1">
 *   <caption>可重试性判定</caption>
 *   <tr><th>场景</th><th>可重试</th><th>理由</th></tr>
 *   <tr><td>429 限流</td><td>✅</td><td>退避后大概率能过</td></tr>
 *   <tr><td>5xx 上游故障</td><td>✅</td><td>可能是瞬时抖动</td></tr>
 *   <tr><td>超时</td><td>✅</td><td>网络抖动，重试有意义</td></tr>
 *   <tr><td>400 参数错误</td><td>❌</td><td>同样的参数重试 100 次结果一样</td></tr>
 *   <tr><td>401/403 鉴权</td><td>❌</td><td>密钥不会自己变好</td></tr>
 *   <tr><td>422 内容审核</td><td>❌</td><td>同样的内容必然同样被拦</td></tr>
 * </table>
 */
public interface ErrorClassifier {

    /**
     * 判断异常是否可重试。
     *
     * @param throwable 异常
     * @return 可重试返回 true
     */
    boolean isRetryable(Throwable throwable);

    /**
     * 把异常翻译成统一错误码。
     *
     * @param throwable 异常
     * @return 错误码；无法识别时返回 {@link ErrorCode#SW_INTERNAL}
     */
    ErrorCode errorCode(Throwable throwable);

    /**
     * 提取上游 HTTP 状态码（若异常携带）。
     *
     * @param throwable 异常
     * @return 状态码；无则返回 -1
     */
    default int httpStatus(Throwable throwable) {
        return -1;
    }
}
