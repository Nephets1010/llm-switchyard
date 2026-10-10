/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.core.usage.RawUsage;

import java.util.List;

/**
 * Anthropic Messages API 的 wire DTO。
 *
 * <p><b>与 OpenAI 系的三个关键差异</b>（这三个差异是必须独立适配的原因）：
 *
 * <ol>
 *   <li><b>system 提示不在 messages 里</b>，而是顶层独立字段 → 转换时必须拆开；
 *   <li><b>缓存用两个字段</b>：{@code cache_creation_input_tokens}（写入）与 {@code
 *       cache_read_input_tokens}（读取），OpenAI 只有一个 {@code cached_tokens}；
 *   <li><b>API Key 走 {@code x-api-key} 头</b>而非 {@code Authorization: Bearer}， 且必须带 {@code
 *       anthropic-version} 头。
 * </ol>
 */
public final class AnthropicWire {

    private AnthropicWire() {
        // 容器类不允许实例化
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    /** 共享的 ObjectMapper。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 解析 JSON。
     *
     * @param json JSON 文本
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 解析结果
     */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("解析 " + type.getSimpleName() + " 失败：响应体不是预期结构", ex);
        }
    }

    /** 消息请求。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class MessagesRequest {

        /** 模型名。 */
        @JsonProperty("model")
        public String model;

        /** 对话消息（不含 system）。 */
        @JsonProperty("messages")
        public List<Message> messages;

        /** ★ 系统提示 —— Anthropic 特有：独立顶层字段，不在 messages 里。 */
        @JsonProperty("system")
        public String system;

        /** 是否流式。 */
        @JsonProperty("stream")
        public Boolean stream;

        /** 最大输出 token —— Anthropic 必填。 */
        @JsonProperty("max_tokens")
        public Integer maxTokens;

        /** 采样温度。 */
        @JsonProperty("temperature")
        public Double temperature;

        /** 核采样。 */
        @JsonProperty("top_p")
        public Double topP;

        /** 工具定义。 */
        @JsonProperty("tools")
        public List<Tool> tools;

        /** 工具选择策略。 */
        @JsonProperty("tool_choice")
        public ToolChoice toolChoice;

        /** 消息。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Message {
            /** 角色：user 或 assistant。 */
            @JsonProperty("role")
            public String role;

            /** 内容。 */
            @JsonProperty("content")
            public String content;

            /** Jackson 反序列化需要一个无参构造器。 */
            public Message() {}

            /**
             * 构造（序列化方向使用）。
             *
             * @param role 角色
             * @param content 内容
             */
            public Message(String role, String content) {
                this.role = role;
                this.content = content;
            }
        }

        /** 工具。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Tool {
            /** 名称。 */
            @JsonProperty("name")
            public String name;

            /** 描述。 */
            @JsonProperty("description")
            public String description;

            /** 参数 JSON Schema。 */
            @JsonProperty("input_schema")
            public JsonNode inputSchema;
        }

        /** 工具选择。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class ToolChoice {
            /** 类型，如 {@code auto} / {@code any} / {@code tool}。 */
            @JsonProperty("type")
            public String type;

            /**
             * 构造。
             *
             * @param type 类型
             */
            public ToolChoice(String type) {
                this.type = type;
            }
        }
    }

    /** 消息响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MessagesResponse {

        /** 响应 ID。 */
        @JsonProperty("id")
        public String id;

        /** 类型。 */
        @JsonProperty("type")
        public String type;

        /** 模型。 */
        @JsonProperty("model")
        public String model;

        /** 内容块。 */
        @JsonProperty("content")
        public List<ContentBlock> content;

        /** 结束原因。 */
        @JsonProperty("stop_reason")
        public String stopReason;

        /** ★ 计量。 */
        @JsonProperty("usage")
        public Usage usage;

        /** 内容块。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class ContentBlock {
            /** 类型：text 或 tool_use。 */
            @JsonProperty("type")
            public String type;

            /** 文本内容。 */
            @JsonProperty("text")
            public String text;

            /** 工具调用 ID。 */
            @JsonProperty("id")
            public String id;

            /** 工具名。 */
            @JsonProperty("name")
            public String name;

            /** 工具入参。 */
            @JsonProperty("input")
            public JsonNode input;
        }
    }

    /**
     * usage 结构 —— 与 OpenAI 系的字段名完全不同。
     *
     * <p>注意：Anthropic <b>没有</b> {@code total_tokens} 字段，因此更凸显 「total 一律本仓重算」这条规则的必要性 —— 一旦上游没有
     * total， 依赖上游的实现会直接得到 null。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Usage {

        /** 输入 token。 */
        @JsonProperty("input_tokens")
        public Long inputTokens;

        /** 输出 token。 */
        @JsonProperty("output_tokens")
        public Long outputTokens;

        /** 缓存写入 token（★ Anthropic 特有）。 */
        @JsonProperty("cache_creation_input_tokens")
        public Long cacheCreationInputTokens;

        /** 缓存读取 token（★ Anthropic 特有，是 OpenAI cached_tokens 的对应物）。 */
        @JsonProperty("cache_read_input_tokens")
        public Long cacheReadInputTokens;
    }

    /**
     * 流式事件。
     *
     * <p>Anthropic 的 SSE 用 {@code event:} + {@code data:} 成对出现，数据结构是 {@code message_start} → {@code
     * content_block_delta}（多次）→ {@code message_delta}（带 usage）→ {@code message_stop}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class StreamEvent {

        /** 事件类型。 */
        @JsonProperty("type")
        public String type;

        /** 索引。 */
        @JsonProperty("index")
        public Integer index;

        /** message_start 携带的 message（含 usage）。 */
        @JsonProperty("message")
        public MessagesResponse message;

        /** content_block_delta 的增量。 */
        @JsonProperty("delta")
        public Delta delta;

        /** message_delta 携带的用量与结束原因。 */
        @JsonProperty("usage")
        public Usage usage;

        /** 增量体。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Delta {
            /** 类型。 */
            @JsonProperty("type")
            public String type;

            /** 文本增量。 */
            @JsonProperty("text")
            public String text;

            /** 结束原因。 */
            @JsonProperty("stop_reason")
            public String stopReason;
        }
    }

    /** 把 Anthropic usage 转成 core 的 {@link RawUsage}。 */
    public static RawUsage toRawUsage(Usage usage) {
        if (usage == null) {
            return RawUsage.empty();
        }
        return RawUsage.anthropic(
                usage.inputTokens,
                usage.outputTokens,
                usage.cacheCreationInputTokens,
                usage.cacheReadInputTokens);
    }
}
