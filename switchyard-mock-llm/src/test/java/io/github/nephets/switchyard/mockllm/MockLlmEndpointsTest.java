/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.mockllm.admin.ScenarioEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ mock-llm 的端点级测试：真起 HTTP，验协议细节。
 *
 * <p>这些断言是「别人的测试能不能依赖 mock-llm」的保证 —— 特别是流式里 {@code include_usage} 的处理、{@code total_tokens} 故意给错的值，
 * 以及 Ollama 的 ndjson 与 OpenAI 的 SSE 两种格式的差异。
 */
@SpringBootTest(
        classes = MockLlmApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("mock-llm 端点：三种协议 + 故障注入")
class MockLlmEndpointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private ScenarioEngine scenarios;

    @BeforeEach
    void reset() {
        scenarios.reset();
    }

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(String path, Object body) {
        return restTemplate.postForEntity(path, new HttpEntity<>(body, json()), String.class);
    }

    @Nested
    @DisplayName("OpenAI 兼容协议")
    class OpenAiProtocol {

        @Test
        @DisplayName("★ 同步响应带完整 usage 三层嵌套")
        void syncUsage() throws Exception {
            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "gpt-5-mini",
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "请用一句话介绍你自己"))));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode json = MAPPER.readTree(response.getBody());
            JsonNode usage = json.get("usage");

            assertThat(usage).isNotNull();
            assertThat(usage.get("prompt_tokens").asLong()).isPositive();
            assertThat(usage.get("completion_tokens").asLong()).isPositive();
            assertThat(usage.get("prompt_tokens_details").get("cached_tokens").asLong())
                    .as("★ 缓存命中必须出现在响应里 —— 上游不返回它，网关就按全价计费")
                    .isPositive();
            assertThat(usage.get("completion_tokens_details").get("reasoning_tokens").asLong())
                    .isPositive();
            assertThat(json.get("choices").get(0).get("message").get("content").asText())
                    .isNotBlank();
        }

        @Test
        @DisplayName("★ total_tokens 刻意给错，用于验证网关用的是自己的重算值")
        void totalTokensIsDeliberatelyWrong() throws Exception {
            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "m",
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "你好"))));

            JsonNode usage = MAPPER.readTree(response.getBody()).get("usage");
            long prompt = usage.get("prompt_tokens").asLong();
            long completion = usage.get("completion_tokens").asLong();
            long total = usage.get("total_tokens").asLong();

            assertThat(total)
                    .as("★ mock 故意让 total 多出 99999，测试才能断言「网关不拷贝上游 total」")
                    .isEqualTo(prompt + completion + 99_999L)
                    .isNotEqualTo(prompt + completion);
        }

        @Test
        @DisplayName("★ 流式带 include_usage 时末尾返回 usage 块")
        void streamWithUsage() throws Exception {
            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "m",
                                    "stream",
                                    true,
                                    "stream_options",
                                    Map.of("include_usage", true),
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "讲个笑话"))));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String payload = response.getBody();
            assertThat(payload).contains("data:").contains("[DONE]");

            List<JsonNode> dataLines = dataLines(payload);
            assertThat(dataLines.size()).isGreaterThanOrEqualTo(3);
            JsonNode usageChunk = dataLines.get(dataLines.size() - 1).get("usage");
            assertThat(usageChunk).as("★ 末尾必须有 usage 块").isNotNull();
            assertThat(usageChunk.get("prompt_tokens").asLong()).isPositive();
        }

        @Test
        @DisplayName("★ 流式增量在 delta 字段（非流式在 message 字段，两者不可混用）")
        void streamUsesDelta() throws Exception {
            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "m",
                                    "stream",
                                    true,
                                    "stream_options",
                                    Map.of("include_usage", true),
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "你好"))));

            for (JsonNode node : dataLines(response.getBody())) {
                if (node.has("choices") && !node.get("choices").isEmpty()) {
                    JsonNode choice = node.get("choices").get(0);
                    assertThat(choice.has("delta"))
                            .as("★ 流式分片的内容在 delta 下；写 message 会让流式内容恒为空且不报错")
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("★ empty-usage-stream 场景：末尾确实没有 usage 块（复现 PRD 痛点 4）")
        void emptyUsageStreamScenario() throws Exception {
            scenarios.switchTo("empty-usage-stream");

            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "m",
                                    "stream",
                                    true,
                                    "stream_options",
                                    Map.of("include_usage", true),
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "你好"))));

            for (JsonNode node : dataLines(response.getBody())) {
                assertThat(node.has("usage")).as("★ 这个场景必须真的不返回 usage，否则测试就失去意义").isFalse();
            }
        }

        @Test
        @DisplayName("★ drop-stream 场景：不发送 [DONE]")
        void dropStreamScenario() {
            scenarios.switchTo("drop-stream");

            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "m",
                                    "stream",
                                    true,
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "你好"))));

            assertThat(response.getBody()).doesNotContain("[DONE]");
        }

        @Test
        @DisplayName("工具调用被正确返回")
        void toolCalls() throws Exception {
            // mock 当前不模拟工具调用，但响应结构要能被客户端解析
            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model", "m",
                                    "messages",
                                            List.of(Map.of("role", "user", "content", "查一下 KB")),
                                    "tools",
                                            List.of(
                                                    Map.of(
                                                            "type",
                                                            "function",
                                                            "function",
                                                            Map.of(
                                                                    "name",
                                                                    "search_kb",
                                                                    "description",
                                                                    "查知识库",
                                                                    "parameters",
                                                                    Map.of(
                                                                            "type",
                                                                            "object",
                                                                            "properties",
                                                                            Map.of(
                                                                                    "q",
                                                                                    Map.of(
                                                                                            "type",
                                                                                            "string"))))))));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("Anthropic 协议")
    class AnthropicProtocol {

        @Test
        @DisplayName("★ usage 用 Anthropic 自己的字段名（input_tokens / cache_read_input_tokens）")
        void syncUsage() throws Exception {
            ResponseEntity<String> response =
                    post(
                            "/v1/messages",
                            Map.of(
                                    "model",
                                    "claude-haiku-4-5",
                                    "max_tokens",
                                    512,
                                    "system",
                                    "你是客服助手",
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "你好"))));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode json = MAPPER.readTree(response.getBody());
            JsonNode usage = json.get("usage");

            assertThat(usage.get("input_tokens").asLong()).isPositive();
            assertThat(usage.get("output_tokens").asLong()).isPositive();
            assertThat(usage.has("cache_read_input_tokens"))
                    .as("★ Anthropic 的缓存字段名与 OpenAI 不同，少了它缓存会被按全价计费")
                    .isTrue();
            assertThat(json.get("content").get(0).get("text").asText()).isNotBlank();
            assertThat(json.get("stop_reason").asText()).isNotBlank();
        }

        @Test
        @DisplayName("★ 流式 usage 分两处到达：message_start 给输入、message_delta 给真实输出")
        void streamUsageInTwoPlaces() throws Exception {
            ResponseEntity<String> response =
                    post(
                            "/v1/messages",
                            Map.of(
                                    "model",
                                    "claude-haiku-4-5",
                                    "max_tokens",
                                    512,
                                    "stream",
                                    true,
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "你好"))));

            String payload = response.getBody();
            // ★ Anthropic 不发 [DONE]，以 message_stop 事件收尾 —— 这是它与 OpenAI 的协议差异，
            //   断言 [DONE] 会把「实现错误」与「协议本就如此」混为一谈。
            assertThat(payload).contains("data:").contains("message_stop");
            assertThat(payload).doesNotContain("[DONE]");

            long startInput = 0;
            long deltaOutput = 0;
            long startOutput = -1;
            for (String line : payload.split("\n")) {
                String trimmed = line.trim();
                // Anthropic 的 SSE 既有 event: 行也有 data: 行；只从 data: 行取 JSON，
                // 事件类型由 data 里的 "type" 字段自己带上。
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(trimmed.substring(5).trim());
                if ("message_start".equals(node.path("type").asText())) {
                    startInput = node.path("message").path("usage").path("input_tokens").asLong();
                    startOutput = node.path("message").path("usage").path("output_tokens").asLong();
                }
                if ("message_delta".equals(node.path("type").asText())) {
                    deltaOutput = node.path("usage").path("output_tokens").asLong();
                }
            }

            assertThat(startInput).as("输入侧 usage 来自 message_start").isPositive();
            assertThat(startOutput).as("★ message_start 里的 output_tokens 是占位值 1").isEqualTo(1L);
            assertThat(deltaOutput)
                    .as("★ 输出侧真实值来自 message_delta；只用 message_start 会恒为 1，成本系统性偏低")
                    .isGreaterThan(1L);
        }
    }

    @Nested
    @DisplayName("Ollama 协议")
    class OllamaProtocol {

        @Test
        @DisplayName("★ token 计数字段名与 OpenAI 相反：prompt_eval_count / eval_count")
        void syncUsage() throws Exception {
            ResponseEntity<String> response =
                    post(
                            "/api/chat",
                            Map.of(
                                    "model",
                                    "qwen3:8b",
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "你好"))));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode json = MAPPER.readTree(response.getBody());

            assertThat(json.get("prompt_eval_count").asLong())
                    .as("★ 套用 OpenAI 解析器读 prompt_tokens 会得到 0")
                    .isPositive();
            assertThat(json.get("eval_count").asLong()).isPositive();
            assertThat(json.get("message").get("content").asText()).isNotBlank();
        }

        @Test
        @DisplayName("★ 流式是 ndjson（每行裸 JSON，无 data: 前缀）")
        void streamIsNdjson() throws Exception {
            ResponseEntity<String> response =
                    post(
                            "/api/chat",
                            Map.of(
                                    "model",
                                    "qwen3:8b",
                                    "stream",
                                    true,
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "你好"))));

            String payload = response.getBody();
            assertThat(payload)
                    .as("★ Ollama 用 ndjson，不加 data: 前缀 —— 加了会解析不到任何分片")
                    .doesNotContain("data:");

            List<JsonNode> lines = new java.util.ArrayList<>();
            for (String line : payload.split("\n")) {
                if (!line.trim().isEmpty()) {
                    lines.add(MAPPER.readTree(line));
                }
            }
            assertThat(lines).isNotEmpty();
            assertThat(lines.get(lines.size() - 1).path("done").asBoolean())
                    .as("最后一个分片带 done=true 与 token 计数")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("故障注入与管理端点")
    class FaultInjection {

        @Test
        @DisplayName("★ 429 场景：响应状态码就是 429，body 里有 error.message")
        void rateLimit() throws Exception {
            scenarios.switchTo("rate-limit");

            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "m",
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "x"))));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            JsonNode error = MAPPER.readTree(response.getBody()).get("error");
            assertThat(error.get("message").asText()).isNotBlank();
            assertThat(error.get("type").asText()).isEqualTo("rate_limit_error");
        }

        @Test
        @DisplayName("400 场景：invalid_request_error（不可重试）")
        void invalidRequest() throws Exception {
            scenarios.switchTo("invalid-req");

            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "m",
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "x"))));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(MAPPER.readTree(response.getBody()).get("error").get("type").asText())
                    .isEqualTo("invalid_request_error");
        }

        @Test
        @DisplayName("500 场景：可重试的服务端错误")
        void serverError() {
            scenarios.switchTo("server-error");

            ResponseEntity<String> response =
                    post(
                            "/v1/chat/completions",
                            Map.of(
                                    "model",
                                    "m",
                                    "messages",
                                    List.of(Map.of("role", "user", "content", "x"))));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("★ /__admin/health 可用（docker-compose healthcheck 依赖它）")
        void healthEndpoint() throws Exception {
            ResponseEntity<String> response =
                    restTemplate.getForEntity("/__admin/health", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode json = MAPPER.readTree(response.getBody());
            assertThat(json.get("status").asText()).isEqualTo("UP");
            assertThat(json.has("scenario")).isTrue();
        }

        @Test
        @DisplayName("★ /__admin/scenarios 列出全部场景，/__admin/reset 复位")
        void scenarioEndpoints() throws Exception {
            ResponseEntity<String> list =
                    restTemplate.getForEntity("/__admin/scenarios", String.class);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(MAPPER.readTree(list.getBody()).has("rate-limit")).isTrue();

            scenarios.switchTo("slow");
            ResponseEntity<String> reset =
                    restTemplate.postForEntity(
                            "/__admin/reset", new HttpEntity<>("", json()), String.class);
            assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(scenarios.current().name()).isEqualTo("ok");
        }

        @Test
        @DisplayName("★ /__admin/stats 返回请求与错误计数")
        void statsEndpoint() throws Exception {
            post(
                    "/v1/chat/completions",
                    Map.of(
                            "model",
                            "m",
                            "messages",
                            List.of(Map.of("role", "user", "content", "x"))));

            JsonNode stats =
                    MAPPER.readTree(
                            restTemplate.getForEntity("/__admin/stats", String.class).getBody());
            assertThat(stats.get("requestCount").asLong()).isPositive();
        }
    }

    /** 解析 SSE 文本，取出所有 data: 行的 JSON。 */
    private List<JsonNode> dataLines(String payload) throws Exception {
        List<JsonNode> result = new java.util.ArrayList<>();
        if (payload == null) {
            return result;
        }
        for (String line : payload.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:") && !trimmed.equals("data:[DONE]")) {
                String json = trimmed.substring(5).trim();
                if (!json.isEmpty() && !"[DONE]".equals(json)) {
                    result.add(MAPPER.readTree(json));
                }
            }
        }
        return result;
    }
}
