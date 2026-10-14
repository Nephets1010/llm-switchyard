/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.model;

import java.util.List;

/**
 * 网关统一的请求模型：把业务层的「问模型」翻译成与供应商无关的结构。
 *
 * <p>存在的意义是让 provider 适配器不必理解 Spring AI 的 {@code Prompt}， 从而做到「core 与 provider 的计量逻辑完全不碰 Spring AI
 * 类型」（ADR-0006 的升级隔离面）。
 *
 * @param messages 消息列表，每项为 {@code (role, content)}；role 取值 {@code system/user/assistant/tool}
 * @param modelRef 目标模型
 * @param maxTokens 最大输出 token；可为 null
 * @param temperature 采样温度；可为 null
 * @param topP 核采样；可为 null
 * @param stream 是否流式
 * @param tools 工具定义（JSON Schema 文本）；可为 null
 * @param responseFormat 结构化输出格式描述；可为 null
 */
public record GatewayRequest(
        List<Message> messages,
        ModelRef modelRef,
        Integer maxTokens,
        Double temperature,
        Double topP,
        boolean stream,
        List<ToolSpec> tools,
        String responseFormat) {

    /** 紧凑构造器：归一化集合并校验。 */
    public GatewayRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不可为空");
        }
        if (modelRef == null) {
            throw new IllegalArgumentException("modelRef 不可为空");
        }
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 单轮简单请求。 */
    public static GatewayRequest simple(
            String userContent, ModelRef modelRef, boolean stream, Integer maxTokens) {
        return new GatewayRequest(
                List.of(new Message(MessageRole.USER, userContent)),
                modelRef,
                maxTokens,
                null,
                null,
                stream,
                List.of(),
                null);
    }

    /**
     * 返回一个换了目标模型的副本。
     *
     * <p>降级链切换模型时必须用它：请求里带着旧模型名会让适配器把请求发给错误的模型， 而响应里的 {@code model} 字段也会谎报成主模型 —— 审计与成本归因随之全错。
     *
     * @param newModelRef 新的目标模型
     * @return 新的请求对象（消息与参数不变）
     */
    public GatewayRequest withModel(ModelRef newModelRef) {
        return new GatewayRequest(
                messages, newModelRef, maxTokens, temperature, topP, stream, tools, responseFormat);
    }

    /** 渲染后的完整文本，供估算器与审计摘要使用。 */
    public String renderForEstimation() {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append(message.role().name()).append(':').append(message.content()).append('\n');
        }
        return sb.toString();
    }

    /** 消息角色。 */
    public enum MessageRole {
        /** 系统提示。 */
        SYSTEM,
        /** 用户输入。 */
        USER,
        /** 助手输出。 */
        ASSISTANT,
        /** 工具结果。 */
        TOOL
    }

    /**
     * 一条消息。
     *
     * @param role 角色
     * @param content 内容
     */
    public record Message(MessageRole role, String content) {
        /** 紧凑构造器。 */
        public Message {
            if (role == null) {
                throw new IllegalArgumentException("role 不可为 null");
            }
            content = content == null ? "" : content;
        }
    }

    /**
     * 工具定义。
     *
     * @param name 工具名
     * @param description 描述
     * @param parametersJson 参数 JSON Schema
     */
    public record ToolSpec(String name, String description, String parametersJson) {}
}
