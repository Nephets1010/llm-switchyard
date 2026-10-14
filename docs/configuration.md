# 配置参考

所有配置项在 `llm.switchyard` 下。带 ★ 的是会影响钱、可用性或合规的项，强烈建议显式设置。

## 最小可用配置

```yaml
llm:
  switchyard:
    routes:
      default:
        primary: openai/gpt-5-mini
        fallbacks: [deepseek/deepseek-chat]
    providers:
      openai:
        api-key: ${OPENAI_API_KEY}
```

只有这两段，网关即可工作。其余项全部有默认值。

## 路由

| 配置项 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `llm.switchyard.enabled` | boolean | `true` | 关掉后 starter 完全不装配，业务回到原生 `ChatModel` |
| `llm.switchyard.default-route` | string | `default` | `router.route()` 不带参数时使用的路由名 |
| `llm.switchyard.routes.<name>.primary` | string | — | **必填**。格式 `providerId/modelId` |
| `llm.switchyard.routes.<name>.fallbacks` | list | `[]` | 有序降级链。**顺序即策略**：便宜的放前面 |

**降级链怎么排**：把「更便宜但更慢」的放前面，把「更贵但更可靠」的放后面。
主模型熔断时不会逐个试失败 —— `StaticRoutingPolicy` 会直接选第一个可用的，
省掉一次必然失败的 RTT。

## 供应商

| 配置项 | 默认 | 说明 |
|---|---|---|
| `llm.switchyard.providers.<id>.api-key` | — | ★ **用环境变量注入，不要写进文件**。本地 Ollama 留空 |
| `llm.switchyard.providers.<id>.base-url` | 内置目录值 | 代理、私有网关、区域端点 |
| `llm.switchyard.providers.<id>.timeout` | `60s` | 整体请求超时 |
| `llm.switchyard.providers.<id>.enabled` | `true` | 设为 `false` 可临时下线某家 |

可用 `<id>`：`openai` / `anthropic` / `ollama` / `dashscope`（通义）/ `deepseek` /
`zhipu`（智谱）/ `moonshot`（Kimi）/ `doubao`（豆包）/ `siliconflow`（硅基流动）。

## 计量

| 配置项 | 默认 | 说明 |
|---|---|---|
| `llm.switchyard.metering.cjk-per-token` | `1.0` | 一个中文/日文/韩文字符折算几个 token |
| `llm.switchyard.metering.ascii-chars-per-token` | `4.0` | 几个 ASCII 字符折算一个 token |
| `llm.switchyard.metering.estimated-tokens-per-request` | `1000` | 无法预估 prompt 时的兜底预留量 |

★ **默认估算器是字符启发式，不是分词器**。它的定位是「兜底 + 显式标注」，
结果会带 `usageSource=ESTIMATED`。需要更高精度时实现 `TokenEstimator` 接口
并注册为 Bean 即可（可接 tiktoken）。

## 配额

| 配置项 | 默认 | 说明 |
|---|---|---|
| `llm.switchyard.quota.enabled` | `true` | 关掉后所有请求放行（仅计量） |
| `llm.switchyard.quota.default-tenant` | `_default` | 未从 baggage 读到租户时的归属 |
| `llm.switchyard.quota.on-exceeded` | `FAIL_SOFT` | `FAIL_SOFT` 返回 429；`TRIP_BREAKER` 额外熔断 |
| `llm.switchyard.quota.reservation-ttl` | `10m` | 超时未结算的预留会被自动回收，防止额度泄漏 |

配额上限本身**不在这里配**（它是运行期状态），用 CLI 或控制台端点设置：

```bash
switchyard quota set --tenant vip --model openai/gpt-5-mini --limit 50000 --period MONTHLY
```

**首版是单实例的**。多实例部署时各自计数，总量会超限 —— 需要时请自行实现
`QuotaStore` SPI（接口已留）。这是明确写进 README「不做什么」的取舍。

## 重试

| 配置项 | 默认 | 说明 |
|---|---|---|
| `llm.switchyard.retry.enabled` | `true` | 关掉后第一次失败即返回 |
| `llm.switchyard.retry.max-attempts` | `3` | 总尝试次数（含首次） |
| `llm.switchyard.retry.initial-backoff` | `200ms` | 首次退避 |
| `llm.switchyard.retry.max-backoff` | `2s` | 退避上限 |
| `llm.switchyard.retry.multiplier` | `2.0` | 指数退避的倍数 |
| `llm.switchyard.retry.jitter` | `0.2` | 抖动比例。**不要设 0**，否则并发请求会同时重试形成风暴 |

可重试判定硬编码在 `ErrorCode.retryable()` 里，不由配置控制：

| 上游响应 | 可重试 | 理由 |
|---|---|---|
| 429 | ✅ | 退避后大概率能过 |
| 5xx / 超时 | ✅ | 可能是瞬时抖动 |
| 400 / 401 / 403 / 404 | ❌ | 同样的请求重试 100 次结果一样，只是白烧配额 |
| 422 | ❌ | 同样的内容必然同样被内容审核拦下 |

## 熔断

| 配置项 | 默认 | 说明 |
|---|---|---|
| `llm.switchyard.circuit-breaker.enabled` | `true` | |
| `llm.switchyard.circuit-breaker.window-size` | `20` | 滑动窗口内保留最近 N 次调用 |
| `llm.switchyard.circuit-breaker.failure-threshold` | `0.5` | 窗口内失败率达到该比例即打开 |
| `llm.switchyard.circuit-breaker.open-duration` | `30s` | 打开后多久转半开（失败则退避翻倍，上限 10 分钟） |

熔断粒度是**单个模型**而不是整个供应商：一个供应商的某模型被限流，
不代表它的其他模型也不可用。

## 审计

| 配置项 | 默认 | 说明 |
|---|---|---|
| `llm.switchyard.audit.type` | `LOGGING` | `LOGGING` / `RINGBUFFER` / `NONE` |
| `llm.switchyard.audit.capture-payload` | `false` | ★ **保持 false**。落 prompt 原文有 PII 泄露风险 |
| `llm.switchyard.audit.ring-buffer-capacity` | `1000` | 仅 `RINGBUFFER` 生效 |

审计走**独立的 logger**（`switchyard.audit`），不混进业务日志 ——
审计需要能被单独采集、单独脱敏、单独留存。

审计事件**默认只落摘要**（`payloadDigest = sha256:...`），不落原文。
需要留痕时开启 `capture-payload` 并自行完成脱敏。

## 控制台

| 配置项 | 默认 | 端点 |
|---|---|---|
| `llm.switchyard.console.enabled` | `true` | |
| `llm.switchyard.console.base-path` | `/_switchyard` | |

| 端点 | 返回 |
|---|---|
| `GET /_switchyard/overview` | `cost` / `tokens` / `circuitBreakers` / `totalCalls` |
| `GET /_switchyard/quota` | 各配额的 used / limit / remaining / utilization |
| `GET /_switchyard/models` | 供应商能力矩阵 + 模型健康 |

**首版不做 SPA**。数据以稳定 JSON 暴露，让用户接进现有监控/BI；
前端放 P1，届时 JSON 契约已稳定，重做 UI 不会返工。

## 价格表

内置表在 `switchyard-core/src/main/resources/prices/switchyard-prices.yml`。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `llm.switchyard.pricing-file` | 空 | 外部价格表路径；配置后热更新生效 |
| `llm.switchyard.pricing-refresh-interval-ms` | `300000` | 热更新间隔（5 分钟） |

⚠️ **内置价格表多数条目标为 `source: ESTIMATED`（社区估算），控制台会显式标注。**
请以你自己的供应商账单为准，或提供自己的价格表：

```yaml
version: 1
prices:
  - providerId: openai
    modelId: gpt-5-mini
    currency: CNY
    unit: PER_1K_TOKENS
    inputPricePerUnit: 0.0014
    outputPricePerUnit: 0.0056
    cachedInputPricePerUnit: 0.00028
    effectiveFrom: 2026-01-01
    source: OFFICIAL
```

**热更新失败不会影响服务**：解析出错时保留旧表并告警。价格表出错的正确表现是
「成本按旧价记账 + 控制台告警」，而不是「网关启动失败」。

## 响应头

网关会在每个响应上写这些头，让业务方**不改任何代码**就能看到成本：

| 头 | 含义 |
|---|---|
| `X-Switchyard-Trace-Id` | 本次调用的 traceId，用于把报错与审计记录对上 |
| `X-Switchyard-Cost-Cny` | 本次成本（展示口径 scale=4） |
| `X-Switchyard-Quota-Remaining` | 剩余额度；不限额时不写 |
| `X-Switchyard-Total-Tokens` | 本次总 token（重算值） |
| `X-Switchyard-Usage-Source` | `VENDOR_REPORTED` / `ESTIMATED` / `FALLBACK` |

业务方已经在用 APM 采集 HTTP 响应头，这几个头会**自动**进入他们的成本看板。

## 虚拟线程

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

流式与并发请求不被线程数限制。core 的 `TraceContext` 用
`InheritableThreadLocal`，在虚拟线程下能正确继承（架构约定：禁止裸 `ThreadLocal`）。
