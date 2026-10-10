/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.sample.web;

import io.github.nephets.switchyard.core.ErrorCode;
import io.github.nephets.switchyard.core.SwitchyardException;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.starter.RoutedChatModel;
import io.github.nephets.switchyard.starter.SwitchyardRouter;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示控制器：把「网关做了什么」直接摊开给读者看。
 *
 * <p><b>这里刻意返回完整上下文</b>（usage 各字段、成本、剩余配额、traceId）， 而不是只返回模型的文本。原因：读者无法验证「计量准不准」， 除非把计量结果本身展示出来。
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final SwitchyardRouter router;
    private final ChatClient chatClient;

    /**
     * 构造。
     *
     * @param router 网关门面
     */
    public DemoController(SwitchyardRouter router) {
        this.router = router;
        // ★ 这就是「30 秒上手」的全部改动：ChatModel 的来源换成 router，其余零改动
        this.chatClient = ChatClient.builder(router.route("default")).build();
    }

    /**
     * 同步对话。
     *
     * @param request 请求体
     * @return 含计量与成本的完整结果
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        String prompt =
                request.prompt() == null || request.prompt().isBlank() ? "你好" : request.prompt();
        String routeName = request.route() == null ? "default" : request.route();

        ChatResponse response =
                router.routedModel(routeName)
                        .call(new org.springframework.ai.chat.prompt.Prompt(prompt));
        RoutedChatModel.CallContext ctx = router.routedModel(routeName).lastCallContext();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("route", routeName);
        body.put("prompt", prompt);
        body.put("answer", response == null ? "" : response.getResult().getOutput().getText());

        if (response != null
                && response.getMetadata() != null
                && response.getMetadata().getUsage() != null) {
            body.put(
                    "springAiUsage",
                    Map.of(
                            "promptTokens", response.getMetadata().getUsage().getPromptTokens(),
                            "completionTokens",
                                    response.getMetadata().getUsage().getCompletionTokens(),
                            "totalTokens", response.getMetadata().getUsage().getTotalTokens()));
        }
        putUsage(body, ctx);
        body.put("remainingQuota", ctx == null ? -1L : ctx.remainingQuota());
        return ResponseEntity.ok(body);
    }

    /**
     * 流式对话（SSE）。
     *
     * <p>★ 这是 PRD 痛点 4 的现场演示：流式结束后最后一条一定带 usage， 且 {@code totalTokens > 0}。
     *
     * @param request 请求体
     * @return SSE 流
     */
    /**
     * 流式对话（SSE）。
     *
     * <p>★ 这是 PRD 痛点 4 的现场演示：流式结束后最后一条一定带 usage，且 {@code totalTokens > 0}。
     *
     * <p><b>为什么用 {@link SseEmitter} 而不是 {@code Flux<ServerSentEvent>} 返回</b>： sample 是 Spring
     * MVC（{@code spring-boot-starter-web}）应用。MVC 的响应式返回会被 {@code ReactiveTypeHandler} 先收集成一个 List
     * 再输出，遇到 {@code text/event-stream} 时没有对应的 HttpMessageConverter，直接抛 {@code
     * HttpMessageNotWritableException} → 500，且<b>流式语义完全丢失</b>。 {@code SseEmitter} 是 MVC 下正确的 SSE
     * 写法：立即建立连接，逐条 push。
     *
     * @param request 请求体
     * @return SSE emitter
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter chatStream(
            @RequestBody ChatRequest request) {
        String prompt =
                request.prompt() == null || request.prompt().isBlank() ? "你好" : request.prompt();
        String routeName = request.route() == null ? "default" : request.route();
        RoutedChatModel model = router.routedModel(routeName);

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(120_000L);

        // ★ 必须把上游调用「同步完成」后再返回 emitter，而不是在 subscribe() 的异步流里做。
        //   SseEmitter 的响应在方法返回后才提交，此时上游调用其实还没开始 ——
        //   结果是客户端拿到一个空流，且日志里连一次模型调用都没有。
        //   正确姿势：先拿到完整的流式结果，再逐条推给 emitter。
        //   （网关本身把整条流累积为一个响应，因此这里不存在「边生成边推送」，
        //     这也是 ChatModel SPI 的既定语义：stream() 返回的是 Flux<ChatResponse>。）
        List<String> chunks = new ArrayList<>();
        try {
            model.stream(new org.springframework.ai.chat.prompt.Prompt(prompt))
                    .doOnNext(
                            response ->
                                    chunks.add(
                                            response.getResult() == null
                                                    ? ""
                                                    : response.getResult().getOutput().getText()))
                    .blockLast(java.time.Duration.ofSeconds(60));
        } catch (RuntimeException ex) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("error", ex.getMessage());
            try {
                emitter.send(
                        org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("error")
                                .data(failure));
            } catch (java.io.IOException ignored) {
                // 客户端已断开
            }
            emitter.complete();
            return emitter;
        }

        // 逐条推给客户端
        try {
            for (String chunk : chunks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunk", chunk);
                emitter.send(
                        org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("chunk")
                                .data(item));
            }
            // ★ 最后一条带上计量：这是整个 demo 的重点
            Map<String, Object> tail = new LinkedHashMap<>();
            RoutedChatModel.CallContext ctx = model.lastCallContext();
            putUsage(tail, ctx);
            tail.put("remainingQuota", ctx == null ? -1L : ctx.remainingQuota());
            tail.put("isFinal", true);
            emitter.send(
                    org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("usage")
                            .data(tail));
            emitter.complete();
        } catch (java.io.IOException | IllegalStateException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    /**
     * 并发配额演示：连续发 N 个请求，触发配额拒绝。
     *
     * <p>这是 PRD P0-4「并发 100 请求时超额请求被正确拒绝」的现场版本。
     *
     * @param concurrency 并发数
     * @param route 路由名
     * @param prompt 提示词
     * @return 汇总结果
     */
    @PostMapping(value = "/quota-demo", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> quotaDemo(
            @RequestParam(defaultValue = "20") int concurrency,
            @RequestParam(defaultValue = "default") String route,
            @RequestParam(defaultValue = "你好") String prompt)
            throws InterruptedException {
        int total = Math.min(Math.max(concurrency, 1), 200);
        RoutedChatModel model = router.routedModel(route);

        // ★ QuotaKey 必须与 RoutedChatModel 内部构造的完全一致 —— 它是
        //   (defaultTenant, bizLine=null, providerId, modelId) 四元组。
        //   这里若填了 bizLine，键就对不上，设的 200 token 上限永远不生效，
        //   于是「并发触发拒绝」这个演示会安静地一个都拒不掉。
        io.github.nephets.switchyard.core.quota.QuotaKey key =
                new io.github.nephets.switchyard.core.quota.QuotaKey(
                        router.properties().getQuota().getDefaultTenant(),
                        null,
                        "openai",
                        "gpt-5-mini");
        model.quotaStore()
                .putPolicy(
                        key,
                        new io.github.nephets.switchyard.core.quota.QuotaPolicy(
                                io.github.nephets.switchyard.core.quota.QuotaPolicy.Period.DAILY,
                                200L,
                                io.github.nephets.switchyard.core.quota.OverLimitBehavior
                                        .FAIL_SOFT));

        var executor = java.util.concurrent.Executors.newFixedThreadPool(Math.min(total, 16));
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger ok =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failed =
                new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < total; i++) {
            executor.submit(
                    () -> {
                        try {
                            start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            model.call(new org.springframework.ai.chat.prompt.Prompt(prompt));
                            // ★ fail-soft 的定义是「返回业务响应而不是抛异常」，所以不能靠 catch 判断拒绝。
                            //   必须查调用上下文里的 error 字段，否则统计出来的全是 succeeded，
                            //   演示会显示「20 个请求全部成功」而实际上全被配额挡掉了。
                            RoutedChatModel.CallContext ctx = model.lastCallContext();
                            if (ctx != null && ctx.rejected()) {
                                rejected.incrementAndGet();
                            } else {
                                ok.incrementAndGet();
                            }
                        } catch (SwitchyardException ex) {
                            if (ex.errorCode() == ErrorCode.SW_QUOTA_EXCEEDED) {
                                rejected.incrementAndGet();
                            } else {
                                failed.incrementAndGet();
                            }
                        } catch (Exception ex) {
                            failed.incrementAndGet();
                        }
                    });
        }
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        // ★ 演示结束后把配额恢复成不限额。
        //   InMemoryQuotaStore 是进程内单例（嵌入式网关的定位），若不恢复，
        //   这个「故意设成 200 token」的配额会一直留在 store 里，
        //   之后所有走同一 (tenant, provider, model) 键的请求都会被拒 ——
        //   包括用户自己后续的正常调用。演示不该给使用方留下一个坏状态。
        model.quotaStore()
                .putPolicy(
                        key, io.github.nephets.switchyard.core.quota.QuotaPolicy.unlimitedPolicy());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("concurrency", total);
        body.put("succeeded", ok.get());
        body.put("quotaRejected", rejected.get());
        body.put("failed", failed.get());
        body.put("quotaLimitTokens", 200L);
        body.put(
                "note",
                "配额上限被故意设为 200 token，因此绝大多数请求会得到 SW_QUOTA_EXCEEDED（HTTP 429 业务响应，不抛 5xx 异常）；"
                        + "演示结束后配额已自动恢复为不限额");
        return ResponseEntity.ok(body);
    }

    /** 把计量上下文放进响应。 */
    private void putUsage(Map<String, Object> body, RoutedChatModel.CallContext ctx) {
        if (ctx == null) {
            return;
        }
        Usage usage = ctx.usage();
        if (usage != null) {
            Map<String, Object> usageMap = new LinkedHashMap<>();
            usageMap.put("inputTokens", usage.inputTokens());
            usageMap.put("outputTokens", usage.outputTokens());
            usageMap.put("reasoningTokens", usage.reasoningTokens());
            usageMap.put("cachedInputTokens", usage.cachedInputTokens());
            usageMap.put("totalTokens", usage.totalTokens());
            usageMap.put("usageSource", usage.usageSource().name());
            usageMap.put("reasoningEstimated", usage.reasoningEstimated());
            usageMap.put("streamUsageReported", usage.streamUsageReported());
            body.put("usage", usageMap);
        }
        BigDecimal cost = ctx.costAmount();
        if (cost != null) {
            body.put(
                    "cost",
                    Map.of(
                            "amountCny",
                            cost.setScale(8, java.math.RoundingMode.HALF_UP).toPlainString(),
                            "currency",
                            "CNY"));
        }
        if (ctx.trace() != null) {
            body.put("traceId", ctx.trace().traceId());
        }
        if (ctx.rejected()) {
            body.put("rejected", true);
            body.put("errorCode", ctx.error() == null ? null : ctx.error().code());
        }
    }

    /** 对话请求体。 */
    public record ChatRequest(String prompt, String route) {}
}
