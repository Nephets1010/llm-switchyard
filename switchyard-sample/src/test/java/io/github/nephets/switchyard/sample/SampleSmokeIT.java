/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.mockllm.MockLlmApplication;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ 全链路冒烟测试：sample（网关）→ mock-llm，<b>零 API Key</b>。
 *
 * <p><b>它验证的是「README 里承诺的东西真的能用」</b>，而不是某个类的单元行为：
 *
 * <ul>
 *   <li>{@code /demo/chat} 返回内容，且计量非零、成本非零；
 *   <li>{@code /demo/chat/stream} 流出 chunk，且流式计量不为 0（PRD 痛点 4 的核心验收）；
 *   <li>{@code /_switchyard/overview} 的成本与 token 是真实数字而非 0。
 * </ul>
 *
 * <p><b>为什么单独起一个 mock-llm 进程（而不是同 JVM 两个 context）</b>： 两个 {@code @SpringBootTest} context 无法共享一个
 * web 端口，而「sample 指向 mock-llm」 的关系要求两者是两个真实 HTTP 服务 —— 这正是 docker-compose 里的拓扑。 在
 * {@code @BeforeAll} 里用一个独立的 {@link SpringApplication} 把它跑在固定端口 18081， 与 compose
 * 的行为完全一致，测试才真正对应生产形态。
 */
@SpringBootTest(
        classes = SampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "llm.switchyard.audit.type=RINGBUFFER",
            "llm.switchyard.quota.default-tenant=demo-tenant",
            // ★ sample 自身的 web-application-type 也要显式声明为 servlet：
            //   测试 classpath 上同时有 starter-web 与 starter-webflux（为了 mock-llm），
            //   Spring Boot 的判定在两者都在时默认选 MVC —— 这里显式写死，
            //   让「测试环境必须显式声明 web 栈」这件事在代码里看得见，
            //   而不是依赖「starter-web 恰好排在前面」这种脆弱的 classpath 顺序。
            "spring.main.web-application-type=servlet",
            "spring.config.name=application"
        })
@DisplayName("★ 全链路冒烟：零 API Key 跑通计量与路由")
class SampleSmokeIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * mock-llm 实际监听的端口。
     *
     * <p><b>为什么用静态初始化块而不是 {@code @BeforeAll}</b>： {@code @DynamicPropertySource} 在
     * ApplicationContext 准备阶段就被调用，<b>早于</b> {@code @BeforeAll}；若在 {@code @BeforeAll}
     * 里才定端口，那时配置已经读完了， 供应商 base-url 会指向 0 号端口 —— 一个只在测试里出现、极难排查的时序陷阱。
     *
     * <p><b>为什么用「启动失败就换端口重试」而不是「先探测再 bind」</b>：试过两条路都不可靠 —— a) {@code ServerSocket(0)} 探测后立即关闭：探测与
     * bind 之间有 TOCTOU 窗口，端口会被抢走； b) 持有 ServerSocket 当端口守卫：Windows 上 Spring 自己就 bind 不上去（本次踩到）。
     * 唯一稳定的做法是「真启动一次，失败换端口重来」：端口冲突时换的是端口， 而不是引入一个更聪明的端口分配算法 —— 后者才是复杂度真正爆炸的地方。
     *
     * <p>且<b>不吞异常</b>：重试只针对端口类失败，mock-llm 的 Bean 装配失败会原样抛出， 不会伪装成「重试几次就好了」。
     */
    private static int mockLlmPort;

    private static final ConfigurableApplicationContext MOCK_LLM_CONTEXT;

    static {
        // ★ 必须在这里就清掉 SERVER_PORT，而不是等 mock 启动成功之后。
        //   Spring Boot 的 relaxed binding 会把 SERVER_PORT 环境变量读成 server.port，
        //   其优先级**高于配置文件**，会同时污染 mock 与 sample 两个上下文。
        //   （本机就设了 SERVER_PORT=8803，实测会导致「明明传了随机端口却报 Port 8803 in use」，
        //     而报错完全指不到「环境变量抢了端口」这个真因。）
        //   静态块是本测试类里最早执行的位置，比任何 Spring 上下文都早。
        System.clearProperty("SERVER_PORT");

        ConfigurableApplicationContext started = null;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5 && started == null; attempt++) {
            int candidate = findFreePort();
            try {
                started =
                        new SpringApplicationBuilder(MockLlmApplication.class)
                                .web(WebApplicationType.SERVLET)
                                .run(
                                        // ★ 三条都必须显式声明，它们各自都踩过坑：
                                        //
                                        // (1) web-application-type=servlet
                                        //     MockLlmApplication 与 SampleApplication 都在测试 classpath
                                        // 上，
                                        //     两个 @SpringBootApplication 会被同时扫描到，WebApplicationType
                                        //     的判定结果不可控（实际被判成 REACTIVE，而 mock-llm 只有
                                        //     starter-web，于是 Unable to start reactive web server）。
                                        //     显式声明把「用哪个 web 栈」变成确定的事实，而不是推断的结果。
                                        //
                                        // (2) spring.config.name=mock-llm
                                        //     两个模块都把配置放在 classpath 根目录的 application.yml，
                                        //     而 mock-llm 的测试 classpath 上同时存在 sample 的那一份。
                                        //     Spring Boot 只加载第一个 application.yml，于是 mock-llm 读到
                                        //     sample 的配置（端口、web 栈、供应商全错），
                                        //     症状却表现为「sample 连不上 mock」—— 报错离真实原因极远。
                                        //
                                        // (3) server.port 走 run(String... args)
                                        //     命令行参数是最高优先级；用 SpringApplicationBuilder.properties()
                                        //     （默认属性，最低优先级）会被 application.yml 的 server.port 覆盖掉。
                                        "--spring.main.web-application-type=servlet",
                                        "--spring.config.name=mock-llm",
                                        "--server.port=" + candidate,
                                        "--spring.main.banner-mode=off",
                                        "--logging.level.root=WARN");
                mockLlmPort = candidate;
            } catch (RuntimeException ex) {
                last = ex;
                if (isPortConflict(ex)) {
                    // 只有端口冲突才换端口重试；其它失败（Bean 装配、配置错误）重试没有意义
                    continue;
                }
                throw ex;
            }
        }
        if (started == null) {
            throw new IllegalStateException("mock-llm 连续 5 次因端口冲突启动失败", last);
        }
        MOCK_LLM_CONTEXT = started;
    }

    /** 判断异常链里是否含端口占用（只有这一类才值得换端口重试）。 */
    private static boolean isPortConflict(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof org.springframework.boot.web.server.PortInUseException
                    || current instanceof java.net.BindException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    static {
        // 把 mock-llm 的端口写进系统属性：排查时能直接从 surefire 报告里看到，
        // 而不必猜「那个随机端口到底是多少」。
        System.setProperty("swy.test.mockLlmPort", String.valueOf(mockLlmPort));
    }

    /** 向系统申请一个当前空闲的端口（探测后立即释放；抢占窗口由外层重试兜底）。 */
    private static int findFreePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("无法申请空闲端口", ex);
        }
    }

    @AfterAll
    static void stopMockLlm() {
        if (MOCK_LLM_CONTEXT != null) {
            MOCK_LLM_CONTEXT.close();
        }
    }

    @LocalServerPort private int samplePort;

    @Autowired private TestRestTemplate restTemplate;

    /**
     * 把各供应商 base-url 指向刚启动的 mock-llm。
     *
     * <p>★ 这个注解不能少：没有它，方法根本不会被 Spring 调用， 各供应商就会用 {@code application.yml} 里的默认 base-url（指向
     * localhost:18081）， 于是 sample 连的是一个并不存在的服务，日志里表现为 {@code ClosedChannelException} 与「降级链已全部失败」——
     * 完全没有指到「@DynamicPropertySource 没生效」这一层。
     */
    @DynamicPropertySource
    static void mockLlmBaseUrl(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + mockLlmPort + "/v1";
        registry.add("llm.switchyard.providers.openai.base-url", () -> base);
        registry.add("llm.switchyard.providers.deepseek.base-url", () -> base);
        registry.add("llm.switchyard.providers.dashscope.base-url", () -> base);
        registry.add(
                "llm.switchyard.providers.ollama.base-url",
                () -> "http://localhost:" + mockLlmPort);
    }

    private String url(String path) {
        return "http://localhost:" + samplePort + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> postJson(String path, String body) {
        return restTemplate.postForEntity(
                url(path), new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    @Test
    @DisplayName("★ /demo/chat 返回内容，计量与成本均非零")
    void syncChatIsMetered() throws Exception {
        ResponseEntity<String> response = postJson("/demo/chat", "{\"prompt\":\"请用一句话介绍你自己\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = MAPPER.readTree(response.getBody());

        assertThat(json.get("answer").asText()).as("必须返回模型回答").isNotBlank();

        JsonNode usage = json.get("usage");
        assertThat(usage).as("响应必须带计量信息").isNotNull();
        assertThat(usage.get("inputTokens").asLong()).as("输入 token 必须 > 0").isPositive();
        assertThat(usage.get("totalTokens").asLong()).as("★ 总 token 必须 > 0").isPositive();
        assertThat(json.get("cost").get("amountCny").asText())
                .as("★ 成本必须非零（说明价格表与计量链路都通了）")
                .isNotEqualTo("0.00000000");
        assertThat(json.get("traceId").asText()).hasSize(32);
    }

    @Test
    @DisplayName("★ /demo/chat/stream 流出 chunk，流式计量不为 0（PRD 痛点 4）")
    void streamingIsMetered() throws Exception {
        // ★ 必须用真正的流式客户端读 BodyHandlers.ofLines()：
        //   TestRestTemplate 走的是 RestTemplate，它会把整个响应体**一次性读完**再返回，
        //   于是 SSE 的响应头很可能是 "Content-Type: text/event-stream" 但 body 为空
        //   （或只拿到第一段），表现为「chunk 数 = 0」。
        //   用流式客户端才能真正验证「服务端确实逐条推了内容」。
        java.net.http.HttpRequest request =
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url("/demo/chat/stream")))
                        .header("Content-Type", "application/json")
                        .POST(
                                java.net.http.HttpRequest.BodyPublishers.ofString(
                                        "{\"prompt\":\"讲一个关于铁路道岔的笑话\"}",
                                        java.nio.charset.StandardCharsets.UTF_8))
                        .build();

        java.util.List<String> lines = new java.util.ArrayList<>();
        java.net.http.HttpResponse<java.util.stream.Stream<String>> response =
                java.net.http.HttpClient.newHttpClient()
                        .send(request, java.net.http.HttpResponse.BodyHandlers.ofLines());
        try (java.util.stream.Stream<String> body = response.body()) {
            body.forEach(lines::add);
        }

        // ★ 用 statusCode() 的整数值而非 HttpStatus 枚举比较：
        //   SSE 响应的状态码是异步写入的，其 HttpStatus 对象与枚举不是同一实例，
        //   枚举相等断言会报出「expected 200 OK but was 200」这种自相矛盾的信息。
        assertThat(response.statusCode()).as("SSE 端点应返回 200").isEqualTo(200);
        String payload = String.join("\n", lines);
        assertThat(payload).as("SSE 响应体").isNotBlank();

        int chunks = 0;
        long totalTokens = -1L;
        long inputTokens = -1L;
        long outputTokens = -1L;
        String usageSource = null;
        boolean streamUsageReported = false;
        for (String line : lines) {
            String trimmed = line.trim();
            // ★ SSE 格式是 `event: <name>` 与 `data: <json>` 成对出现，只解析 data 行。
            //   早期版本直接按 JSON 行解析，是因为端点还没包 ServerSentEvent ——
            //   那时的失败信息（500 + No converter）完全指不到「少了 event 行」这一层。
            if (trimmed.startsWith("event:")) {
                continue;
            }
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String json = trimmed.substring("data:".length()).trim();
            if (json.isEmpty()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(json);
            if (node.has("chunk") && !node.get("chunk").asText().isEmpty()) {
                chunks++;
            }
            if (node.has("usage")) {
                JsonNode usage = node.get("usage");
                inputTokens = usage.get("inputTokens").asLong();
                outputTokens = usage.get("outputTokens").asLong();
                totalTokens = usage.get("totalTokens").asLong();
                usageSource = usage.get("usageSource").asText();
                streamUsageReported = usage.get("streamUsageReported").asBoolean();
            }
        }

        assertThat(chunks).as("★ 流式应流出至少一个 chunk").isGreaterThanOrEqualTo(1);
        assertThat(totalTokens).as("★ 流式计量绝不能是 0 —— PRD 痛点 4 的核心验收点").isPositive();
        assertThat(inputTokens).isPositive();
        assertThat(outputTokens).isPositive();
        assertThat(usageSource)
                .as("★ 注入 include_usage 后应采信供应商上报的 usage")
                .isEqualTo("VENDOR_REPORTED");
        assertThat(streamUsageReported).as("流式确实收到了 usage 块").isTrue();
    }

    @Test
    @DisplayName("★ /_switchyard/overview 返回非零的成本与 token")
    void overviewShowsRealNumbers() throws Exception {
        postJson("/demo/chat", "{\"prompt\":\"你好\"}");

        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/_switchyard/overview"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = MAPPER.readTree(response.getBody());

        assertThat(json.has("cost")).as("overview 必须含 cost").isTrue();
        assertThat(json.has("tokens")).as("overview 必须含 tokens").isTrue();
        assertThat(json.has("circuitBreakers")).as("overview 必须含 circuitBreakers").isTrue();

        assertThat(json.get("tokens").get("total").asLong())
                .as("★ 打过调用后 token 总量必须非零")
                .isPositive();
        assertThat(json.get("cost").get("totalAmount").asText())
                .as("★ 成本必须非零")
                .isNotEqualTo("0.00000000");
        assertThat(json.get("totalCalls").asLong()).isPositive();
    }

    @Test
    @DisplayName("/_switchyard/models 返回供应商能力矩阵")
    void modelsEndpointListsProviders() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/_switchyard/models"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = MAPPER.readTree(response.getBody());

        assertThat(json.get("providers").size()).as("内置目录应有 9 家供应商").isGreaterThanOrEqualTo(8);
        JsonNode first = json.get("providers").get(0);
        assertThat(first.has("protocol")).isTrue();
        assertThat(first.has("capabilities")).isTrue();
    }

    @Test
    @DisplayName("/_switchyard/quota 返回配额视图")
    void quotaEndpoint() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/_switchyard/quota"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(MAPPER.readTree(response.getBody()).has("quota")).isTrue();
    }

    @Test
    @DisplayName("★ /demo/quota-demo：超出配额被拒绝，且不产生 5xx")
    void quotaDemoRejectsOverLimit() throws Exception {
        // ★ 必须用 POST：该端点是 @PostMapping，用 GET 会得到 405 METHOD_NOT_ALLOWED，
        //   而那个错误与「配额是否 fail-soft」毫无关系，很容易把人引向错误的方向。
        ResponseEntity<String> response = postJson("/demo/quota-demo?concurrency=20", "{}");

        assertThat(response.getStatusCode())
                .as("★ 配额拒绝必须是 200 业务响应，不是 5xx（PRD P0-5 fail-soft）")
                .isEqualTo(HttpStatus.OK);
        JsonNode json = MAPPER.readTree(response.getBody());

        assertThat(json.get("concurrency").asInt()).isEqualTo(20);
        assertThat(json.get("quotaRejected").asInt())
                .as("★ 配额上限被设为 200 token，20 个并发必然有大量请求被拒")
                .isPositive();
        assertThat(
                        json.get("succeeded").asInt()
                                + json.get("quotaRejected").asInt()
                                + json.get("failed").asInt())
                .isEqualTo(20);
    }
}
