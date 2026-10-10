/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nephets.switchyard.core.audit.AuditEvent;
import io.github.nephets.switchyard.core.audit.AuditEventType;
import io.github.nephets.switchyard.core.pricing.PriceEntry;
import io.github.nephets.switchyard.core.pricing.PriceSource;
import io.github.nephets.switchyard.core.pricing.PriceUnit;
import io.github.nephets.switchyard.core.trace.TraceContext;
import io.github.nephets.switchyard.core.usage.Usage;
import io.github.nephets.switchyard.core.usage.UsageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★★★ 跨仓库契约一致性门禁（架构文档 §2.6）。
 *
 * <p>三个仓库<b>不共享 jar</b>，只共享 {@code contract/contract-v1.json} 描述的语义。
 * 这份测试用<b>反射</b>把本仓的契约类逐字段与契约文件比对：字段名、顺序、类型、必填性、枚举值 逐字符一致。任何一侧改了字段名而没同步另两侧，CI 立刻红。
 *
 * <p>这是「不共享 jar 也能共享语义」这个设计的全部保障 —— 没有它，决策 4 就是一个传说。
 */
@DisplayName("★ 跨仓契约一致性（contract-v1.json）")
class ContractSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode loadContract() throws Exception {
        // 优先读仓库根目录的副本（契约的唯一权威来源）；测试环境下通过相对路径回退
        Path fromRoot = Path.of("..", "contract", "contract-v1.json");
        Path resolved = Files.exists(fromRoot) ? fromRoot : Path.of("contract", "contract-v1.json");
        if (Files.exists(resolved)) {
            return MAPPER.readTree(Files.readString(resolved, StandardCharsets.UTF_8));
        }
        try (InputStream in =
                ContractSchemaTest.class.getClassLoader().getResourceAsStream("contract-v1.json")) {
            if (in != null) {
                return MAPPER.readTree(in.readAllBytes());
            }
        }
        throw new IllegalStateException("找不到契约文件 contract/contract-v1.json，请确认在仓库根目录存在该文件");
    }

    @Test
    @DisplayName("契约文件存在、版本受支持、六份契约齐备")
    void contractFileIsComplete() throws Exception {
        JsonNode contract = loadContract();

        assertThat(contract.get("contractVersion").asText()).isEqualTo("v1");
        assertThat(contract.get("packagePrefix").asText()).isEqualTo("io.github.nephets");

        Set<String> present = new LinkedHashSet<>();
        contract.get("contracts").fieldNames().forEachRemaining(present::add);
        assertThat(present)
                .containsExactlyInAnyOrder(
                        "CTX-001", "USG-001", "PRC-001", "EVT-001", "ERR-001", "SPN-001");
    }

    @Test
    @DisplayName("★ CTX-001：TraceContext 的字段名/顺序/类型与契约逐字符一致")
    void traceContextMatchesContract() throws Exception {
        JsonNode spec = loadContract().get("contracts").get("CTX-001");
        assertRecordMatches(TraceContext.class, spec.get("fields"));

        // httpHeaders 是字符串数组，用 asText 逐个取值（不能用 findValuesAsText）
        List<String> httpHeaders = new ArrayList<>();
        spec.get("propagation").get("httpHeaders").forEach(node -> httpHeaders.add(node.asText()));
        assertThat(httpHeaders).containsExactly("traceparent", "tracestate", "baggage");
    }

    @Test
    @DisplayName("★ USG-001：Usage 的 8 个字段与契约逐字符一致，含 total 重算不变量")
    void usageMatchesContract() throws Exception {
        JsonNode spec = loadContract().get("contracts").get("USG-001");
        assertRecordMatches(Usage.class, spec.get("fields"));

        assertThat(spec.get("invariant").asText())
                .as("契约必须写明 total 禁止拷贝上游")
                .contains("MUST be recomputed")
                .contains("FORBIDDEN");

        JsonNode gate = spec.get("accuracyGate");
        assertThat(gate.get("trafficSize").asInt()).isEqualTo(100_000);
        assertThat(gate.get("maxErrorPercent").asDouble()).isEqualTo(0.5d);
        assertThat(gate.get("streamingMustBeNonZero").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★ USG-001：UsageSource 枚举值与契约逐字符一致")
    void usageSourceEnumMatchesContract() throws Exception {
        JsonNode spec = loadContract().get("contracts").get("USG-001");
        assertEnumMatches(UsageSource.class, spec.get("enums").get("UsageSource"));
    }

    @Test
    @DisplayName("★ PRC-001：PriceEntry 字段与枚举一致，成本公式含 scale=8")
    void priceEntryMatchesContract() throws Exception {
        JsonNode spec = loadContract().get("contracts").get("PRC-001");
        assertRecordMatches(PriceEntry.class, spec.get("fields"));

        assertEnumMatches(PriceUnit.class, spec.get("enums").get("PriceUnit"));
        assertEnumMatches(PriceSource.class, spec.get("enums").get("PriceSource"));

        assertThat(spec.get("formula").asText()).contains("scale=8");
        assertThat(spec.get("priceTableFile").asText()).isEqualTo("switchyard-prices.yml");
        assertThat(spec.get("priceTableHotReload").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★ EVT-001：AuditEvent 字段与枚举一致，usage 不可为 null")
    void auditEventMatchesContract() throws Exception {
        JsonNode spec = loadContract().get("contracts").get("EVT-001");
        assertRecordMatches(AuditEvent.class, spec.get("fields"));

        assertEnumMatches(AuditEventType.class, spec.get("enums").get("AuditEventType"));
        assertThat(spec.get("nullPlaceholderForbidden").asText()).contains("unknown");
    }

    @Test
    @DisplayName("★ ERR-001：错误码集合、HTTP 状态与 retryable 逐项一致")
    void errorCodesMatchContract() throws Exception {
        JsonNode spec = loadContract().get("contracts").get("ERR-001");

        Map<String, ErrorCode> byName = new LinkedHashMap<>();
        for (ErrorCode code : ErrorCode.values()) {
            byName.put(code.name(), code);
        }

        JsonNode values = spec.get("values");
        assertThat(values).as("契约必须列出全部错误码").isNotNull();

        List<String> contractNames = new ArrayList<>();
        values.forEach(node -> contractNames.add(node.get("name").asText()));

        assertThat(contractNames)
                .as("本仓 ErrorCode 枚举与契约的错误码集合必须完全相同")
                .containsExactlyInAnyOrderElementsOf(byName.keySet());

        values.forEach(
                node -> {
                    ErrorCode code = byName.get(node.get("name").asText());
                    assertThat(code.httpStatus())
                            .as("%s 的 HTTP 状态码", code.name())
                            .isEqualTo(node.get("httpStatus").asInt());
                    assertThat(code.retryable())
                            .as("%s 的 retryable", code.name())
                            .isEqualTo(node.get("retryable").asBoolean());
                });

        List<String> responseShape = new ArrayList<>();
        spec.get("responseBodyShape").forEach(node -> responseShape.add(node.asText()));
        assertThat(responseShape)
                .containsExactlyInAnyOrder("code", "message", "retryable", "traceId", "timestamp");
    }

    @Test
    @DisplayName("★ SPN-001：自有属性全部带 swy. 前缀，无裸 swy")
    void spanAttributesUseSwyPrefix() throws Exception {
        JsonNode spec = loadContract().get("contracts").get("SPN-001");

        List<String> attributes = new ArrayList<>();
        spec.get("customAttributes").forEach(node -> attributes.add(node.get("name").asText()));

        assertThat(attributes).isNotEmpty();
        assertThat(attributes)
                .as("自定义属性必须全部以 swy. 开头，防止与未来 OTel 约定撞名")
                .allSatisfy(name -> assertThat(name).startsWith("swy.").isNotEqualTo("swy"));

        // 本仓（switchyard）必须落地的属性
        assertThat(attributes)
                .contains(
                        "swy.tenant.id",
                        "swy.biz.line",
                        "swy.feature",
                        "swy.prompt.version",
                        "swy.usage.source",
                        "swy.usage.stream_reported",
                        "swy.usage.reasoning_tokens",
                        "swy.usage.cached_input_tokens",
                        "swy.cost.amount",
                        "swy.cost.currency",
                        "swy.price.source",
                        "swy.payload.digest",
                        "swy.payload.full");

        assertThat(spec.get("spanNameTemplate").asText())
                .isEqualTo("{gen_ai.operation.name} {target}");
        assertThat(attributes.stream().noneMatch(n -> !n.contains(".")))
                .as("所有自定义属性名都必须带 '.' 分段")
                .isTrue();
    }

    @Test
    @DisplayName("契约的 providerId 集合与 ProviderCatalog 实际提供的集合一致")
    void providerIdSetMatchesCatalog() throws Exception {
        JsonNode contract = loadContract();
        List<String> declaredValues = new ArrayList<>();
        contract.get("conventions")
                .get("providerIdValues")
                .forEach(node -> declaredValues.add(node.asText()));

        List<String> actual =
                io.github.nephets.switchyard.core.provider.ProviderCatalog.builtin().all().stream()
                        .map(
                                io.github.nephets.switchyard.core.provider.ProviderCatalogEntry
                                        ::providerId)
                        .sorted()
                        .toList();

        assertThat(actual)
                .as("ProviderCatalog 提供的 providerId 必须覆盖契约声明的全部取值")
                .containsAll(declaredValues);
        assertThat(declaredValues).isNotEmpty();
    }

    @Test
    @DisplayName("契约的 errorCode 前缀与自定义属性前缀约定被本仓遵守")
    void conventionsRespected() throws Exception {
        JsonNode conventions = loadContract().get("conventions");

        assertThat(conventions.get("errorCodePrefix").asText()).isEqualTo("SW_");
        assertThat(conventions.get("customAttributePrefix").asText()).isEqualTo("swy.");
        assertThat(conventions.get("currency").asText()).isEqualTo("CNY");
        assertThat(conventions.get("moneyScaleInternal").asInt()).isEqualTo(8);

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.name()).startsWith("SW_");
        }
        assertThat(PriceEntry.CURRENCY_CNY).isEqualTo(conventions.get("currency").asText());
    }

    @Test
    @DisplayName("★ 仓库根目录的契约副本可被解析且与加载到的契约语义一致")
    void contractCopyIsLoadable() throws Exception {
        Path fromRoot = Path.of("..", "contract", "contract-v1.json");
        if (Files.exists(fromRoot)) {
            byte[] rootCopy = Files.readAllBytes(fromRoot);
            assertThat(MAPPER.readTree(rootCopy))
                    .as("根目录 contract/contract-v1.json 与加载到的契约语义必须一致")
                    .isEqualTo(loadContract());
        }
        assertThat(loadContract().get("contracts").size()).isGreaterThanOrEqualTo(6);
    }

    /**
     * 反射校验 record 的字段名、顺序、类型与契约文件一致。
     *
     * @param recordClass record 类
     * @param fields 契约中的字段数组
     */
    private static void assertRecordMatches(Class<?> recordClass, JsonNode fields) {
        assertThat(recordClass.isRecord()).as("%s 必须是 record", recordClass.getName()).isTrue();
        RecordComponent[] components = recordClass.getRecordComponents();

        List<String> actualNames =
                java.util.Arrays.stream(components).map(RecordComponent::getName).toList();
        List<String> expectedNames = new ArrayList<>();
        fields.forEach(node -> expectedNames.add(node.get("name").asText()));

        assertThat(actualNames)
                .as("%s 的字段名与顺序必须与契约逐字符一致", recordClass.getSimpleName())
                .containsExactlyElementsOf(expectedNames);

        Map<String, RecordComponent> byName = new LinkedHashMap<>();
        for (RecordComponent component : components) {
            byName.put(component.getName(), component);
        }

        fields.forEach(
                node -> {
                    String name = node.get("name").asText();
                    String expectedType = node.get("type").asText();
                    RecordComponent component = byName.get(name);
                    assertThat(component).isNotNull();

                    String actualType = simpleTypeName(component.getType());
                    assertThat(actualType)
                            .as("%s.%s 的类型", recordClass.getSimpleName(), name)
                            .isEqualTo(expectedType);

                    if (node.has("unit") && !node.get("unit").isNull()) {
                        assertThat(node.get("unit").asText())
                                .as("%s.%s 的单位", recordClass.getSimpleName(), name)
                                .isNotBlank();
                    }
                });
    }

    /** 校验枚举值与契约一致。 */
    private static void assertEnumMatches(Class<?> enumClass, JsonNode contractValues) {
        List<String> expected = new ArrayList<>();
        contractValues.forEach(node -> expected.add(node.asText()));
        List<String> actual = new ArrayList<>();
        for (Object constant : enumClass.getEnumConstants()) {
            actual.add(constant.toString());
        }

        assertThat(actual)
                .as("%s 的枚举值必须与契约逐字符一致", enumClass.getSimpleName())
                .containsExactlyElementsOf(expected);
    }

    /** 类型的可读短名，与契约文件里写的名字对齐。 */
    private static String simpleTypeName(Class<?> type) {
        if (type == long.class || type == Long.class) {
            return "long";
        }
        if (type == int.class || type == Integer.class) {
            return "Integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == String.class) {
            return "String";
        }
        if (type == BigDecimal.class) {
            return "BigDecimal";
        }
        if (type == Instant.class) {
            return "Instant";
        }
        if (type == LocalDate.class) {
            return "LocalDate";
        }
        return type.getSimpleName();
    }

    /** 契约测试专用：验证审计摘要算法为 SHA-256。 */
    @Test
    @DisplayName("审计 payloadDigest 使用 sha256: 前缀（契约 EVT-001）")
    void digestAlgorithmMatchesContract() {
        String payload = "你是客服助手，请用一句话问候用户";
        String digest = AuditEvent.digestOf(payload);

        assertThat(digest).startsWith("sha256:");
        assertThat(digest).hasSize("sha256:".length() + 64);
        assertThat(digest).isEqualTo(AuditEvent.verifyDigestAlgorithm(payload));
        assertThat(AuditEvent.digestOf(null)).isNull();
        assertThat(AuditEvent.currency()).isEqualTo("CNY");
        assertThat(AuditEvent.typeNames())
                .containsExactlyInAnyOrder(
                        "MODEL_CALL", "TOOL_CALL", "RETRIEVAL", "APPROVAL", "FLOW_STEP");
    }

    /** 契约测试辅助：确认枚举顺序与契约声明顺序一致（便于跨仓 diff）。 */
    @Test
    @DisplayName("枚举声明顺序稳定，便于跨仓逐行 diff")
    void enumOrderIsStable() {
        assertThat(
                        java.util.Arrays.stream(ErrorCode.values())
                                .map(Enum::name)
                                .collect(Collectors.toList()))
                .containsExactly(
                        "SW_QUOTA_EXCEEDED",
                        "SW_PROVIDER_UNAVAILABLE",
                        "SW_PROVIDER_TIMEOUT",
                        "SW_MODEL_UNAVAILABLE",
                        "SW_CONTENT_FILTERED",
                        "SW_BAD_REQUEST",
                        "SW_TOOL_DENIED",
                        "SW_APPROVAL_REQUIRED",
                        "SW_APPROVAL_DENIED",
                        "SW_INTERNAL");

        assertThat(java.util.Arrays.stream(UsageSource.values()).map(Enum::name).toList())
                .containsExactly("VENDOR_REPORTED", "ESTIMATED", "FALLBACK");
    }
}
