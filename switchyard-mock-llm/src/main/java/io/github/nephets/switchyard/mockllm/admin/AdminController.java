/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.mockllm.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端点：切换场景、重置、查看统计。
 *
 * <p><b>暴露这些端点是为了让 demo 可交互</b>：README 里可以写 「先切到 rate-limit 场景，再调一次接口，观察网关如何自动重试成功」，
 * 读者能<b>亲手</b>看到重试与降级，而不是只相信一段文字。
 */
@RestController
@RequestMapping("/__admin")
public class AdminController {

    private final ScenarioEngine scenarios;

    /**
     * 构造。
     *
     * @param scenarios 场景引擎
     */
    public AdminController(ScenarioEngine scenarios) {
        this.scenarios = scenarios;
    }

    /** 健康检查（docker-compose 的 healthcheck 依赖它）。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return scenarios.health();
    }

    /**
     * 切换场景。
     *
     * @param body 形如 {@code {"scenario":"rate-limit"}}
     * @return 切换后的场景
     */
    @PostMapping("/scenario")
    public Map<String, Object> switchScenario(@RequestBody Map<String, String> body) {
        String name = body.get("scenario");
        Scenario scenario = scenarios.switchTo(name);
        return Map.of("scenario", scenario.name(), "description", scenario.description());
    }

    /** 列出全部可用场景。 */
    @GetMapping("/scenarios")
    public Map<String, Scenario> listScenarios() {
        return scenarios.registered();
    }

    /** 重置为正常场景并清空统计。 */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        scenarios.reset();
        return Map.of("status", "reset", "scenario", scenarios.current().name());
    }

    /** 统计信息。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "scenario", scenarios.current().name(),
                "requestCount", scenarios.requestCount(),
                "errorCount", scenarios.errorCount());
    }
}
