# ADR-0006：provider 层自研 wire DTO，不复用 Spring AI 的 provider starter

- **状态**：已接受
- **日期**：2026-10-09
- **影响范围**：`switchyard-provider` 模块全部实现

## 背景

Spring AI 1.1.8 主仓库已包含 openai / anthropic / ollama / deepseek / zhipuai 的官方 starter。
架构文档最初建议直接复用（理由：少写 HTTP 客户端）。

但实现过程中发现一个**与本项目核心卖点直接冲突**的事实：
Spring AI 的 `OpenAiApi` 在把响应转成 `ChatResponse` 时，
**丢弃了 `prompt_tokens_details.cached_tokens` 与 `completion_tokens_details.reasoning_tokens`**。

而这两个字段恰好是：

| 字段 | 丢了会怎样 |
|---|---|
| `prompt_tokens_details.cached_tokens` | 缓存命中的那部分 token 会被**按全价**计入成本 → 与供应商账单对不上 |
| `completion_tokens_details.reasoning_tokens` | DeepSeek / GLM 的推理成本**算不出来** |

同时，流式路径下末尾的 usage chunk 也不对外暴露，而它正是 PRD 痛点 4 的修复对象。

换句话说：**复用 Spring AI 的解析器，等于把本项目唯一不可妥协的能力（计量精度）
外包给一个并不承诺保留这些字段的实现。**

## 决策

**provider 层用 JDK 内置 `HttpClient` + Jackson 自研 wire DTO**，不引入
`spring-ai-starter-model-*`。

同时，`provider` 层**不使用 Spring AI 的类型做计量**：它内部跑一套自己的
`GatewayRequest` / `GatewayResponse` / `RawUsage`，只有 `RoutedChatModel`（在 starter 层）
才把结果翻译成 Spring AI 的 `ChatResponse`。

## 后果

### 正面

- **计量字段零丢失**：`cached_tokens`、`reasoning_tokens`、流式末尾 usage 全部完整解析，
  且有断言守着（`ProviderContractIT` 里 mock 的 `total_tokens` 被刻意设成错值，
  测试断言网关用的是自己的重算值）。
- **升级隔离面更小**（ADR-0005）：连 provider 的 usage 解析也不依赖 `org.springframework.ai.*`。
- **三家协议可精确表达差异**：Anthropic 的 `cache_creation_input_tokens` /
  `cache_read_input_tokens` 双字段、Ollama 的 `prompt_eval_count` / `eval_count` 反向命名、
  Ollama 流式是 ndjson 而非 SSE —— 这些用统一抽象表达都会失真。
- **零额外依赖**：provider 模块只依赖 jackson-databind + JDK。

### 负面

- **要自己维护 wire DTO**。供应商新增字段时需要手动加（但 `FAIL_ON_UNKNOWN_PROPERTIES=false`
  已配置，未知字段会被忽略，不会因上游新增字段而解析失败）。
- **不支持 `include_usage` 之外的供应商私有扩展**（如 vLLM 的自定义 usage 字段），
  需要按需扩展 DTO。

## 何时应该重新考虑

如果 Spring AI 在后续版本中承诺并测试「保留 `cached_tokens` 与 `reasoning_tokens`」，
本 ADR 可以被推翻。但即便如此，provider 层也应保留自己的 DTO 作为**降级路径** ——
因为 `switchyard` 需要在「用户不用 Spring AI」时也能独立工作。

## 备选方案与放弃理由

| 方案 | 放弃理由 |
|---|---|
| 复用 `spring-ai-starter-model-openai` | 丢弃缓存与推理 token，直接损害核心卖点（见上） |
| 复用 Spring AI 但通过反射读 nativeUsage | 依赖内部实现，升级即碎；且不同 provider 的 nativeUsage 结构不一致 |
| 直接用 Jackson 的 `JsonNode` 自由遍历 | 失去类型安全，字段名拼错只能运行时发现 |
