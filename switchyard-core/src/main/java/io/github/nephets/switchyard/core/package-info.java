/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */

/**
 * LLM 道岔 · 纯逻辑核心层。
 *
 * <p>本包及其子包内的全部类型都是纯 POJO / record / 枚举，<b>不依赖任何 Spring 框架类型</b> （见 ADR-0005）。这不是洁癖，而是一条可机器校验的架构边界：
 *
 * <ul>
 *   <li>框架升级（Boot 3.5 → 4.1、Spring AI 1.1 → 2.x）时，改动被限制在 {@code provider} 与 {@code starter} 两个模块约
 *       15 个文件内，core 与全部契约测试一行不动；
 *   <li>core 里的计量、配额、熔断逻辑可以在不启动 Spring 容器的前提下被毫秒级单测覆盖， 而这正是「计量精度」这个护城河能被反复验证的前提；
 *   <li>CI 用依赖树扫描本模块，出现 Spring 框架类型即构建失败。
 * </ul>
 *
 * <p>子包职责：
 *
 * <ul>
 *   <li>{@code trace} —— W3C TraceContext（契约 CTX-001）
 *   <li>{@code usage} —— Token 计量归一化（契约 USG-001，本项目最关键的契约）
 *   <li>{@code pricing} —— 价格表与成本计算（契约 PRC-001）
 *   <li>{@code quota} —— 配额预留-对账（请求前预留、请求后结算、多退少补）
 *   <li>{@code resilience} —— 语义化重试、降级链、熔断
 *   <li>{@code routing} —— 路由策略 SPI 与首发静态实现
 *   <li>{@code provider} —— 供应商目录与能力矩阵
 *   <li>{@code audit} —— 审计事件（契约 EVT-001）
 * </ul>
 */
package io.github.nephets.switchyard.core;
