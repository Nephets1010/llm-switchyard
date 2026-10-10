/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Ollama 原生协议端点。
 *
 * <p><b>刻意返回 ndjson 而不是 SSE</b>（每行一个裸 JSON，无 {@code data:} 前缀）—— 这是 Ollama 的真实行为。若 mock 这里偷懒返回
 * SSE，适配器就会「在测试里通过、 接到真实 Ollama 时拿到空响应」，而这正是本项目要防的那类静默错误。
 */
@RestController
@RequestMapping("/api")
public class OllamaChatController {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatController.class);

    private final ScenarioEngine scenarios;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造。
     *
     * @param scenarios 场景引擎
     */
    public OllamaChatController(ScenarioEngine scenarios) {
        this.scenarios = scenarios;
    }

    /**
     * 聊天端点。
     *
     * @param body 请求体
     * @return ndjson 文本或 JSON 响应
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> body) {
        Scenario scenario = scenarios.next("/api/chat");
        boolean streaming = Boolean.TRUE.equals(body.get("stream"));
        log.debug(
                "mock ollama: model={} stream={} scenario={}",
                body.get("model"),
                streaming,
                scenario.name());

        if (scenario.httpStatus() >= 400) {
            ObjectNode root = mapper.createObjectNode();
            root.put("error", "mock ollama error " + scenario.httpStatus());
            return ResponseEntity.status(scenario.httpStatus()).body(root);
        }

        sleep(scenario.latencyMillis());

        String prompt = extractPrompt(body);
        String model = String.valueOf(body.getOrDefault("model", "qwen3:8b"));
        String reply = scenario.emptyContent() ? "" : MockUsageFactory.reply(prompt, model);

        long inputTokens = MockUsageFactory.inputTokens(prompt);
        long outputTokens = MockUsageFactory.outputTokens(reply);

        if (streaming) {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("application/x-ndjson"))
                    .body(streamResponse(model, reply, inputTokens, outputTokens, scenario));
        }
        return ResponseEntity.ok().body(syncResponse(model, reply, inputTokens, outputTokens));
    }

    private ObjectNode syncResponse(
            String model, String reply, long inputTokens, long outputTokens) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("created_at", "2026-10-09T00:00:00Z");
        ObjectNode message = root.putObject("message");
        message.put("role", "assistant");
        message.put("content", reply);
        root.put("done", true);
        root.put("done_reason", "stop");
        root.put("prompt_eval_count", inputTokens);
        root.put("eval_count", outputTokens);
        return root;
    }

    private String streamResponse(
            String model, String reply, long inputTokens, long outputTokens, Scenario scenario) {
        List<String> chunks = MockUsageFactory.split(reply, 3);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < chunks.size(); i++) {
            boolean last = i == chunks.size() - 1;
            ObjectNode node = mapper.createObjectNode();
            node.put("model", model);
            ObjectNode message = node.putObject("message");
            message.put("role", "assistant");
            message.put("content", chunks.get(i));
            if (last && !scenario.dropStreamMidway()) {
                node.put("done", true);
                node.put("done_reason", "stop");
                if (!scenario.omitStreamUsage()) {
                    node.put("prompt_eval_count", inputTokens);
                    node.put("eval_count", outputTokens);
                }
            } else {
                node.put("done", false);
            }
            sb.append(json(node)).append("\n");
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
        return "";
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
