/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.shell;

import java.io.PrintStream;

/**
 * {@code switchyard} 各命令结果的唯一出口。
 *
 * <p><b>命令结果不是日志</b>：这层只负责把结果写到 stdout / stderr，不做任何 logger 调用。 曾经有人把命令入口的日志加上，结果 Spring Boot
 * 默认把日志也打到 stdout，日志行混进了命令输出， CI 里按行解析配额与审计表格的断言立刻失败，那次改动当天就被回退了。
 * 这个类的存在就是为了把「命令结果」和「运行日志」这两件事分开：结果从这里出，日志走 logger，谁也不许混。
 */
public final class CliConsole {

    private CliConsole() {}

    /**
     * 当前的标准输出流，供 picocli 这类需要 {@link PrintStream} 的 API 使用。
     *
     * <p>刻意每次调用都现取 {@code System.out}，不在类加载期缓存成静态字段： 测试与嵌入式场景都会在运行期用 {@code System.setOut}
     * 换掉标准输出，缓存引用会让输出仍写向旧流。 具体到本仓，{@code StarterInterfacesTest.quotaTable} 会在用例内部二次 {@code
     * System.setOut}（它自己另开缓冲， 为的是断言自包含）；一旦把流做成静态快照，这个用例必红 —— 而它红的形状还很像「偶发」。
     *
     * @return 调用时刻的 {@code System.out}
     */
    public static PrintStream out() {
        return System.out;
    }

    /**
     * 写一段文本，<b>不追加换行</b>。
     *
     * @param text 文本内容
     */
    public static void print(String text) {
        out().print(text);
    }

    /** 写一个空行。 */
    public static void println() {
        out().println();
    }

    /**
     * 写一行文本。
     *
     * @param line 文本内容
     */
    public static void println(String line) {
        out().println(line);
    }

    /**
     * 按格式串写出，参数与语义同 {@link String#format(String, Object...)}。
     *
     * <p>两个刻意的选择：
     *
     * <ul>
     *   <li><b>不指定 Locale</b>：传 {@code Locale.ROOT} 会改变数字与日期的排版（比如千分位、小数点）， 逐字节的 stdout 契约就变了。这里沿用
     *       {@code System.out.printf} 的默认行为。
     *   <li><b>不自动补换行</b>：是否换行由格式串里的 {@code %n} 决定。原有调用点的格式串已经自带 {@code %n}， 补一次就会多一个空行。
     * </ul>
     *
     * @param format 格式串
     * @param args 格式参数
     */
    public static void printf(String format, Object... args) {
        out().printf(format, args);
    }

    /**
     * 当前的错误输出流。
     *
     * <p>现取 {@code System.err} 的理由同 {@link #out()}：运行期可能被替换。
     *
     * @return 调用时刻的 {@code System.err}
     */
    public static PrintStream err() {
        return System.err;
    }

    /**
     * 向 stderr 写一行文本。
     *
     * <p>用在「命令没法正常完成、必须先把话说明白」的场合（比如参数格式不对）。 这些话<b>不走 logger</b>：宿主把日志级别调高就会把提示吞掉，
     * 而使用者看到的是一份「看起来正常」的输出。
     *
     * @param line 文本内容
     */
    public static void errPrintln(String line) {
        err().println(line);
    }
}
