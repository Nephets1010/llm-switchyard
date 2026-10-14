/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mock LLM 服务：CI 的假后端 + demo 的一键启动单元。
 *
 * <p><b>为什么这个模块必须存在</b>（且必须零 API Key）：
 *
 * <ul>
 *   <li>CI 若调用真实 API，就会在每次提交时产生费用，而且会因为网络抖动让构建变得不稳定；
 *   <li>README 里「30 秒上手」的承诺要求任何人 clone 后不用申请密钥就能跑通；
 *   <li>故障场景（429/500/超时/断流）无法在真实 API 上稳定复现 —— 而它们正是本项目最需要被验证的路径。
 * </ul>
 *
 * <p>因此本模块实现 OpenAI / Anthropic / Ollama 三套协议的端点，并内置 7 个可切换场景。
 */
@SpringBootApplication
public class MockLlmApplication {

    /**
     * 入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MockLlmApplication.class, args);
    }
}
