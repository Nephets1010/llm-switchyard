# 架构说明

## 一句话

`llm-switchyard` 是一个**跑在你 Spring Boot 进程内**的 LLM 网关：
它把「多供应商统一适配 / Token 精确计量 / 配额预留-对账-熔断 / 语义化重试与降级 / 审计留痕」
五件事织进一个标准 `ChatModel` 装饰器，业务代码只改一行。

## 模块与依赖

```
switchyard-core        纯逻辑，零 Spring 依赖（编译期只有一个 snakeyaml）
    ↑                  Usage 归一化 / 成本计算 / 配额 / 熔断 / TraceContext / 审计事件
switchyard-provider    3 个协议适配器 + 9 家供应商（自研 wire DTO）
    ↑
switchyard-starter    Spring Boot 自动装配：装饰器、控制台端点、CLI
    ↑                  （business 应用依赖这一层）
```

依赖方向严格单向，无环。CI 用 `enforcer` 的 `bannedDependencies` 禁止
`agentswitchyard` / `glasshouse` / `langgraph4j`（决策 4：不共享 jar，只共享规范）。

## 一次调用的完整链路

```
业务代码 → ChatClient → ChatModel 装饰器（RoutedChatModel）
  │
  ├─ 1. 取/建 traceId（W3C TraceContext，虚拟线程安全）
  │
  ├─ 2. 路由决策：StaticRoutingPolicy 选出主模型 + 有序降级链
  │
  ├─ 3. 配额预留（请求前）── 预留量 = estimator.estimate(prompt) + max_tokens
  │     └─ 不足 → ★ fail-soft：返回 SW_QUOTA_EXCEEDED(429)，不抛异常、不打上游
  │
  ├─ 4. 熔断许可（滑动窗口失败率）
  │     └─ 已熔断 → 直接快速失败，跳到降级
  │
  ├─ 5. 语义化重试（RetryExecutor）
  │     ├─ 可重试：429 / 5xx / 超时（指数退避 + 抖动）
  │     └─ 不可重试：4xx（同样的非法参数重试 100 次还是非法）
  │
  ├─ 6. 调用 provider 适配器 → 拿回 RawUsage
  │     └─ ★ 流式：注入 stream_options.include_usage=true，捕获末尾 usage 块
  │
  ├─ 7. 归一化（USG-001 七步算法，total 一律本仓重算）
  │
  ├─ 8. 配额结算（多退少补）→ 成本计算（PRC-001）→ 写审计（EVT-001）
  │
  └─ 9. 返回 + 响应头
        X-Switchyard-Trace-Id / Cost-Cny / Quota-Remaining / Total-Tokens / Usage-Source
```

失败时：第 6 步抛出 → 释放预留（否则一次 5xx 会永久吃掉一份配额）→ 记录熔断器 →
降级链取下一个模型 → 全部失败则抛 `SW_MODEL_UNAVAILABLE`。

## 为什么 core 零 Spring 依赖

这条边界不是洁癖，而是一个**可被测试的承诺**：

| 层 | 依赖 Spring 内部 API？ | 升到 Boot 4.1 + Spring AI 2.x |
|---|---|---|
| core | **完全不依赖** | **零影响** |
| provider 的 usage 解析 | 只用自己的 wire DTO | 零影响 |
| provider 的 ChatModel 适配 | 依赖 | 需改（~8 文件） |
| starter 自动装配 | 依赖 | 需改（~7 文件） |

CI 的 `architecture-boundaries` job 执行
`mvn -pl switchyard-core dependency:list`，出现 `org.springframework` 即失败。
详见 [ADR-0005](adr/0005-upgrade-path.md)。

## 三个适配器如何覆盖 9 家供应商

| 协议 | 适配器 | 覆盖 |
|---|---|---|
| OpenAI 兼容 | `OpenAiCompatibleProvider` | openai / 通义 / 智谱 / DeepSeek / Kimi / 豆包 / 硅基流动（7 家） |
| Anthropic 原生 | `AnthropicProvider` | anthropic |
| Ollama 原生 | `OllamaProvider` | ollama |

这三家**必须**独立适配的协议差异：

- **OpenAI 系**：流式增量在 `choices[].delta.content`（非流式在 `.message.content`）；
  `tool_calls` 挂在 `message` 内部；usage 有 `prompt_tokens_details` / `completion_tokens_details` 两层嵌套。
- **Anthropic**：`system` 是顶层独立字段；缓存用 `cache_creation_input_tokens` + `cache_read_input_tokens` 双字段；
  流式 usage 分两处到达（`message_start` + `message_delta`）；SSE 用 `event:` + `data:` 成对表示。
- **Ollama**：字段名与 OpenAI **相反**（`prompt_eval_count` / `eval_count`）；
  流式是 **ndjson**（裸 JSON，无 `data:` 前缀）而非 SSE。

## 计量的两条铁律

1. **`totalTokens` 一律本仓重算**（`input + output + reasoning`），绝不拷贝上游。
   理由：各家对 total 的口径不同，拷贝它等于把供应商的计费算法变更风险转嫁给自己的对账。
2. **缓存 token 不重复计费**：成本公式第一步先把 `cachedInputTokens` 从 input 里扣出来，
   按折扣价单算。写错这一步会导致同一段 token 按全价算两遍。

实测：10 万条构造流量误差 **0.363%**（种子 `20261009`，可复现），
流式计量为 0 的条数 **0**。

## 扩展点（首版已留好，P1 直接实现即可）

| SPI | 用途 | P1 计划 |
|---|---|---|
| `QuotaStore` | 多实例配额协调（Redis 令牌桶） | P2 |
| `TokenEstimator` | 换成 tiktoken 提升估算精度 | P1 |
| `RoutingPolicy` | 成本/延迟/质量三维动态路由 | P1 |
| `AuditSink` | 把审计接到 ClickHouse / Kafka / OTLP | P1 |
| `ChatProvider` | 新增供应商 | 随时 |
| `ErrorClassifier` | 按供应商定制可重试性 | 随时 |
