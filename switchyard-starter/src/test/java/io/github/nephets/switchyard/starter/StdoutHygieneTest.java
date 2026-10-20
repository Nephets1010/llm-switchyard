/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主源码零标准流直写的持续门禁。
 *
 * <p>命令结果与运行日志是两件事：结果只能从 {@code CliConsole} 出，日志走 SLF4J。 混在一起的代价本仓付过一次 —— 有人把命令入口的输出改成 logger，而
 * Spring Boot 默认把日志也写到 stdout， 日志行混进命令结果，CI 里按行解析配额表与审计表格的断言当天全红，那次改动当天回退（4fe6494）。
 * 这条守卫就是为了让「下次想这么改」变成一次构建失败，而不是一次线上事故。
 */
@DisplayName("主源码零标准流直写")
class StdoutHygieneTest {

    /** 家族里全部含主源码的模块，扫描范围以它为准。 */
    private static final List<String> MODULES =
            List.of(
                    "switchyard-core",
                    "switchyard-provider",
                    "switchyard-starter",
                    "switchyard-mock-llm",
                    "switchyard-sample");

    /**
     * 唯一白名单：CLI 命令结果的出口。
     *
     * <p>为什么只有它能进白名单：它是产品输出而非日志，改用 logger 会被 Boot 的默认配置带回 stdout， 从而污染命令结果的机器解析。文件里那两行
     * 「现取当前流」的写法也是刻意保留的 —— 测试与嵌入式场景会在运行期换掉标准流，缓存成静态字段会让输出仍写向旧流。
     */
    private static final String WHITELIST =
            "switchyard-starter/src/main/java/io/github/nephets/switchyard/starter/shell/CliConsole.java";

    /**
     * 三条规则逐条罗列，不写 {@code System\.} 这种兜底。
     *
     * <p>为什么要逐条罗列：主源码里还有取时间戳的 8 处与退出进程的 1 处是正常用法， 兜底会把它们一次全报成违规，把人引去改没问题的代码 ——
     * 一次误报九处的门禁，很快就会被当成噪音关掉。
     *
     * <p>为什么用字符串片段拼而不是写成一个正则字面量：拼出来的正则在源码里不成整词， 将来有人把扫描范围扩到测试源码时，守卫不会被自己的正则字面量命中。
     */
    private static final Pattern DIRECT_STREAM_WRITE =
            Pattern.compile("System" + "\\s*\\.\\s*" + "(out|err)" + "\\b");

    private static final Pattern PRINT_STACK_TRACE = Pattern.compile("printStackTrace" + "\\s*\\(");

    private static final Pattern REPLACE_SYSTEM_STREAM =
            Pattern.compile("System" + "\\s*\\.\\s*" + "(setOut|setErr)" + "\\s*\\(");

    /** 白名单文件允许的那一种行：把当前标准流交出去，不写内容。 */
    private static final Pattern STREAM_ACCESSOR_LINE =
            Pattern.compile("return\\s+" + "System" + "\\s*\\.\\s*" + "(out|err)" + "\\s*;");

    /** 向上找仓库根的层数上限：够覆盖「模块目录 → 仓库根」，又不至于一路找到磁盘根。 */
    private static final int MAX_LOOKUP_DEPTH = 6;

    private static final String SOURCE_SUFFIX = ".java";

    @Test
    @DisplayName("扫描范围非空：五个模块的主源码目录都得在，且各扫到至少一份源码")
    void scanningScopeIsNotEmpty() throws IOException {
        for (String module : MODULES) {
            Path sourceRoot = mainJavaRoot(module);
            assertThat(Files.isDirectory(sourceRoot))
                    .as("模块主源码目录不存在：%s。目录没了会让下面几条「零命中」变成假绿", sourceRoot)
                    .isTrue();
            assertThat(javaFiles(sourceRoot))
                    .as("模块 %s 扫到的主源码文件数为 0，说明路径算错了，此时「零违规」没有意义", module)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("主源码除 CliConsole 白名单外，不得直写 stdout / stderr")
    void mainSourcesDoNotWriteToStdoutDirectly() throws IOException {
        assertThat(collectHits(DIRECT_STREAM_WRITE, true)).as("白名单之外直写标准流的位置（文件:行号:原文）").isEmpty();
    }

    @Test
    @DisplayName("主源码不得调用 printStackTrace（无白名单例外）")
    void mainSourcesDoNotCallPrintStackTrace() throws IOException {
        assertThat(collectHits(PRINT_STACK_TRACE, false))
                .as("调用 printStackTrace 的位置（文件:行号:原文）：异常要交给 logger，不要丢给 stderr")
                .isEmpty();
    }

    @Test
    @DisplayName("主源码不得替换标准流（setOut / setErr），换流只能发生在测试里")
    void mainSourcesDoNotReplaceSystemStreams() throws IOException {
        assertThat(collectHits(REPLACE_SYSTEM_STREAM, false))
                .as("替换标准流的位置（文件:行号:原文）：产品代码换掉全局流会连宿主的日志一起改道")
                .isEmpty();
    }

    @Test
    @DisplayName("白名单出口的形状锁定：CliConsole 剔除注释后碰标准流的行有且仅有两行，且都只是把流交出去")
    void whitelistedSeamKeepsExactlyTwoAccessorLines() throws IOException {
        Path seam = locateRepoRoot().resolve(WHITELIST);
        assertThat(Files.isRegularFile(seam))
                .as("白名单文件不见了：%s。要么被改名要么被挪走，白名单得跟着改，不能留一条永远匹配不上的规则", seam)
                .isTrue();

        List<String> streamLines = hitsIn(seam, DIRECT_STREAM_WRITE);

        assertThat(streamLines)
                .as("CliConsole 剔除注释后碰标准流的行；多一行说明出口被加了别的东西，该单独评审")
                .hasSize(2)
                .allSatisfy(
                        hit ->
                                assertThat(hit)
                                        .as("这一行只该是把当前流交出去，不该顺手写内容")
                                        .containsPattern(STREAM_ACCESSOR_LINE.pattern()));
    }

    /**
     * 按正则扫全部模块的主源码。
     *
     * @param pattern 规则
     * @param skipWhitelistedFile 是否整份跳过白名单文件
     * @return 每条形如 {@code 模块/src/main/java/.../X.java:行号:原文}
     */
    private static List<String> collectHits(Pattern pattern, boolean skipWhitelistedFile)
            throws IOException {
        List<String> hits = new ArrayList<>();
        for (String module : MODULES) {
            for (Path file : javaFiles(mainJavaRoot(module))) {
                if (skipWhitelistedFile && WHITELIST.equals(relativize(file))) {
                    continue;
                }
                hits.addAll(hitsIn(file, pattern));
            }
        }
        return hits;
    }

    /**
     * 单文件内按正则找命中。
     *
     * <p>注释行启发式<b>只对白名单文件生效</b>：CliConsole 的 Javadoc 本来就在解释「为什么要现取标准流」， 里边满是那几个词，不剔除会让白名单形同虚设。
     * 其余文件一律逐行实扫 —— 把启发式推广到全量扫描，等于给「在注释里写一句就绕过」开了口子。
     */
    private static List<String> hitsIn(Path file, Pattern pattern) throws IOException {
        boolean whitelisted = WHITELIST.equals(relativize(file));
        List<String> hits = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (whitelisted && isCommentLine(line)) {
                continue;
            }
            if (pattern.matcher(line).find()) {
                hits.add(relativize(file) + ":" + (i + 1) + ": " + line);
            }
        }
        return hits;
    }

    /** 白名单文件里的注释行：行注释与 Javadoc / 块注释的续行。 */
    private static boolean isCommentLine(String trimmedLine) {
        return trimmedLine.startsWith("//")
                || trimmedLine.startsWith("/*")
                || trimmedLine.startsWith("*");
    }

    private static List<Path> javaFiles(Path sourceRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(SOURCE_SUFFIX))
                    .sorted()
                    .toList();
        }
    }

    private static Path mainJavaRoot(String module) {
        return locateRepoRoot().resolve(module).resolve("src/main/java");
    }

    /** 相对仓库根的 POSIX 路径：Windows 上的反斜杠会让白名单比对永远不相等。 */
    private static String relativize(Path file) {
        return locateRepoRoot().relativize(file).toString().replace('\\', '/');
    }

    /**
     * 定位仓库根：从当前工作目录逐级向上找，最多 {@link #MAX_LOOKUP_DEPTH} 层。
     *
     * <p>为什么要逐级向上找而不是写死 {@code ..}：surefire 的工作目录是模块目录还是 reactor 根，取决于 pom 里 workingDirectory
     * 的配置与命令行参数，写死的相对路径会在其中一种情况下指到一个不存在的目录， 扫描静默返回空 —— 那样「零违规」是一条骗人的绿。
     *
     * <p>为什么找不到就直接抛而不是「跳过」：扫不到就跳过等于把门禁变成装饰。 这里用 AssertionError 让用例失败，并把起点绝对路径写进消息，方便定位。
     *
     * <p>为什么只向上找、不做递归搜索：构建副本里存在嵌套的同名仓库目录，递归会把那份也扫进来。
     */
    private static Path locateRepoRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        Path current = start;
        for (int depth = 0; depth <= MAX_LOOKUP_DEPTH && current != null; depth++) {
            boolean hasCore = Files.isDirectory(current.resolve("switchyard-core/src/main/java"));
            boolean hasStarter =
                    Files.isDirectory(current.resolve("switchyard-starter/src/main/java"));
            if (hasCore && hasStarter) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "定位不到仓库根：从 "
                        + start
                        + " 向上 "
                        + MAX_LOOKUP_DEPTH
                        + " 层内，没有同时含 switchyard-core 与 switchyard-starter 主源码的目录");
    }
}
