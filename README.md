<div align="center">

# LLM Switchyard

**The Java-native LLM gateway that runs inside your Spring Boot app — route every call by cost, latency
and quality, meter every token, and never let a provider outage take your business down.**

[English](README.md) | [简体中文](README.zh-CN.md)

[![CI](https://github.com/Nephets1010/llm-switchyard/actions/workflows/ci.yml/badge.svg)](https://github.com/Nephets1010/llm-switchyard/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen.svg)](https://spring.io/projects/spring-boot)

</div>

---

## 1. The problem this solves

> How much of your LLM bill is **repeated system prompts**, **silently-zeroed streaming requests**, and
> **spend nobody can attribute to a department**?
>
> LLM Switchyard fixes all three at once: token-exact metering (streaming included), quota enforcement
> measured in tokens rather than requests, and per-tenant cost attribution. It runs **inside your own
> Spring Boot process** — no extra deployment, no data leaving your network.

| Your problem | What Switchyard does | Proof |
|---|---|---|
| Bill only shows a vendor total | Every response carries cost + traceId; audit events carry tenant/business-line dimensions | `X-Switchyard-Cost-Cny` header + `/_switchyard/overview` |
| Streaming requests are billed as 0 tokens (silent) | Injects `include_usage`; falls back to an estimator and **labels it** `ESTIMATED` | **0.363%** error over 100,000 records; **0** streaming requests metered as zero |
| One tenant's crawler drains the budget | Reserve-before-call; over-quota returns HTTP 429 (never a 5xx) | `InMemoryQuotaStoreTest`: 100 threads racing for 1,000 tokens → exactly 10 pass |
| An OpenAI 429 becomes a 500 and pages someone at 3am | Semantic retry (429/5xx/timeouts only) + circuit breaker + ordered fallback chain | `ErrorClassifierTest`: 429 retried, 400 retried **zero** times |

---

## 2. Support status — read this first

> **OSS support for Spring Boot 3.5 ended on 2026-06-30. This project pins 3.5.16, the last OSS patch of the 3.5 line.**

We chose 3.5.x anyway, and we designed the upgrade path up front:

**Why still on 3.5.16**

- Our users run 5–30-person Java teams whose existing services are on Boot 3.x. Requiring a full framework migration before they can *try* a gateway is a deal-breaker.
- Spring AI 2.0 requires Boot 4.x; Boot 4 brings Jackson 3 migration and starter restructuring, whose cost lands on users while the benefit is "a newer version number".

**Migration path (designed, not aspirational)**

| Layer | Depends on Spring AI / Boot internals? | Impact of moving to Boot 4.1 + Spring AI 2.x |
|---|---|---|
| `switchyard-core` (metering / cost / quota / breaker) | **No** | **Zero impact**, unchanged |
| `switchyard-provider` usage parsing | Own wire DTOs, no `org.springframework.ai.*` | Zero impact |
| `switchyard-provider` `ChatModel` adapters | `ChatModel` / `Prompt` / `ChatResponse` | Needs work (~8 files) |
| `switchyard-starter` auto-configuration | Boot auto-config | Needs work (~7 files) |

> In other words: **the upgrade is confined to roughly 15 files; core and every contract test stay put.**
> CI enforces this via the `architecture-boundaries` job — a single `org.springframework.*` entry in core's dependency tree fails the build.

---

## 3. 30-second quickstart

**Step 1 — add the dependency**

```xml
<dependency>
  <groupId>io.github.nephets</groupId>
  <artifactId>switchyard-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**Step 2 — configure it** (full version in [`application-switchyard.yml`](switchyard-starter/src/main/resources/application-switchyard.yml))

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

**Step 3 — change one line in your code**

```java
@Bean
ChatClient chatClient(SwitchyardRouter router) {
  return ChatClient.builder(router.route("default")).build();
  //              ↑ only the ChatModel's origin changed. Everything else is untouched.
}
```

**That's the whole migration.** Structured output, tool calling, and streaming all keep working, because
`router.route()` returns a standard `ChatModel` — we only wrap it in a decorator.

---

## 4. Metering accuracy: the actual moat

<div align="center">

| Traffic | Billing ground truth | Metered by Switchyard | **Measured error** | Budget |
|---|---|---|---|---|
| **100,000 requests** | 290,272,514 tokens | 289,217,848 tokens | **0.363337%** | 0.5% |
| Streaming subset | 33,334 requests | — | requests metered as zero: **0** | 0 |

</div>

Produced by `MeteringAccuracyTest` on every `mvn verify`, with a fixed seed (`20261009`) so **anyone who
clones the repo gets the identical number**. Report: `switchyard-core/target/precision-report.md`.

**How we get there**

1. **`totalTokens` is always recomputed by us**, never copied from upstream — vendors disagree on whether reasoning tokens are included, and copying that field transfers their accounting risk onto your reconciliation.
2. **Cached tokens are never double-billed.** `prompt_tokens_details.cached_tokens` is parsed in full and billed separately at its discount rate. Spring AI's default parser **discards this field** — which is why we own the wire layer.
3. **Streaming requests get `stream_options.include_usage=true` injected unconditionally.** That is the root cause of the "streaming is always 0" bug: OpenAI and vLLM omit the usage block entirely without it.
4. **When usage is genuinely missing, we don't pretend.** The value falls back to a replaceable estimator, and `usageSource` / `streamUsageReported` are set honestly so the condition is monitorable and alertable.

**Honest disclosure:** most entries in the bundled price table are marked `source: ESTIMATED` and labelled as such in the console. **Verify against your own invoices**, or supply your own `switchyard-prices.yml`. We refuse to fabricate prices in code.

---

## 5. Capabilities

| Capability | Status | Evidence (test class) |
|---|---|---|
| Multi-provider adapter layer (**3 adapters / 9 vendors**) | ✅ | `ProviderContractIT` (one `@MethodSource` template across 7 OpenAI-compatible vendors) |
| In-process starter, no sidecar | ✅ | `SampleSmokeIT` |
| Token-exact metering (streaming included) | ✅ | `MeteringAccuracyTest`, `ProviderContractIT` |
| Quota reserve → settle (reject before spending) | ✅ | `InMemoryQuotaStoreTest`, `QuotaReservationSettlementTest` |
| Quota circuit breaking, fail-soft (429 not 5xx) | ✅ | `SampleSmokeIT#quotaDemoRejectsOverLimit` |
| Semantic retry (never on 4xx) | ✅ | `ErrorClassifierTest` |
| Circuit breaker + ordered fallback | ✅ | `CircuitBreakerTest` |
| Structured audit (no prompt text by default) | ✅ | `AuditEventContractTest` |
| Hot-reloadable price table | ✅ | `PricingTableLoaderTest` |
| W3C TraceContext | ✅ | `W3CTraceCodecContractTest`, `ContractSchemaTest` |
| Console JSON endpoints | ✅ | `SampleSmokeIT` |
| CLI (`quota` / `audit` / `doctor`) | ✅ | `QuotaCli`, `AuditCli`, `DoctorCli` |
| Semantic cache | ⏳ P1 | |
| Cost/latency/quality dynamic routing | ⏳ P1 | v1 ships `StaticRoutingPolicy`; the SPI is in place |

---

## 6. Provider support matrix

9 vendors, **3 protocol adapters**. Every cell is backed by a test.

| Vendor | providerId | Protocol | Streaming | Structured output | Tool calls | Exact metering | Reasoning tokens | Cache hits |
|---|---|---|---|---|---|---|---|---|
| OpenAI | `openai` | OpenAI-compatible | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| Qwen (Alibaba) | `dashscope` | OpenAI-compatible | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| DeepSeek | `deepseek` | OpenAI-compatible | ✅ | — | ✅ | ✅ | ✅ | ✅ |
| Zhipu GLM | `zhipu` | OpenAI-compatible | ✅ | — | ✅ | ✅ | ✅ | — |
| Kimi (Moonshot) | `moonshot` | OpenAI-compatible | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| Doubao (Volcano) | `doubao` | OpenAI-compatible | ✅ | — | ✅ | ✅ | — | ✅ |
| SiliconFlow | `siliconflow` | OpenAI-compatible | ✅ | ✅ | ✅ | ✅ | — | — |
| Anthropic | `anthropic` | Anthropic native | ✅ | — | ✅ | ✅ | ✅ | ✅ |
| Ollama | `ollama` | Ollama native | ✅ | — | ✅ | ✅ | — | — |

`—` means the vendor's current API does **not** return that field; `reasoningTokens` is then always 0 with
`reasoningEstimated=true` — we never fabricate it.

---

## 7. Try it in one command (no API key, no cost)

```bash
git clone https://github.com/Nephets1010/llm-switchyard.git
cd llm-switchyard
docker compose up --build
```

```bash
# ① sync chat — observe metering and cost
curl -s -XPOST localhost:18080/demo/chat \
  -H 'Content-Type: application/json' -d '{"prompt":"Introduce yourself in one sentence"}' | jq

# ② streaming — the final event always carries totalTokens > 0
curl -sN -XPOST localhost:18080/demo/chat/stream \
  -H 'Content-Type: application/json' -d '{"prompt":"Tell me a joke"}'

# ③ console — real numbers, not zeros
curl -s localhost:18080/_switchyard/overview | jq

# ④ concurrency vs. quota — most requests get rejected
curl -s "localhost:18080/demo/quota-demo?concurrency=20" | jq
```

**See retry and fallback actually happen:**

```bash
curl -XPOST localhost:18081/__admin/scenario -d '{"scenario":"rate-limit-retryable"}'
# call the demo again — the gateway retries and succeeds; look for the retry log lines
curl -XPOST localhost:18081/__admin/reset
```

**Without Docker:**

```bash
./mvnw -pl switchyard-mock-llm spring-boot:run    # terminal 1, :18081
./mvnw -pl switchyard-sample spring-boot:run      # terminal 2, :18080
```

---

## 8. Honest comparison

### vs LiteLLM / Portkey / Cloudflare AI Gateway

| Dimension | LiteLLM / Portkey | LLM Switchyard |
|---|---|---|
| **Deployment** | Separate Python service behind a reverse proxy | **Inside your Spring Boot process** |
| **Auth reuse** | Your own integration work | Inherits your existing Spring Security |
| **Streaming metering** | Often depends on whether the vendor returns usage | **`include_usage` injected; verified non-zero** |
| **Cached-token billing** | Depends on the upstream parser exposing `cached_tokens` | Own wire layer; **fully preserved** |
| **Chinese tokenization** | Generic | Chinese-aware estimator, swappable for tiktoken |
| **Data residency** | Traffic transits a third party | Never leaves your network |
| **Model catalog size** | ★ **300+ — their genuine strength** | 9 in v1 |
| **Mature routing algorithms** | ★ Battle-tested | Static routing + fallback in v1; dynamic scoring in P1 |

> **Straight talk:** if you need 300 models and mature load-balancing, **LiteLLM is the more mature choice.**
> Switchyard targets a specific case: **private Java services in mainland China** that must reuse existing auth
> and rate limiting, cannot send data abroad, and need per-department AI cost accounting.

### vs hand-rolled Spring Cloud Gateway filters

| Dimension | Hand-rolled | LLM Switchyard |
|---|---|---|
| Time to first call | Weeks | Dependency + config + one line |
| Token metering | Usually sums `total_tokens`; caches and reasoning ignored | 7-step normalization; total always recomputed |
| Streaming usage | Commonly missed (PRD pain point 4) | Regression-tested |
| Cross-repo traceId | None | W3C TraceContext, same lineage as `llm-glasshouse` |
| Maintenance | Every vendor protocol change is yours | 3 adapters; one fix benefits all |

---

## 9. Where it sits in your architecture

```
┌──────────────────────────────────────────────────────────┐
│  Your Spring Boot app (Security / rate limiting / txns here) │
└───────────────────────────┬──────────────────────────────┘
                            │ ChatClient / ChatModel
┌───────────────────────────▼──────────────────────────────┐
│  switchyard-starter — decorator layer (~15 files)           │
│   SwitchyardRouter → RoutedChatModel (ChatModel decorator)  │
│   quota · retry · breaker · fallback · audit · cost         │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  switchyard-provider — 3 adapters, 9 vendors               │
│  OpenAI-compatible · Anthropic · Ollama (own wire DTOs)     │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  switchyard-core — pure logic, zero Spring dependencies    │
│  Usage normalization · cost · quota · breaker · trace · audit│
└──────────────────────────────────────────────────────────┘
```

---

## 10. Configuration quick reference

| Property | Default | Notes |
|---|---|---|
| `llm.switchyard.routes.<name>.primary` | — | `providerId/modelId` |
| `llm.switchyard.routes.<name>.fallbacks` | `[]` | Ordered fallback chain |
| `llm.switchyard.providers.<id>.api-key` | — | **Inject via env vars, never commit** |
| `llm.switchyard.providers.<id>.base-url` | built-in | Proxies / private gateways / regional endpoints |
| `llm.switchyard.quota.on-exceeded` | `FAIL_SOFT` | 429 response, or trip the breaker |
| `llm.switchyard.retry.max-attempts` | `3` | Total attempts including the first |
| `llm.switchyard.circuit-breaker.failure-threshold` | `0.5` | Window failure ratio |
| `llm.switchyard.audit.capture-payload` | `false` | ★ **Keep false** — raw prompts carry PII |
| `llm.switchyard.metering.cjk-per-token` | `1.0` | Implement `TokenEstimator` to swap in tiktoken |

Full reference: [`docs/configuration.md`](docs/configuration.md).

---

## 11. What this is *not*

- ❌ **No SPA console.** v1 ships three JSON endpoints. A full dashboard costs 8–10 days that belong in metering accuracy.
- ❌ **No semantic cache** (P1). It drags in embeddings and a vector store — a genuine fork in the road.
- ❌ **No multi-instance quota coordination** (P2). v1 is single-instance by design (an embedded gateway lives with its app). Implement the `QuotaStore` SPI over Redis when you need it.
- ❌ **No audit storage.** That's an observability concern; the `AuditSink` SPI is there for you to plug in.
- ❌ **Not a replacement for your API gateway.** It routes and meters at the LLM layer.

---

## 12. Contributing

```bash
git clone https://github.com/Nephets1010/llm-switchyard.git
cd llm-switchyard
./mvnw clean verify
```

PRs must pass: `build (Java 21)` · `spotless` · `contract-consistency` · `architecture-boundaries` · `compose-smoke`.

- [CONTRIBUTING.md](CONTRIBUTING.md) · [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) · [SECURITY.md](SECURITY.md) · [docs/adr/](docs/adr/)

**Two contributions we'd especially welcome:**

1. **A new provider** — implement `ChatProvider`, register it in `ProviderCatalog`, add one `AbstractProviderContractTest` subclass.
2. **Price-table verification** — the accuracy of the bundled price table directly determines whether the cost report can be trusted.

## A Star, if you please

If this helped you **finally understand an unexplained AI bill**, or **stopped a provider blip from becoming a production incident**, a ⭐ goes a long way — it helps the next engineer facing the same problem find it.

Issues are welcome too, even if you're not using it yet.

---

<div align="center">

**Apache-2.0** ｜ [English](README.md) ｜ [简体中文](README.zh-CN.md)

Metering error **0.363%** · zero-metered streaming requests **0** · CI makes **zero real API calls**

</div>
