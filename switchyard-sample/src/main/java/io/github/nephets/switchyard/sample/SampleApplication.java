/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 可运行 demo：同步对话 / 流式对话 / 并发配额拒绝三个接口，全部指向 mock-llm。
 *
 * <p><b>零 API Key 是硬约束</b>：README 承诺「30 秒上手」，而任何需要先去申请密钥的项目 都会在第一步就流失掉大部分读者。所以 demo 刻意不连任何真实供应商 ——
 * 想看真实行为时，用户改一行 {@code base-url} 与 {@code api-key} 即可，业务代码不动。
 */
@SpringBootApplication
public class SampleApplication {

    /**
     * 入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
