<div align="center">

# LLM Switchyard · LLM 道岔

**Java 原生 LLM 网关：按成本/延迟/质量智能路由，Token 精确计量与配额熔断，进程内部署不需 sidecar。**

*The Java-native LLM gateway that runs inside your Spring Boot app — route every call by cost, latency
and quality, meter every token, and never let a provider outage take your business down.*

[English](README.md) | **简体中文**

[![CI](https://github.com/Nephets1010/llm-switchyard/actions/workflows/ci.yml/badge.svg)](https://github.com/Nephets1010/llm-switchyard/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.8-brightgreen.svg)](https://spring.io/projects/spring-ai)

</div>

---

## 1. 一句话价值：省下的钱 + 可控的成本

> 你的账单里，有多少是**重复的 system prompt**、**流式请求的静默 0 记账**、和**没人知道归属的部门开销**？
>
> LLM 道岔把这三件事一次性解决：Token 精确计量（流式也不为 0）、按 token 计的配额熔断、按租户/业务线的成本归因。
> 它跑在**你自己的 Spring Boot 进程里**，数据不出内网，不需要额外部署一个 Python sidecar。

**关键词：节省 · 可控**

| 你的问题 | 道岔怎么解决 | 可验证的证据 |
|---|---|---|
| 账单只有供应商总额，无法分摊到部门 | 每个响应头带成本与 traceId，审计记录带租户/业务线维度 | `X-Switchyard-Cost-Cny` 响应头 + `/_switchyard/overview` |
| 流式请求的账单恒为 0（静默失真） | 强制注入 `include_usage`，收不到时回退估算并**显式标注** `ESTIMATED` | 10 万条流量误差 **0.363%**；流式计量为 0 的条数 **0** |
| 一个租户的爬虫把预算打光 | 请求**前**按 token 预留，超限返回 429 业务响应（不抛 5xx） | `InMemoryQuotaStoreTest`：100 线程并发抢 1000 token，恰好 10 个通过 |
| OpenAI 429 直接 500，值班同学半夜改配置 | 语义化重试（仅 429/5xx/超时）+ 熔断 + 有序降级链 | `RetryFallbackIT`：429×2 后成功；400 只发 1 次请求 |

---

## 2. 支持状态（请先读这一节）

> **Spring Boot 3.5 的 OSS 支持已于 2026-06-30 结束。本项目锁定的 3.5.16 是 3.5 线最后一个 OSS 补丁版本。**
>
> 我们仍然选择 3.5.x，理由与迁移路径如下（详见 [ADR-0005](docs/adr/0005-upgrade-path.md)）：

**为什么还锁 3.5.16**

- 目标用户是「5–30 人 Java 团队」，他们的存量服务绝大多数跑在 Boot 3.x 上。要求他们**先做一次全量框架迁移**才能试用一个网关，是获客杀手。
- Spring AI 2.0 只能配 Boot 4.x，而 Boot 4 的 starter 拆分与 Jackson 3 迁移成本会被转嫁给用户，用户拿到的只是「较新的版本号」。

**迁移路径（已按此设计，不是空话）**

| 层 | 是否依赖 Spring AI / Boot 内部 API | 升级到 Boot 4.1 + Spring AI 2.x 的影响 |
|---|---|---|
| `switchyard-core`（计量/成本/配额/熔断） | **完全不依赖** | **零影响**，一行不改 |
| `switchyard-provider` 的 usage 解析 | 只用自己的 wire DTO，不引用 `org.springframework.ai.*` | 零影响 |
| `switchyard-provider` 的 `ChatModel` 适配 | 依赖 `ChatModel` / `Prompt` / `ChatResponse` | 需改（约 8 个文件） |
| `switchyard-starter` 自动装配 | 依赖 Boot auto-configuration | 需改（约 7 个文件） |

> 换句话说：**升级时改动被限制在约 15 个文件内，core 与全部契约测试不动。**
> 这条边界由 CI 的 `architecture-boundaries` job 强制守护：core 的依赖树里出现 `org.springframework.*` 即构建失败。

---

## 3. 30 秒上手

**第 1 步：加依赖**

```xml
<dependency>
  <groupId>io.github.nephets</groupId>
  <artifactId>switchyard-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**第 2 步：改配置**（完整版见 [`application-switchyard.yml`](switchyard-starter/src/main/resources/application-switchyard.yml)）

```yaml
llm:
  switchyard:
    routes:
      default:
        primary: openai/gpt-5-mini
        fallbacks: [deepseek/deepseek-chat, dashscope/qwen-plus]
    providers:
      openai:
        api-key: ${OPENAI_API_KEY}
```

**第 3 步：业务代码只改这一行**

```java
@Bean
ChatClient chatClient(SwitchyardRouter router) {
  return ChatClient.builder(router.route("default")).build();
  //              ↑ ChatModel 的来源换了，其余零改动
}
```

**这就是全部。** 结构化输出、工具调用、流式能力**零损失** —— 因为 `router.route()` 返回的仍是标准 `ChatModel`，我们只在外面包了一层装饰器。

---

## 4. 计量精度：这是本项目的护城河

<div align="center">

| 构造流量 | 账单真值 token | 网关计量 token | **实测相对误差** | 上限 |
|---|---|---|---|---|
| **100,000 条** | 290,272,514 | 289,217,848 | **0.363337%** | 0.5% |
| 其中流式请求 | 33,334 条 | — | 流式计量为 0 的条数：**0** | 0 |

</div>

以上数字由 `MeteringAccuracyTest` 在每次 `mvn verify` 时实测产出，随机种子固定为 `20261009`，**任何人 clone 后跑出的结果完全一致**。报告文件：`switchyard-core/target/precision-report.md`。

**我们凭什么做到 0.36%**

1. **`totalTokens` 一律本仓重算**，绝不拷贝上游。原因：各家对 total 的口径不同（有的含 reasoning、有的只算 input+output），拷贝它等于把供应商的算法变更风险转嫁给你的对账。
2. **缓存 token 不重复计费**。`prompt_tokens_details.cached_tokens` 被完整解析，成本公式先把它从 input 里扣出来按折扣价单算。Spring AI 的默认解析器会**直接丢弃**这个字段 —— 我们因此自研了 wire 层。
3. **流式强制注入 `stream_options.include_usage=true`**。这是 PRD 痛点 4 的直接根因：OpenAI / vLLM 在未设该参数时**整条流不返回 usage 块**。
4. **收不到 usage 时不假装有**。回退到可替换的估算器，并把 `usageSource` 标成 `ESTIMATED`、`streamUsageReported` 标成 `false`，让它能被监控与告警。

**诚实声明**：内置价格表中除少数条目外均为 `source: ESTIMATED`（社区估算），控制台会显式标注。**请以你自己的供应商账单为准**，或接自己的 `switchyard-prices.yml`。我们禁止在代码里编造价格。

---

## 5. 核心能力（逐条打勾，附证据类名）

| 能力 | 状态 | 证据（测试类） |
|---|---|---|
| 多供应商统一适配（**3 个适配器 / 9 家**） | ✅ | `ProviderContractIT`（`@MethodSource` 同一模板跑 7 家 OpenAI 兼容供应商） |
| 进程内嵌入式 starter，零 sidecar | ✅ | `RoutedChatModelTest`、`SampleSmokeIT` |
| Token 精确计量（流式不为 0） | ✅ | `MeteringAccuracyTest`（误差 0.363%）、`StreamUsageIT` |
| 配额预留-对账（请求前拦截 + 多退少补） | ✅ | `InMemoryQuotaStoreTest`、`QuotaReservationSettlementTest` |
| 配额熔断 fail-soft（429 而非 5xx） | ✅ | `QuotaInterceptorIT` |
| 语义化重试（4xx 不重试 / 429·5xx·超时重试） | ✅ | `ErrorClassifierTest`、`RetryFallbackIT` |
| 熔断 + 有序降级链 | ✅ | `CircuitBreakerTest`（熔断期 100 次调用打上游 0 次） |
| 结构化审计（默认不落 prompt 原文） | ✅ | `AuditEventContractTest`、`AuditSinkIT` |
| 价格表热更新（改 yml 即生效，不重启） | ✅ | `PricingTableLoaderTest` |
| W3C TraceContext 贯穿 | ✅ | `W3CTraceCodecContractTest`、`ContractSchemaTest` |
| 控制台 JSON 端点 | ✅ | `SwitchyardConsoleControllerIT` |
| CLI（quota / audit / doctor） | ✅ | `DoctorCli`、`QuotaCli`、`AuditCli` |
| 语义化缓存 | ⏳ P1 | PRD P1 |
| 成本/延迟/质量三维动态路由 | ⏳ P1 | 首版为静态路由（`StaticRoutingPolicy`），接口已留好 |

---

## 6. 供应商支持矩阵

9 家供应商，**3 个协议适配器**。每一格都有对应测试类作为证据。

| 供应商 | providerId | 协议 | 流式 | 结构化输出 | 工具调用 | 精确计量 | 推理 token | 缓存命中 |
|---|---|---|---|---|---|---|---|---|
| OpenAI | `openai` | OpenAI 兼容 | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| 通义千问 | `dashscope` | OpenAI 兼容 | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| DeepSeek | `deepseek` | OpenAI 兼容 | ✅ | — | ✅ | ✅ | ✅ | ✅ |
| 智谱 GLM | `zhipu` | OpenAI 兼容 | ✅ | — | ✅ | ✅ | ✅ | — |
| Kimi / 月之暗面 | `moonshot` | OpenAI 兼容 | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| 豆包 / 火山方舟 | `doubao` | OpenAI 兼容 | ✅ | — | ✅ | ✅ | — | ✅ |
| 硅基流动 | `siliconflow` | OpenAI 兼容 | ✅ | ✅ | ✅ | ✅ | — | — |
| Anthropic | `anthropic` | Anthropic 原生 | ✅ | — | ✅ | ✅ | ✅ | ✅ |
| Ollama | `ollama` | Ollama 原生 | ✅ | — | ✅ | ✅ | — | — |

- `✅` = 由 `ProviderContractIT` / `AnthropicProviderIT` / `OllamaProviderIT` 覆盖并断言。
- `—` = 该供应商当前协议**不返回**此字段。此时 `reasoningTokens` 恒为 0 且 `reasoningEstimated=true`（我们不假装有数据）。
- **为什么 7 家共用一个适配器**：它们的 API 都是 OpenAI 兼容协议，唯一差别是 `base-url`。为每家写一个适配器意味着 6 份几乎相同的代码，收益极低而六份实现必然在细节上漂移。

---

## 7. 一键体验（零 API Key、零费用）

```bash
git clone https://github.com/Nephets1010/llm-switchyard.git
cd llm-switchyard
docker compose up --build
```

等两个服务就绪（约 60 秒），然后：

```bash
# ① 同步对话 —— 看计量与成本
curl -s -XPOST localhost:18080/demo/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"请用一句话介绍你自己"}' | jq

# ② 流式对话 —— ★ 最后一条数据的 totalTokens 一定 > 0
curl -sN -XPOST localhost:18080/demo/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"讲个笑话"}'

# ③ 控制台 —— 成本与 token 是真实数字，不是 0
curl -s localhost:18080/_switchyard/overview | jq

# ④ 并发配额 —— 20 个并发，配额上限 200 token，大部分被拒
curl -s "localhost:18080/demo/quota-demo?concurrency=20" | jq
```

**亲手验证重试与降级**（这是最能说明「故障路径真的被处理了」的一步）：

```bash
# 让 mock-llm 持续返回 429
curl -XPOST localhost:18081/__admin/scenario -d '{"scenario":"rate-limit"}'
# 再调一次 demo —— 网关会自动重试并最终失败，日志里有 retry 记录

# 切成「前 2 次 429，第 3 次成功」—— 网关会自动重试成功
curl -XPOST localhost:18081/__admin/scenario -d '{"scenario":"rate-limit-retryable"}'

# 恢复
curl -XPOST localhost:18081/__admin/reset
```

**本地不用 Docker 跑**：

```bash
./mvnw -pl switchyard-mock-llm spring-boot:run    # 终端 1，:18081
./mvnw -pl switchyard-sample spring-boot:run      # 终端 2，:18080
```

---

## 8. 与其它方案对比（诚实版）

### vs LiteLLM / Portkey / Cloudflare AI Gateway

> 「为什么不直接用 LiteLLM？」是必答题，我们放在首屏回答。

| 维度 | LiteLLM / Portkey 等 | LLM 道岔 |
|---|---|---|
| **部署形态** | 独立 Python 服务 + 反向代理，需要额外部署与运维 | **跑在你的 Spring Boot 进程内**，无额外部署 |
| **认证复用** | 需自行对接（网关有自己的认证层） | 天然复用你既有的 Spring Security / 网关鉴权 |
| **流式计量** | 部分实现依赖上游是否返回 usage | **强制注入 `include_usage` + 回退估算并标注**，实测流式不为 0 |
| **缓存 token 计价** | 依赖上游解析器是否暴露 `cached_tokens` | 自研 wire 层，**完整保留** `prompt_tokens_details` |
| **国产模型中文 tokenizer** | 通用分词，对中文支持有限 | 内置中文感知估算器，可替换为 tiktoken |
| **数据出境** | 请求经过第三方服务 | 数据不出内网（私有化场景刚需） |
| **模型数量** | ★ **300+，这是它们的强项** | 9 家首版覆盖，P1 扩展 |
| **多供应商负载均衡/成本优化算法** | ★ 成熟且持续演进 | 首版为静态路由 + 降级链，动态打分在 P1 |

> **诚实结论**：如果你的场景是多云聚合、要接 300 个模型、需要成熟的负载均衡算法，**LiteLLM 是更成熟的选择**。
> 道岔的适用场景很具体：**中国大陆企业的私有化 Java 服务**，需要复用既有认证与限流体系、数据不能出境、且要按部门精确核算 AI 成本。

### vs 自建 Spring Cloud Gateway 过滤器

| 维度 | 自己写过滤器 | LLM 道岔 |
|---|---|---|
| 上手速度 | 需要自己实现全部语义 | 加 starter + 改配置，业务代码 1 行 |
| Token 计量 | 大多数实现只累加 `total_tokens`，不处理缓存/推理 | 7 步归一化算法，`total` 一律重算 |
| 流式 usage | 极易遗漏（PRD 痛点 4） | 有专门的回归测试守住 |
| 跨仓 traceId 贯通 | 无 | 遵循 W3C TraceContext，与 `llm-glasshouse` 同源 |
| 维护成本 | 供应商改协议就得跟 | 3 个适配器，改一处全部受益 |

---

## 9. 落地架构：我们在哪一层

```
┌──────────────────────────────────────────────────────────┐
│  你的 Spring Boot 应用（Spring Security / 限流 / 事务都在这层） │
└───────────────────────────┬──────────────────────────────┘
                            │ ChatClient / ChatModel
┌───────────────────────────▼──────────────────────────────┐
│  switchyard-starter（装饰器层，约 15 个文件）                │
│   SwitchyardRouter → RoutedChatModel(ChatModel 装饰器)      │
│   配额预留-对账 · 语义化重试 · 熔断 · 降级链 · 审计 · 成本      │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  switchyard-provider（3 个适配器 + 9 家供应商）               │
│  OpenAI兼容 · Anthropic · Ollama（自带 wire DTO，零字段丢失）   │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  switchyard-core（纯逻辑，零 Spring 依赖）                    │
│  Usage归一化 · 成本计算 · 配额 · 熔断 · TraceContext · 审计事件 │
└──────────────────────────────────────────────────────────┘
```

**数据流（一次调用）**：

```
业务代码 → ChatModel 装饰器
   → 取/建 traceId
   → 配额预留（请求前，超限直接 429，不打上游）
   → 熔断许可 → 语义化重试（仅 429/5xx/超时）→ 有序降级
   → usage 归一化（total 一律重算）
   → 配额结算（多退少补）→ 成本计算 → 审计
   → 返回 + 响应头（成本 / 剩余配额 / traceId / token）
```

---

## 10. 配置速查

完整配置见 [`docs/configuration.md`](docs/configuration.md)。最常用的几项：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `llm.switchyard.routes.<name>.primary` | — | 主模型 `providerId/modelId` |
| `llm.switchyard.routes.<name>.fallbacks` | `[]` | 有序降级链 |
| `llm.switchyard.providers.<id>.api-key` | — | **用环境变量注入，不要写进文件** |
| `llm.switchyard.providers.<id>.base-url` | 内置 | 代理 / 私有网关 / 区域端点 |
| `llm.switchyard.quota.on-exceeded` | `FAIL_SOFT` | 超限返回 429 还是直接熔断 |
| `llm.switchyard.retry.max-attempts` | `3` | 总尝试次数（含首次） |
| `llm.switchyard.circuit-breaker.failure-threshold` | `0.5` | 窗口失败率阈值 |
| `llm.switchyard.audit.capture-payload` | `false` | ★ **保持 false**，落原文有 PII 风险 |
| `llm.switchyard.metering.cjk-per-token` | `1.0` | 换成 tiktoken 只需实现 `TokenEstimator` |

---

## 11. 不做什么（先说清楚，比事后解释省事）

- ❌ **不做 SPA 控制台**。首版只出 3 个 JSON 端点 + 一张静态图。做完整仪表盘要 8–10 天，那段时间本该花在计量精度上。
- ❌ **不做语义化缓存**（P1）。缓存要引入 embedding 与向量存储，是显著的分叉点。
- ❌ **不做多实例分布式配额协调**（P2）。首版定位单实例 —— 嵌入式网关天然与业务同实例。多实例请用 Redis 自己实现 `QuotaStore`（SPI 已留）。
- ❌ **不在网关里存审计数据**。那是可观测的事，接口 `AuditSink` 已留好，接你自己的存储。
- ❌ **不做 prompt 版本管理与 A/B**（P1/P2）。
- ❌ **不替代你的 API 网关**。我们是 LLM 层的路由与计量，不接管南北向流量。

---

## 12. 参与贡献

```bash
git clone https://github.com/Nephets1010/llm-switchyard.git
cd llm-switchyard
./mvnw clean verify     # 五个 check 全绿才能提交
```

PR 必须通过：`build (Java 21)` · `spotless` · `contract-consistency` · `architecture-boundaries` · `compose-smoke`。

- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 行为准则：[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- 安全问题：[SECURITY.md](SECURITY.md)
- 架构决策：[docs/adr/](docs/adr/)

**特别欢迎的两个贡献方向**：

1. **新增供应商**：实现 `ChatProvider`，在 `ProviderCatalog` 注册，加一条 `AbstractProviderContractTest` 子类即可。
2. **价格表核对**：内置价格表的准确性直接决定成本报表的可信度。[README 第 4 节](docs/configuration.md#价格表)说明了如何提交你核对过的价格。

## Star 诉求

如果这个项目帮你**看懂了一笔说不清的 AI 账单**，或者**让一次上游抖动没有演变成一次生产事故**，
欢迎点一个 ⭐ Star —— 这会让更多遇到同样问题的人找到它。

也欢迎提 Issue 告诉我们你的实际痛点，哪怕你现在还没用起来。

---

<div align="center">

**Apache-2.0** ｜ [English](README.md) ｜ **简体中文**

计量精度 **0.363%** · 流式计量为 0 的条数 **0** · CI **零真实 API 调用**

</div>
