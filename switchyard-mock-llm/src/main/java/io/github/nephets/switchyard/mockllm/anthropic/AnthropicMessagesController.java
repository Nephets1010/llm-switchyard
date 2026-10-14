/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.nephets.switchyard.mockllm.admin.Scenario;
import io.github.nephets.switchyard.mockllm.admin.ScenarioEngine;
import io.github.nephets.switchyard.mockllm.support.MockUsageFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 端点。
 *
 * <p>刻意模拟了 Anthropic 流式的两个真实特征，它们是适配器必须处理的难点：
 *
 * <ul>
 *   <li>usage <b>分两处到达</b>：{@code message_start} 带输入侧（含 cache_read_input_tokens）， {@code
 *       message_delta} 带真实输出量。若只读其中一处，计量就会缺一半。
 *   <li>每个事件用 {@code event: <name>} + {@code data: <json>} 成对表示，不是裸 {@code data:}。
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class AnthropicMessagesController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessagesController.class);

    private final ScenarioEngine scenarios;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造。
     *
     * @param scenarios 场景引擎
     */
    public AnthropicMessagesController(ScenarioEngine scenarios) {
        this.scenarios = scenarios;
    }

    /**
     * 消息端点。
     *
     * @param body 请求体
     * @return SSE 文本或 JSON 响应
     */
    @PostMapping("/messages")
    public ResponseEntity<?> messages(@RequestBody Map<String, Object> body) {
        Scenario scenario = scenarios.next("/v1/messages");
        boolean streaming = Boolean.TRUE.equals(body.get("stream"));
        log.debug(
                "mock anthropic: model={} stream={} scenario={}",
                body.get("model"),
                streaming,
                scenario.name());

        if (scenario.httpStatus() >= 400) {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode error = root.putObject("error");
            error.put(
                    "type",
                    scenario.httpStatus() == 429 ? "rate_limit_error" : "invalid_request_error");
            error.put("message", "mock anthropic error " + scenario.httpStatus());
            return ResponseEntity.status(scenario.httpStatus()).body(root);
        }

        sleep(scenario.latencyMillis());

        String prompt = extractPrompt(body);
        String model = String.valueOf(body.getOrDefault("model", "claude-mock"));
        String reply = scenario.emptyContent() ? "" : MockUsageFactory.reply(prompt, model);

        long inputTokens = MockUsageFactory.inputTokens(prompt);
        long outputTokens = MockUsageFactory.outputTokens(reply);
        long cacheRead = MockUsageFactory.cachedInputTokens(inputTokens);

        if (streaming) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(
                            streamResponse(
                                    model, reply, inputTokens, outputTokens, cacheRead, scenario));
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("id", "msg_mock_" + Math.abs(prompt.hashCode()));
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", model);
        ArrayNode content = root.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", reply);
        root.put("stop_reason", "end_turn");

        if (!scenario.omitStreamUsage()) {
            ObjectNode usage = root.putObject("usage");
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", outputTokens);
            usage.put("cache_creation_input_tokens", 0);
            usage.put("cache_read_input_tokens", cacheRead);
        }
        return ResponseEntity.ok().body(root);
    }

    private String streamResponse(
            String model,
            String reply,
            long inputTokens,
            long outputTokens,
            long cacheRead,
            Scenario scenario) {
        StringBuilder sb = new StringBuilder();

        // 事件 1：message_start —— 带输入侧 usage（output_tokens 在这里是占位值 1）
        ObjectNode start = mapper.createObjectNode();
        start.put("type", "message_start");
        ObjectNode message = start.putObject("message");
        message.put("id", "msg_mock_stream");
        message.put("model", model);
        message.put("role", "assistant");
        message.putArray("content").addObject();
        ObjectNode startUsage = message.putObject("usage");
        startUsage.put("input_tokens", inputTokens);
        startUsage.put("output_tokens", 1);
        startUsage.put("cache_creation_input_tokens", 0);
        startUsage.put("cache_read_input_tokens", cacheRead);
        sb.append("event: message_start\ndata: ").append(json(start)).append("\n\n");

        // 事件 2..n：content_block_delta
        List<String> chunks = MockUsageFactory.split(reply, 3);
        for (String chunk : chunks) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "content_block_delta");
            node.put("index", 0);
            ObjectNode delta = node.putObject("delta");
            delta.put("type", "text_delta");
            delta.put("text", chunk);
            sb.append("event: content_block_delta\ndata: ").append(json(node)).append("\n\n");
        }

        // 事件 n+1：message_delta —— 带真实输出量
        ObjectNode messageDelta = mapper.createObjectNode();
        messageDelta.put("type", "message_delta");
        messageDelta.putObject("delta").put("stop_reason", "end_turn");
        if (!scenario.omitStreamUsage()) {
            ObjectNode usage = messageDelta.putObject("usage");
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", outputTokens);
        }
        sb.append("event: message_delta\ndata: ").append(json(messageDelta)).append("\n\n");

        // 事件 n+2：message_stop
        if (!scenario.dropStreamMidway()) {
            ObjectNode stop = mapper.createObjectNode();
            stop.put("type", "message_stop");
            sb.append("event: message_stop\ndata: ").append(json(stop)).append("\n\n");
        }
        return sb.toString();
    }

    private String extractPrompt(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (messages instanceof List<?> list && !list.isEmpty()) {
            Object last = list.get(list.size() - 1);
            if (last instanceof Map<?, ?> map && map.get("content") != null) {
                return String.valueOf(map.get("content"));
            }
        }
        return body.get("system") == null ? "" : String.valueOf(body.get("system"));
    }

    /** 序列化为 JSON 文本；mock 内部结构必然可序列化。 */
    private static String json(com.fasterxml.jackson.databind.JsonNode node) {
        try {
            return new ObjectMapper().writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("mock 序列化响应失败", ex);
        }
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
