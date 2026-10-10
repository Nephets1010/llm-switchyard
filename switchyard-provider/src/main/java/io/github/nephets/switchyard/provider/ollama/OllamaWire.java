/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.core.usage.RawUsage;

import java.util.List;

/**
 * Ollama 原生协议的 wire DTO。
 *
 * <p><b>为什么必须独立适配</b>：Ollama 的字段命名与 OpenAI <b>完全相反</b>：
 *
 * <table border="1">
 *   <caption>Ollama 与 OpenAI 的字段名对照</caption>
 *   <tr><th>语义</th><th>OpenAI</th><th>Ollama</th></tr>
 *   <tr><td>输入 token</td><td>{@code prompt_tokens}</td><td>{@code prompt_eval_count}</td></tr>
 *   <tr><td>输出 token</td><td>{@code completion_tokens}</td><td>{@code eval_count}</td></tr>
 * </table>
 *
 * <p>若强行套用 OpenAI 解析器，本地部署的 Ollama 会得到<b>计量恒为 0</b> —— 而且同样不会报错，只是数字悄悄不对。
 */
public final class OllamaWire {

    private OllamaWire() {
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

        /** 采样选项。 */
        @JsonProperty("options")
        public Options options;

        /** 工具定义。 */
        @JsonProperty("tools")
        public List<Tool> tools;

        /** 格式：{@code json} 等。 */
        @JsonProperty("format")
        public String format;

        /** 消息。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Message {
            /** 角色。 */
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

        /** 采样选项。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Options {
            /** 温度。 */
            @JsonProperty("temperature")
            public Double temperature;

            /** 核采样。 */
            @JsonProperty("top_p")
            public Double topP;

            /** 最大输出 token（★ Ollama 用 num_predict，不是 max_tokens）。 */
            @JsonProperty("num_predict")
            public Integer numPredict;
        }

        /** 工具。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Tool {
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
            public Tool(String name, String description, String parametersJson) {
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

            /** 参数 schema。 */
            @JsonProperty("parameters")
            public JsonNode parameters;

            /**
             * 构造。
             *
             * @param name 名称
             * @param description 描述
             * @param parametersJson 参数 JSON Schema
             */
            public FunctionDef(String name, String description, String parametersJson) {
                this.name = name;
                this.description = description;
                if (parametersJson == null || parametersJson.isBlank()) {
                    this.parameters = MAPPER.createObjectNode();
                } else {
                    try {
                        this.parameters = MAPPER.readTree(parametersJson);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("工具参数 JSON 非法：" + parametersJson, ex);
                    }
                }
            }
        }
    }

    /** 聊天响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ChatResponse {

        /** 模型名。 */
        @JsonProperty("model")
        public String model;

        /** 生成时间。 */
        @JsonProperty("created_at")
        public String createdAt;

        /** 消息。 */
        @JsonProperty("message")
        public ChatRequest.Message message;

        /** ★ 输入 token 数（对应 OpenAI 的 prompt_tokens）。 */
        @JsonProperty("prompt_eval_count")
        public Long promptEvalCount;

        /** ★ 输出 token 数（对应 OpenAI 的 completion_tokens）。 */
        @JsonProperty("eval_count")
        public Long evalCount;

        /** 结束原因：stop / length。 */
        @JsonProperty("done_reason")
        public String doneReason;

        /** 是否结束。 */
        @JsonProperty("done")
        public Boolean done;
    }

    /** 流式分片（结构与响应相同，最后一个分片带完整的 token 计数）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class StreamChunk {

        /** 模型名。 */
        @JsonProperty("model")
        public String model;

        /** 消息。 */
        @JsonProperty("message")
        public ChatRequest.Message message;

        /** 输入 token（通常在末尾分片出现）。 */
        @JsonProperty("prompt_eval_count")
        public Long promptEvalCount;

        /** 输出 token（通常在末尾分片出现）。 */
        @JsonProperty("eval_count")
        public Long evalCount;

        /** 是否结束。 */
        @JsonProperty("done")
        public Boolean done;

        /** 结束原因。 */
        @JsonProperty("done_reason")
        public String doneReason;
    }

    /** 把 Ollama 的 token 计数转成 core 的 {@link RawUsage}。 */
    public static RawUsage toRawUsage(ChatResponse response) {
        return response == null
                ? RawUsage.empty()
                : RawUsage.ollama(response.promptEvalCount, response.evalCount);
    }

    /** 把流式分片的 token 计数转成 core 的 {@link RawUsage}。 */
    public static RawUsage toRawUsage(StreamChunk chunk) {
        return chunk == null
                ? RawUsage.empty()
                : RawUsage.ollama(chunk.promptEvalCount, chunk.evalCount);
    }
}
