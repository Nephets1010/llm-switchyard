/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.starter.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CliConsole} 的行为。
 *
 * <p>重点是「运行期替换流之后输出立即改道」这一组：starter 的 CLI 测试与嵌入式场景都靠 {@code System.setOut}
 * 捕获命令结果，一旦这里退化成静态缓存的流，断言就会静默失效 —— 而且失效的形状很像偶发。
 */
@DisplayName("CliConsole：命令结果的统一出口")
class CliConsoleTest {

    @Test
    @DisplayName("println() 写一个空行")
    void printlnWithoutTextWritesEmptyLine() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            CliConsole.println();
        } finally {
            System.setOut(saved);
        }

        assertThat(buffer.toString(StandardCharsets.UTF_8)).isEqualTo(System.lineSeparator());
    }

    @Test
    @DisplayName("println(String) 原样写出一行")
    void printlnWithTextWritesItVerbatim() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            CliConsole.println("结论：未发现问题");
        } finally {
            System.setOut(saved);
        }

        assertThat(buffer.toString(StandardCharsets.UTF_8))
                .isEqualTo("结论：未发现问题" + System.lineSeparator());
    }

    @Test
    @DisplayName("print(String) 原样写出，不追加换行")
    void printWritesTextAsIs() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            CliConsole.print("openai/gpt-5-mini");
            CliConsole.print("|");
        } finally {
            System.setOut(saved);
        }

        assertThat(buffer.toString(StandardCharsets.UTF_8)).isEqualTo("openai/gpt-5-mini|");
    }

    @Test
    @DisplayName("printf 只在格式串带 %n 时换行，不会自动补")
    void printfHonoursFormatStringOnly() {
        ByteArrayOutputStream withNewline = new ByteArrayOutputStream();
        ByteArrayOutputStream withoutNewline = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(withNewline, true, StandardCharsets.UTF_8));
            CliConsole.printf("%s-%d%n", "openai", 7);

            System.setOut(new PrintStream(withoutNewline, true, StandardCharsets.UTF_8));
            CliConsole.printf("%s-%d", "openai", 7);
        } finally {
            System.setOut(saved);
        }

        assertThat(withNewline.toString(StandardCharsets.UTF_8))
                .isEqualTo("openai-7" + System.lineSeparator());
        assertThat(withoutNewline.toString(StandardCharsets.UTF_8)).isEqualTo("openai-7");
    }

    @Test
    @DisplayName("printf 的宽度、右对齐与百分号转义与真实格式串一致")
    void printfHonoursWidthAndNewline() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            CliConsole.printf("%-34s %12s %.0f%%%n", "openai/gpt-5-mini", "0.001234", 99.6);
            CliConsole.printf("%7.1f%%%n", 12.345);
        } finally {
            System.setOut(saved);
        }

        assertThat(buffer.toString(StandardCharsets.UTF_8))
                .isEqualTo(
                        "openai/gpt-5-mini                      0.001234 100%"
                                + System.lineSeparator()
                                + "   12.3%"
                                + System.lineSeparator());
    }

    @Test
    @DisplayName("运行期替换 System.out 后，输出立即改道到新流")
    void followsSystemOutReplacedAtRuntime() {
        ByteArrayOutputStream before = new ByteArrayOutputStream();
        ByteArrayOutputStream after = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(before, true, StandardCharsets.UTF_8));
            CliConsole.println("换流之前");

            System.setOut(new PrintStream(after, true, StandardCharsets.UTF_8));
            CliConsole.println("换流之后");
        } finally {
            System.setOut(saved);
        }

        assertThat(before.toString(StandardCharsets.UTF_8))
                .isEqualTo("换流之前" + System.lineSeparator());
        assertThat(after.toString(StandardCharsets.UTF_8))
                .isEqualTo("换流之后" + System.lineSeparator());
    }

    @Test
    @DisplayName("out() 返回的就是当前的 System.out")
    void outResolvesStreamOnEachCall() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream replacement = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        PrintStream saved = System.out;
        try {
            System.setOut(replacement);

            assertThat(CliConsole.out()).isSameAs(replacement);
            assertThat(CliConsole.out()).isSameAs(System.out);
        } finally {
            System.setOut(saved);
        }

        assertThat(CliConsole.out()).isSameAs(saved);
    }

    @Test
    @DisplayName("errPrintln 写进 stderr，stdout 一个字节都不碰")
    void errPrintlnWritesToStderrWithoutTouchingStdout() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            CliConsole.errPrintln("配额存储未装配，无法设置配额");
        } finally {
            System.setOut(savedOut);
            System.setErr(savedErr);
        }

        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .isEqualTo("配额存储未装配，无法设置配额" + System.lineSeparator());
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("err() 每次现取 System.err，运行期替换后立即改道")
    void errResolvesStreamOnEachCall() {
        ByteArrayOutputStream before = new ByteArrayOutputStream();
        ByteArrayOutputStream after = new ByteArrayOutputStream();
        PrintStream savedErr = System.err;
        try {
            System.setErr(new PrintStream(before, true, StandardCharsets.UTF_8));
            assertThat(CliConsole.err()).isSameAs(System.err);
            CliConsole.errPrintln("换流之前");

            System.setErr(new PrintStream(after, true, StandardCharsets.UTF_8));
            assertThat(CliConsole.err()).isSameAs(System.err);
            CliConsole.errPrintln("换流之后");
        } finally {
            System.setErr(savedErr);
        }

        assertThat(before.toString(StandardCharsets.UTF_8))
                .isEqualTo("换流之前" + System.lineSeparator());
        assertThat(after.toString(StandardCharsets.UTF_8))
                .isEqualTo("换流之后" + System.lineSeparator());
        assertThat(CliConsole.err()).isSameAs(savedErr);
    }
}
