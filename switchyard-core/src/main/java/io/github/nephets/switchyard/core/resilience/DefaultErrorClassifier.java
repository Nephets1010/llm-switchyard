/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.resilience;

import io.github.nephets.switchyard.core.ErrorCode;
import io.github.nephets.switchyard.core.SwitchyardException;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 默认异常分类器。
 *
 * <p><b>HTTP 状态码 → 错误码的映射表</b>：
 *
 * <ul>
 *   <li>400 / 404 / 422 → {@code SW_BAD_REQUEST} / {@code SW_CONTENT_FILTERED}（不可重试）
 *   <li>401 / 403 → {@code SW_BAD_REQUEST}（不可重试：密钥不会自己变好，重试只会触发风控）
 *   <li>429 → {@code SW_PROVIDER_UNAVAILABLE}（可重试，配指数退避）
 *   <li>5xx → {@code SW_PROVIDER_UNAVAILABLE}（可重试）
 *   <li>超时类异常 → {@code SW_PROVIDER_TIMEOUT}（可重试）
 * </ul>
 *
 * <p>实现方式刻意做成「异常类型 + 状态码」两条独立路径： 有些上游（如用 JDK HttpClient 的适配器）抛 {@code IOException} 但没状态码， 有些（如
 * Spring 的 WebClient）有状态码但包成 {@code WebClientResponseException}。 两条路径都覆盖，才能既不错判也不漏判。
 */
public class DefaultErrorClassifier implements ErrorClassifier {

    @Override
    public boolean isRetryable(Throwable throwable) {
        return errorCode(throwable).retryable();
    }

    @Override
    public ErrorCode errorCode(Throwable throwable) {
        if (throwable == null) {
            return ErrorCode.SW_INTERNAL;
        }
        if (throwable instanceof SwitchyardException swy) {
            return swy.errorCode();
        }

        // 路径 1：按异常类型
        if (isTimeout(throwable)) {
            return ErrorCode.SW_PROVIDER_TIMEOUT;
        }

        // 路径 2：按 HTTP 状态码
        int status = httpStatus(throwable);
        if (status > 0) {
            return fromStatus(status);
        }

        // 路径 3：网络层异常。IOException 一般意味着连接被拒/重置/超时，属于瞬时故障
        if (throwable instanceof IOException) {
            return ErrorCode.SW_PROVIDER_UNAVAILABLE;
        }

        return ErrorCode.SW_INTERNAL;
    }

    @Override
    public int httpStatus(Throwable throwable) {
        Throwable current = throwable;
        // 向上追溯最多 6 层：够穿过适配器的包装，又不至于在环形 cause 里打转
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current instanceof HttpStatusCarrier carrier) {
                return carrier.httpStatus();
            }
            String message = current.getMessage();
            int status = extractStatusFromMessage(message);
            if (status > 0) {
                return status;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return -1;
    }

    /**
     * HTTP 状态码到错误码的映射。
     *
     * <p>⚠️ <b>404 刻意映射为 {@code SW_BAD_REQUEST}（不可重试）</b>：对 LLM 端点而言 404 几乎总是 「模型名写错」或「base-url
     * 写错」，同样的请求重试 100 次还是 404。而 {@link ErrorCode#SW_MODEL_UNAVAILABLE}
     * 虽然可重试，但它的语义是「<b>降级链上的模型全部失败</b>」——那是网关本地的判定，不是上游状态码的映射。 把 404
     * 映射成它会让「换一个模型再试」与「对同一个不存在的模型重试」混为一谈。
     */
    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 429 -> ErrorCode.SW_PROVIDER_UNAVAILABLE;
            case 400 -> ErrorCode.SW_BAD_REQUEST;
            case 401, 403 -> ErrorCode.SW_BAD_REQUEST;
            case 404 -> ErrorCode.SW_BAD_REQUEST;
            case 408, 504 -> ErrorCode.SW_PROVIDER_TIMEOUT;
            case 422 -> ErrorCode.SW_CONTENT_FILTERED;
            case 503 -> ErrorCode.SW_PROVIDER_UNAVAILABLE;
            default -> status >= 500 ? ErrorCode.SW_PROVIDER_UNAVAILABLE : ErrorCode.SW_BAD_REQUEST;
        };
    }

    /** 超时判定：显式的超时异常，或 cause 链里的超时异常。 */
    public static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof SQLTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /**
     * 从异常消息里尽力提取 HTTP 状态码。
     *
     * <p>依赖消息文本本身不优雅，但它的价值在于「宁可多识别一点」： 一次被误判为不可重试的 503 会让调用直接失败，而一次被误判为可重试的 400 只是多花 200ms。 真实项目里大量
     * HTTP 客户端的异常消息都含 {@code "429"} / {@code "status code: 503"} 这类片段。
     */
    private static int extractStatusFromMessage(String message) {
        if (message == null || message.length() < 3) {
            return -1;
        }
        for (String pattern :
                new String[] {"status: ", "status code: ", "HTTP ", "code: ", "status="}) {
            int idx = message.indexOf(pattern);
            if (idx >= 0) {
                int start = idx + pattern.length();
                int end = start;
                while (end < message.length() && Character.isDigit(message.charAt(end))) {
                    end++;
                }
                if (end > start) {
                    try {
                        int value = Integer.parseInt(message.substring(start, end));
                        if (value >= 100 && value < 600) {
                            return value;
                        }
                    } catch (NumberFormatException ignored) {
                        // 继续尝试下一个 pattern
                    }
                }
            }
        }
        return -1;
    }

    /**
     * 让适配器能把自己的 HTTP 状态码喂给分类器，而不必继承框架异常。
     *
     * <p>provider 层的适配器实现本接口后，{@code DefaultErrorClassifier} 就能在 「完全不依赖 Spring 的异常体系」的前提下识别状态码 ——
     * 这是 core 零框架依赖能成立的关键。
     */
    public interface HttpStatusCarrier {
        /** 上游返回的 HTTP 状态码。 */
        int httpStatus();
    }
}
