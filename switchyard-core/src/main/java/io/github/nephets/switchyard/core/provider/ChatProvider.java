/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.provider;

import io.github.nephets.switchyard.core.model.GatewayRequest;
import io.github.nephets.switchyard.core.model.GatewayResponse;

/**
 * 供应商适配器（SPI）。
 *
 * <p>注意本接口<b>刻意不返回 {@code Flux}</b>：流式由 {@link StreamingChatProvider} 这一可选扩展接口承载。
 * 理由是让「只支持非流式的供应商」（比如某些私有部署的推理端点）实现本接口时 不用去写一个假的 {@code Flux}。
 */
public interface ChatProvider {

    /**
     * 供应商标识，与 {@code ProviderCatalog} 中的 providerId 一致。
     *
     * @return 供应商标识
     */
    String providerId();

    /** 本适配器的能力声明。 */
    ProviderCapabilities capabilities();

    /**
     * 发起一次非流式调用。
     *
     * <p>实现约定：<b>不得把 HTTP 异常原样抛给上层</b>，必须经 {@code ProviderExceptionTranslator} 翻译成 {@code
     * SwitchyardException}。
     *
     * @param request 统一请求
     * @return 统一响应（含归一化前的原始 usage 会被适配器先行归一化）
     */
    GatewayResponse complete(GatewayRequest request);

    /** 适配器名，写入日志便于定位是谁在处理。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /** 是否健康（能否连通）。供 {@code doctor} 命令与控制台健康页使用。 */
    default boolean healthy() {
        return true;
    }

    /**
     * 流式能力扩展接口。
     *
     * <p>流式实现必须做到两件事（PRD 痛点 4 的核心）：
     *
     * <ol>
     *   <li><b>请求时注入 {@code stream_options.include_usage=true}</b> —— vLLM / OpenAI 在未设该参数时<b>不返回
     *       usage 块</b>，这是流式计量恒为 0 的根因；
     *   <li><b>解析末尾的 usage chunk</b> —— 即使供应商返回了，也要正确提取， 提取不到时回退估算并把 {@code streamUsageReported} 置
     *       false，让问题可被观测。
     * </ol>
     */
    interface StreamingChatProvider extends ChatProvider {

        /**
         * 发起一次流式调用。
         *
         * @param request 统一请求，{@code stream} 会被强制置为 true
         * @return 统一响应，{@code chunks} 携带分片，{@code usage} 已归一化
         */
        GatewayResponse stream(GatewayRequest request);
    }
}
