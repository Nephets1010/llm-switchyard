/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm.openai;

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

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议端点（覆盖 OpenAI / 通义 / 智谱 / DeepSeek / Kimi / 豆包 的调用形态）。
 *
 * <p><b>流式为什么同步返回字符串而不是 {@code SseEmitter} 或 {@code Flux}</b>： mock 的数据量是测试级别的（3 个分片 + 1 个 usage
 * 块），同步拼一个完整的 SSE 文本即可。 两个实际好处：① 没有异步 emitter 的时序问题（响应提交后才抛异常时，客户端只能看到半截流）； ② mock 在测试期与 docker
 * compose 中的行为完全一致，不受 web 栈（MVC/WebFlux）判定的影响。
 *
 * <p><b>刻意使用 MVC 而非 WebFlux</b>：见 pom 中的说明。mock-llm 与 sample 必须跑在同一个 web 栈上， 否则症状（连不上）会出现在 sample
 * 侧而真实原因在 classpath 上，极难排查。
 */
@RestController
@RequestMapping("/v1")
public class OpenAiChatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatController.class);

    private final ScenarioEngine scenarios;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造。
     *
     * @param scenarios 场景引擎
     */
    public OpenAiChatController(ScenarioEngine scenarios) {
        this.scenarios = scenarios;
    }

    /**
     * 聊天补全端点。
     *
     * @param body 请求体
     * @return SSE 文本或 JSON 响应
     */
    @PostMapping("/chat/completions")
    public ResponseEntity<?> chatCompletions(@RequestBody Map<String, Object> body) {
        Scenario scenario = scenarios.next("/v1/chat/completions");
        boolean streaming = Boolean.TRUE.equals(body.get("stream"));

        // ★ 记录客户端是否真的带了 include_usage —— 验证「流式计量修复」是否生效的关键观测点
        Object streamOptions = body.get("stream_options");
        boolean includeUsage =
                streamOptions instanceof Map<?, ?> map
                        && Boolean.TRUE.equals(map.get("include_usage"));
        log.debug(
                "mock openai: model={} stream={} include_usage={} scenario={}",
                body.get("model"),
                streaming,
                includeUsage,
                scenario.name());

        if (scenario.httpStatus() >= 400) {
            return ResponseEntity.status(scenario.httpStatus())
                    .body(errorBody(scenario.httpStatus(), scenarioDescription(scenario)));
        }

        sleep(scenario.latencyMillis());

        String prompt = extractPrompt(body);
        String model = String.valueOf(body.getOrDefault("model", "mock-model"));
        boolean omitUsage = scenario.omitStreamUsage();
        String reply = scenario.emptyContent() ? "" : MockUsageFactory.reply(prompt, model);

        long inputTokens = MockUsageFactory.inputTokens(prompt);
        long outputTokens = MockUsageFactory.outputTokens(reply);
        long cachedTokens = MockUsageFactory.cachedInputTokens(inputTokens);

        if (streaming) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(
                            streamResponse(
                                    model,
                                    reply,
                                    inputTokens,
                                    outputTokens,
                                    cachedTokens,
                                    omitUsage,
                                    scenario));
        }
        return ResponseEntity.ok()
                .body(
                        syncResponse(
                                model,
                                prompt,
                                reply,
                                inputTokens,
                                outputTokens,
                                cachedTokens,
                                omitUsage));
    }

    /** 非流式响应。 */
    private ObjectNode syncResponse(
            String model,
            String prompt,
            String reply,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            boolean omitUsage) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "chatcmpl-mock-" + Math.abs(prompt.hashCode()));
        root.put("object", "chat.completion");
        root.put("model", model);
        root.put("system_fingerprint", "fp_mock");

        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", reply);
        choice.put("finish_reason", "stop");

        if (!omitUsage) {
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", inputTokens);
            usage.put("completion_tokens", outputTokens);
            // 刻意给一个「错误」的 total，让测试能断言网关用的是自己的重算值而非这个
            usage.put("total_tokens", inputTokens + outputTokens + 99_999);
            ObjectNode promptDetails = usage.putObject("prompt_tokens_details");
            promptDetails.put("cached_tokens", cachedTokens);
            ObjectNode completionDetails = usage.putObject("completion_tokens_details");
            completionDetails.put(
                    "reasoning_tokens", MockUsageFactory.reasoningTokens(outputTokens));
        }
        return root;
    }

    /** 流式 SSE 响应（每个事件一行，符合 SSE 规范）。 */
    private String streamResponse(
            String model,
            String reply,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            boolean omitUsage,
            Scenario scenario) {
        List<String> chunks = MockUsageFactory.split(reply, 3);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < chunks.size(); i++) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", "chatcmpl-mock-stream");
            node.put("model", model);
            ArrayNode choices = node.putArray("choices");
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);
            ObjectNode delta = choice.putObject("delta");
            delta.put("content", chunks.get(i));
            if (i == chunks.size() - 1) {
                choice.put("finish_reason", "stop");
            }
            sb.append("data: ").append(json(node)).append("\n\n");
        }

        // ★ 无论客户端是否请求 include_usage，都发 usage 块。真实供应商只在
        //   include_usage=true 时才发；这里刻意「更慷慨」，以便测试「客户端没带
        //   include_usage 但仍能拿到 usage」这一防御性路径。
        if (!omitUsage) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", "chatcmpl-mock-stream");
            node.put("model", model);
            node.putArray("choices");
            ObjectNode usage = node.putObject("usage");
            usage.put("prompt_tokens", inputTokens);
            usage.put("completion_tokens", outputTokens);
            usage.put("total_tokens", inputTokens + outputTokens + 99_999);
            ObjectNode details = usage.putObject("prompt_tokens_details");
            details.put("cached_tokens", cachedTokens);
            sb.append("data: ").append(json(node)).append("\n\n");
        }

        // 断流场景：刻意不发 [DONE]，模拟「上游中途挂掉」
        if (!scenario.dropStreamMidway()) {
            sb.append("data: [DONE]\n\n");
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

    private ObjectNode errorBody(int status, String message) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("message", message);
        error.put("type", status == 429 ? "rate_limit_error" : "invalid_request_error");
        error.put("code", String.valueOf(status));
        return root;
    }

    private String scenarioDescription(Scenario scenario) {
        return switch (scenario.httpStatus()) {
            case 429 -> "Rate limit reached for gpt-5-mini";
            case 500 -> "The server had an error while processing your request";
            case 400 -> "Invalid parameter: max_tokens";
            default -> "Mock error " + scenario.httpStatus();
        };
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
            Thread.sleep(Duration.ofMillis(millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
