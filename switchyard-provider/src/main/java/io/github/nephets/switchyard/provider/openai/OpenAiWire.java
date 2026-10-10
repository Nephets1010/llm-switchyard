/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * OpenAI 兼容协议的 wire DTO（覆盖 openai / 通义 / 智谱 / DeepSeek / Kimi / 豆包 / 硅基流动）。
 *
 * <p>⚠️ <b>为什么不用 Spring AI 的 OpenAiApi 而自己定义 DTO</b>（ADR-0006）： Spring AI 的解析器会把 {@code
 * prompt_tokens_details.cached_tokens} 与 {@code completion_tokens_details.reasoning_tokens}
 * 直接丢弃。而这两个字段恰恰是：
 *
 * <ul>
 *   <li>缓存折扣计价的依据 —— 丢了它成本会按全价算，直接影响与供应商账单的对账；
 *   <li>DeepSeek / GLM 推理成本的依据 —— 丢了它 reasoning token 的成本算不出来。
 * </ul>
 *
 * 计量精度是本项目的护城河，不能交给一个「会丢字段的第三方解析器」。
 */
public final class OpenAiWire {

    private OpenAiWire() {
        // 容器类不允许实例化
    }

    /** Jackson 策略：忽略未知字段（供应商会持续新增字段，不能因多一个字段就解析失败）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    /**
     * 解析 JSON 文本。
     *
     * @param json JSON
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

    /** 共享的 ObjectMapper（供子类与其他 provider 复用）。 */
    public static com.fasterxml.jackson.databind.ObjectMapper mapper() {
        return MAPPER;
    }

    /** 把工具参数的 JSON Schema 文本解析为树；空白视为空 schema。 */
    static JsonNode parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("工具参数 JSON 非法：" + json, ex);
        }
    }

    /** 聊天请求。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ChatRequest {

        /** 模型名。 */
        @JsonProperty("model")
        public String model;

        /** 消息列表。 */
        @JsonProperty("messages")
        public List<Message> messages;

        /** 是否流式。 */
        @JsonProperty("stream")
        public Boolean stream;

        /**
         * 流式选项。
         *
         * <p>★ 这是流式计量的关键：OpenAI / vLLM 在未设 {@code include_usage} 时<b>不返回 usage 块</b>， 这是 PRD 痛点
         * 4「流式请求计量恒为 0」的直接成因。适配器必须无条件注入它。
         */
        @JsonProperty("stream_options")
        public StreamOptions streamOptions;

        /** 最大输出 token。 */
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
        public List<ToolDef> tools;

        /** 工具选择策略。 */
        @JsonProperty("tool_choice")
        public String toolChoice;

        /** 结构化输出格式。 */
        @JsonProperty("response_format")
        public ResponseFormat responseFormat;

        /** 流式选项。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class StreamOptions {
            /** 是否在流式末尾返回 usage。 */
            @JsonProperty("include_usage")
            public Boolean includeUsage;

            /**
             * 构造开启 usage 的选项。
             *
             * @return StreamOptions
             */
            public static StreamOptions withUsage() {
                StreamOptions options = new StreamOptions();
                options.includeUsage = Boolean.TRUE;
                return options;
            }
        }

        /** 消息。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Message {
            /** 角色。 */
            @JsonProperty("role")
            public String role;

            /** 内容。 */
            @JsonProperty("content")
            public String content;

            /** 工具调用 ID（tool 角色的消息需要）。 */
            @JsonProperty("tool_call_id")
            public String toolCallId;

            /**
             * ★ 工具调用列表挂在 {@code message} 内部，不是 {@code choices[]} 层。
             *
             * <p>放在错误层级是最容易犯且最难发现的 wire bug：Jackson 不会报错，只是安静地解析出 null， 于是工具调用功能「看起来有实现」但永远不触发。
             */
            @JsonProperty("tool_calls")
            public List<ChatResponse.ToolCall> toolCalls;

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

        /** 工具定义。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class ToolDef {
            /** 类型，固定 function。 */
            @JsonProperty("type")
            public String type;

            /** 函数定义。 */
            @JsonProperty("function")
            public FunctionDef function;

            /**
             * 构造。
             *
             * @param name 工具名
             * @param description 描述
             * @param parametersJson 参数 JSON Schema
             */
            public ToolDef(String name, String description, String parametersJson) {
                this.type = "function";
                this.function = new FunctionDef(name, description, parametersJson);
            }
        }

        /** 函数定义。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class FunctionDef {
            /** 名称。 */
            @JsonProperty("name")
            public String name;

            /** 描述。 */
            @JsonProperty("description")
            public String description;

            /** 参数 JSON Schema（原文透传，不解析）。 */
            @JsonProperty("parameters")
            public JsonNode parameters;

            /**
             * 构造。
             *
             * @param name 名称
             * @param description 描述
             * @param parametersJson 参数 JSON
             */
            public FunctionDef(String name, String description, String parametersJson) {
                this.name = name;
                this.description = description;
                this.parameters = parseParameters(parametersJson);
            }
        }

        /** 结构化输出格式。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class ResponseFormat {
            /** 类型，如 {@code json_object} / {@code json_schema}。 */
            @JsonProperty("type")
            public String type;

            /**
             * 构造。
             *
             * @param type 类型
             */
            public ResponseFormat(String type) {
                this.type = type;
            }

            /**
             * 构造 json_object 格式。
             *
             * @return ResponseFormat
             */
            public static ResponseFormat jsonObject() {
                return new ResponseFormat("json_object");
            }
        }
    }

    /** 聊天响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ChatResponse {

        /** 响应 ID。 */
        @JsonProperty("id")
        public String id;

        /** 模型（供应商可能做别名映射，所以要用响应里的而非请求里的）。 */
        @JsonProperty("model")
        public String model;

        /** 候选列表。 */
        @JsonProperty("choices")
        public List<Choice> choices;

        /** 计量。 */
        @JsonProperty("usage")
        public Usage usage;

        /** 结束原因。 */
        @JsonProperty("system_fingerprint")
        public String systemFingerprint;

        /** 候选。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Choice {
            /** 下标。 */
            @JsonProperty("index")
            public Integer index;

            /** 消息。 */
            @JsonProperty("message")
            public ChatRequest.Message message;

            /** 工具调用。 */
            @JsonProperty("tool_calls")
            public List<ToolCall> toolCalls;

            /** 结束原因。 */
            @JsonProperty("finish_reason")
            public String finishReason;
        }

        /** 工具调用。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class ToolCall {
            /** 调用 ID。 */
            @JsonProperty("id")
            public String id;

            /** 类型。 */
            @JsonProperty("type")
            public String type;

            /** 函数。 */
            @JsonProperty("function")
            public FunctionCall function;

            /** 函数调用。 */
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static final class FunctionCall {
                /** 名称。 */
                @JsonProperty("name")
                public String name;

                /** 参数 JSON 文本。 */
                @JsonProperty("arguments")
                public String arguments;
            }
        }
    }

    /**
     * usage 结构 —— <b>计量精度的关键</b>。
     *
     * <p>这里刻意保留了三层嵌套：
     *
     * <ul>
     *   <li>{@code prompt_tokens_details.cached_tokens} —— 缓存命中，丢了会按全价计费；
     *   <li>{@code completion_tokens_details.reasoning_tokens} —— DeepSeek 推理 token；
     *   <li>{@code prompt_tokens_details.audio_tokens} 等新字段 —— 保留位置，避免将来又要改结构。
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Usage {

        /** 输入 token。 */
        @JsonProperty("prompt_tokens")
        public Long promptTokens;

        /** 输出 token。 */
        @JsonProperty("completion_tokens")
        public Long completionTokens;

        /** 上游报的总 token —— ★ 仅用于测试断言「我们没有拷贝它」，计量链路绝不使用。 */
        @JsonProperty("total_tokens")
        public Long totalTokens;

        /** 输入 token 明细。 */
        @JsonProperty("prompt_tokens_details")
        public PromptTokensDetails promptTokensDetails;

        /** 输出 token 明细。 */
        @JsonProperty("completion_tokens_details")
        public CompletionTokensDetails completionTokensDetails;

        /** 输入 token 明细。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class PromptTokensDetails {
            /** 缓存命中 token（★ 缓存折扣计价的依据）。 */
            @JsonProperty("cached_tokens")
            public Long cachedTokens;

            /** 音频 token。 */
            @JsonProperty("audio_tokens")
            public Long audioTokens;
        }

        /** 输出 token 明细。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class CompletionTokensDetails {
            /** 推理 token（★ DeepSeek / GLM 推理成本的依据）。 */
            @JsonProperty("reasoning_tokens")
            public Long reasoningTokens;

            /** 音频 token。 */
            @JsonProperty("audio_tokens")
            public Long audioTokens;
        }
    }

    /** 流式分片。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class StreamChunk {

        /** 响应 ID。 */
        @JsonProperty("id")
        public String id;

        /** 模型。 */
        @JsonProperty("model")
        public String model;

        /** 候选。 */
        @JsonProperty("choices")
        public List<StreamChoice> choices;

        /** 计量 —— ★ 只有在 {@code stream_options.include_usage=true} 时，末尾分片才会带它。 */
        @JsonProperty("usage")
        public Usage usage;
    }

    /**
     * 流式候选。
     *
     * <p>⚠️ <b>刻意与 {@link ChatResponse.Choice} 分开定义</b>：OpenAI 流式分片里 增量内容放在 {@code delta}
     * 字段下，而<b>非流式</b>响应里放在 {@code message} 字段下。 两者共用一个类会导致流式内容永远解析为空 —— 而且<b>不会报错</b>，
     * 只是一路流过去、最终内容是空串。这是本项目「计量与内容都要静默正确」理念下最危险的一类 bug。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class StreamChoice {

        /** 下标。 */
        @JsonProperty("index")
        public Integer index;

        /** ★ 增量消息（流式专有字段）。 */
        @JsonProperty("delta")
        public ChatRequest.Message delta;

        /** 工具调用增量（流式里工具调用也是增量拼接的）。 */
        @JsonProperty("tool_calls")
        public List<ChatResponse.ToolCall> toolCalls;

        /** 结束原因。 */
        @JsonProperty("finish_reason")
        public String finishReason;
    }
}
