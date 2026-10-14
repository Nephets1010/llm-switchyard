/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("W3C TraceContext 编解码（CTX-001）")
class W3CTraceCodecContractTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @AfterEach
    void clearContext() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("★ traceparent 编码格式严格为 00-{traceId}-{spanId}-{flags}")
    void encodeFormat() {
        TraceContext ctx = new TraceContext(TRACE_ID, SPAN_ID, null, "01", null);

        assertThat(W3CTraceCodec.encodeTraceparent(ctx))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    @DisplayName("★ 编解码往返一致（traceId/spanId/flags 逐字符还原）")
    void roundTrip() {
        TraceContext original = new TraceContext(TRACE_ID, SPAN_ID, null, "01", "vendor=value");
        String encoded = W3CTraceCodec.encodeTraceparent(original);

        assertThat(W3CTraceCodec.decodeTraceparent(encoded))
                .as("traceparent 只承载 traceId/spanId/flags，traceState 走独立的头")
                .contains(new TraceContext(TRACE_ID, SPAN_ID, null, "01", null));
    }

    @ParameterizedTest(name = "畸形输入「{0}」返回 empty 而不抛异常")
    @DisplayName("畸形 traceparent 不抛异常（它来自不可信的 HTTP 头）")
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "garbage",
                "00-tooshort",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",
                "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-xx",
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"
            })
    void malformedTraceparentReturnsEmpty(String input) {
        assertThat(W3CTraceCodec.decodeTraceparent(input)).isEmpty();
    }

    @Test
    @DisplayName("构造器校验 hex 长度与字符集")
    void constructorValidates() {
        assertThatThrownBy(() -> new TraceContext("tooshort", SPAN_ID, null, "01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 位");
        assertThatThrownBy(() -> new TraceContext(TRACE_ID, "ZZZZ", null, "01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("小写 hex");
        assertThatThrownBy(() -> new TraceContext(TRACE_ID, SPAN_ID, null, "99", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceFlags");
    }

    @Test
    @DisplayName("★ 虚拟线程安全：子线程能继承 traceId（用 InheritableThreadLocal 而非裸 ThreadLocal）")
    void contextIsInheritableByVirtualThread() throws Exception {
        TraceContext root = TraceContext.createRoot();
        TraceContext.set(root);

        TraceContext[] holder = new TraceContext[1];
        Thread thread =
                Thread.ofVirtual()
                        .unstarted(
                                () -> {
                                    holder[0] = TraceContext.currentOrNull();
                                });
        thread.start();
        thread.join();

        assertThat(holder[0]).as("虚拟线程必须能读到父线程的 traceId，否则审计会断链").isNotNull();
        assertThat(holder[0].traceId()).isEqualTo(root.traceId());
    }

    @Test
    @DisplayName("with(...) 作用域退出后自动恢复原上下文")
    void withRestoresPreviousContext() {
        TraceContext outer = TraceContext.createRoot();
        TraceContext inner = TraceContext.createRoot();
        TraceContext.set(outer);

        TraceContext.with(inner, () -> assertThat(TraceContext.current()).isEqualTo(inner));

        assertThat(TraceContext.current()).isEqualTo(outer);
    }

    @Test
    @DisplayName("current() 在无上下文时自动创建根上下文（不要求用户先初始化）")
    void currentCreatesRootWhenAbsent() {
        TraceContext.clear();
        TraceContext created = TraceContext.current();

        assertThat(created).isNotNull();
        assertThat(created.traceId()).hasSize(32);
        assertThat(created.parentSpanId()).isNull();
        assertThat(created.sampled()).isTrue();
    }

    @Test
    @DisplayName("newChild() 继承 traceId 但换 spanId 并记下父 span")
    void newChild() {
        TraceContext parent = TraceContext.createRoot();
        TraceContext child = parent.newChild();

        assertThat(child.traceId()).isEqualTo(parent.traceId());
        assertThat(child.spanId()).isNotEqualTo(parent.spanId());
        assertThat(child.parentSpanId()).isEqualTo(parent.spanId());
        assertThat(child.sampled()).isTrue();
    }

    @Test
    @DisplayName("clear() 清空绑定，避免线程复用时串号")
    void clearRemovesContext() {
        TraceContext.set(TraceContext.createRoot());
        TraceContext.clear();

        assertThat(TraceContext.currentOrNull()).isNull();
    }

    @ParameterizedTest(name = "flags={0} → sampled={1}")
    @CsvSource({"01, true", "00, false"})
    @DisplayName("traceFlags 语义")
    void traceFlags(String flags, boolean sampled) {
        assertThat(new TraceContext(TRACE_ID, SPAN_ID, null, flags, null).sampled())
                .isEqualTo(sampled);
    }

    @Test
    @DisplayName("★ baggage 只透传白名单键（客户端可写，不做白名单等于允许注入）")
    void baggageOnlyPassesWhitelistedKeys() {
        Map<String, String> baggage =
                Map.of(
                        BaggageKeys.TENANT_ID,
                        "vip",
                        BaggageKeys.BIZ_LINE,
                        "customer-service",
                        "evil.injected",
                        "rm -rf /");

        String encoded = W3CTraceCodec.encodeBaggage(baggage);

        assertThat(encoded).contains("swy.tenant.id=vip").doesNotContain("evil.injected");
        assertThat(W3CTraceCodec.decodeBaggage(encoded))
                .containsEntry(BaggageKeys.TENANT_ID, "vip")
                .hasSize(2);
    }

    @Test
    @DisplayName("baggage 值里的分隔符被替换，防止伪造出第二个键")
    void baggageValueSanitized() {
        String encoded =
                W3CTraceCodec.encodeBaggage(
                        Map.of(BaggageKeys.TENANT_ID, "vip;swy.feature=hacked"));

        assertThat(encoded).doesNotContain(";").contains("vip_swy.feature_hacked");
        assertThat(W3CTraceCodec.decodeBaggage(encoded)).hasSize(1);
    }

    @Test
    @DisplayName("空 baggage 编码为 null，解码为空 Map")
    void emptyBaggage() {
        assertThat(W3CTraceCodec.encodeBaggage(null)).isNull();
        assertThat(W3CTraceCodec.encodeBaggage(Map.of())).isNull();
        assertThat(W3CTraceCodec.decodeBaggage(null)).isEmpty();
        assertThat(W3CTraceCodec.decodeBaggage("")).isEmpty();
    }

    @Test
    @DisplayName("tracestate 超过 32 个成员时截断（W3C 规范上限）")
    void tracestateTruncated() {
        String longState =
                String.join(
                        ",",
                        java.util.stream.IntStream.range(0, 40)
                                .mapToObj(i -> "k" + i + "=v")
                                .toList());

        String encoded = W3CTraceCodec.encodeTracestate(longState);

        assertThat(encoded.split(",")).hasSize(W3CTraceCodec.MAX_TRACESTATE_MEMBERS);
        assertThat(W3CTraceCodec.encodeTracestate(null)).isNull();
        assertThat(W3CTraceCodec.encodeTracestate("a=1")).isEqualTo("a=1");
    }

    @Test
    @DisplayName("★ payload 摘要为 sha256:<64 hex>，且内容相同则摘要相同（可验证脱敏逻辑生效）")
    void digestIsStableAndSha256() {
        String payload = "你是客服助手，请用一句话问候用户";

        String digest = W3CTraceCodec.digest(payload);

        assertThat(digest).startsWith("sha256:").hasSize(7 + 64);
        assertThat(W3CTraceCodec.digest(payload)).isEqualTo(digest);
        assertThat(W3CTraceCodec.digest(payload + "！")).isNotEqualTo(digest);
        assertThat(W3CTraceCodec.digest(null)).isNull();
    }

    @Test
    @DisplayName("toHex 生成固定宽度小写 hex")
    void toHexIsFixedWidth() {
        assertThat(W3CTraceCodec.toHex(0L)).isEqualTo("0000000000000000");
        assertThat(W3CTraceCodec.toHex(1L)).isEqualTo("0000000000000001");
        assertThat(W3CTraceCodec.toHex(-1L)).isEqualTo("ffffffffffffffff");
    }

    @Test
    @DisplayName("encodeAll 同时写入 traceparent 与 tracestate")
    void encodeAllWritesBothHeaders() {
        TraceContext ctx = new TraceContext(TRACE_ID, SPAN_ID, null, "01", "vendor=acme");
        Map<String, String> headers = new java.util.LinkedHashMap<>();

        W3CTraceCodec.encodeAll(ctx, headers::put);

        assertThat(headers).containsKeys(W3CTraceCodec.TRACEPARENT, W3CTraceCodec.TRACESTATE);
        assertThat(headers.get(W3CTraceCodec.TRACEPARENT)).startsWith("00-");
    }
}
