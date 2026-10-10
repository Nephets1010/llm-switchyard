/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.pricing;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 价格表 YAML 加载器（契约 PRC-001）。
 *
 * <p><b>热更新是 PRD 的明确要求</b>：「价格表更新无需改代码、无需重启」。 实现方式是「外部文件 → 解析 → 整体替换」的热加载，配合 starter 层的定时调度 （默认 5
 * 分钟一轮，PRD P0-7）。
 *
 * <p><b>安全考量</b>：用 SnakeYAML 的 {@link SafeConstructor} 而不是默认构造器。 价格表虽然是自家维护的文件，但它常被挂载进容器、也可能来自配置中心
 * —— 允许任意类型实例化等于给了一个反序列化入口。
 *
 * <p><b>加载失败绝不影响服务</b>：解析异常时保留旧表并返回失败计数。价格表出错的正确表现是 「控制台告警 + 成本按 0 记账」，而不是「网关启动失败」——
 * 后者会把一次配置笔误升级成一次生产事故。
 */
public class PricingTableLoader {

    /** 默认价格表文件名（classpath 与外部文件同名）。 */
    public static final String DEFAULT_RESOURCE = "prices/switchyard-prices.yml";

    private final PricingTable table;
    private volatile String lastError;
    private volatile Instant lastAttemptAt;

    /**
     * 构造加载器。
     *
     * @param table 被本加载器维护的价格表
     */
    public PricingTableLoader(PricingTable table) {
        this.table = table;
    }

    /**
     * 从 classpath 加载内置价格表。
     *
     * @return 生效条目数
     * @throws IllegalStateException 资源缺失或 YAML 格式错误时
     */
    public int loadFromClasspath() {
        try (InputStream in =
                PricingTableLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 中找不到内置价格表：" + DEFAULT_RESOURCE);
            }
            int count = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            table.reload();
            return count;
        } catch (IOException ex) {
            throw new IllegalStateException("读取内置价格表失败：" + DEFAULT_RESOURCE, ex);
        }
    }

    /**
     * 从外部文件加载（热更新的主入口）。
     *
     * <p>失败时<b>不抛异常</b>：保留旧表，记录 {@link #lastError()}。
     *
     * @param path 价格表文件路径
     * @return 生效条目数；解析失败返回 -1
     */
    public int loadFromFile(Path path) {
        lastAttemptAt = Instant.now();
        if (path == null || !Files.exists(path)) {
            lastError = "价格表文件不存在：" + path;
            return -1;
        }
        try {
            String yaml = Files.readString(path, StandardCharsets.UTF_8);
            int count = parse(yaml);
            table.reload();
            lastError = null;
            return count;
        } catch (IOException | RuntimeException ex) {
            lastError = "价格表加载失败：" + ex.getMessage();
            return -1;
        }
    }

    /**
     * 直接从 YAML 文本解析并整体替换。
     *
     * @param yaml YAML 文本
     * @return 生效条目数
     */
    @SuppressWarnings("unchecked")
    public int parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return 0;
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        if (!(loaded instanceof Map)) {
            return 0;
        }
        Object rawList = ((Map<String, Object>) loaded).get("prices");
        List<PriceEntry> parsed = new ArrayList<>();
        if (rawList instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    parsed.add(toEntry((Map<String, Object>) map));
                }
            }
        }
        int size = table.replaceAll(parsed);
        lastError = null;
        return size;
    }

    /** 上次加载失败的原因；成功或从未失败时为 null。 */
    public String lastError() {
        return lastError;
    }

    /** 上次尝试加载的时刻。 */
    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    /** 被维护的价格表。 */
    public PricingTable table() {
        return table;
    }

    @SuppressWarnings("unchecked")
    private static PriceEntry toEntry(Map<String, Object> map) {
        String providerId = str(map, "providerId");
        String modelId = str(map, "modelId");
        String currency =
                map.containsKey("currency") ? str(map, "currency") : PriceEntry.CURRENCY_CNY;
        PriceUnit unit =
                map.containsKey("unit")
                        ? PriceUnit.valueOf(str(map, "unit"))
                        : PriceUnit.PER_1K_TOKENS;
        BigDecimal input = decimal(map, "inputPricePerUnit");
        BigDecimal output = decimal(map, "outputPricePerUnit");
        BigDecimal reasoning =
                map.containsKey("reasoningPricePerUnit")
                        ? decimal(map, "reasoningPricePerUnit")
                        : null;
        BigDecimal cached =
                map.containsKey("cachedInputPricePerUnit")
                        ? decimal(map, "cachedInputPricePerUnit")
                        : null;
        LocalDate effectiveFrom =
                map.containsKey("effectiveFrom")
                        ? toLocalDate(map.get("effectiveFrom"))
                        : LocalDate.of(2026, 1, 1);
        PriceSource source =
                map.containsKey("source")
                        ? PriceSource.valueOf(str(map, "source"))
                        : PriceSource.OFFICIAL;
        return new PriceEntry(
                providerId,
                modelId,
                currency,
                unit,
                input,
                output,
                reasoning,
                cached,
                effectiveFrom,
                source);
    }

    /**
     * 把 YAML 中的日期值转成 {@link LocalDate}。
     *
     * <p>⚠️ 这个方法存在的原因是一个真实踩过的坑：SnakeYAML 会把 {@code 2026-01-01} 这类 无引号日期解析成 {@link
     * java.util.Date}（{@code "Thu Jan 01 08:00:00 CST 2026"}）， 而不是 String。直接 {@code
     * String.valueOf(...)} 再 {@code LocalDate.parse(...)} 会抛 {@code
     * DateTimeParseException}，且报错信息完全指不到真正原因。 用户在价格表里写一个再正常不过的日期就能让整个价格表加载失败 —— 因此必须在这里显式处理。
     *
     * @param value YAML 中的原始值
     * @return LocalDate
     * @throws IllegalArgumentException 类型无法识别时
     */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof java.time.LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        String text = String.valueOf(value).trim();
        // Date.toString() 是 "Thu Jan 01 08:00:00 CST 2026" 这种格式，取末尾的 yyyy-MM-dd
        java.util.regex.Matcher isoMatcher =
                java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(text);
        if (isoMatcher.find()) {
            return LocalDate.of(
                    Integer.parseInt(isoMatcher.group(1)),
                    Integer.parseInt(isoMatcher.group(2)),
                    Integer.parseInt(isoMatcher.group(3)));
        }
        return LocalDate.parse(text);
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static BigDecimal decimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("价格条目缺少必填字段：" + key);
        }
        return new BigDecimal(String.valueOf(value).trim());
    }
}
