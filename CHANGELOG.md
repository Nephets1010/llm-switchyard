# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 变更

- CLI 命令结果统一走新增的 `CliConsole`（`starter.shell` 包内的唯一出口），不再在各命令类里直写
  `System.out` / `System.err`。stdout / stderr 的逐字节契约与全部输出文案、行序均不变，对外 API 不变。
- **日志与命令结果的边界（写进规范，避免再犯）**：CLI 命令结果是产品输出，不是日志，
  **不得改成 logger**。Spring Boot 默认把日志也打到 stdout，一旦混入，按行解析配额表 / 审计表格的
  CI 断言就会被污染（历史上因此加过一次、当天回退）；而 stderr 上的「先把话说明白」式告警改成
  logger 后，宿主调高日志级别就会把提示吞掉，使用者看到的是一份「看起来正常」的输出。
  命令执行过程的可观测性另找出口，既不走 stdout 也不走 stderr。

## [0.1.0] — 2026-10-09

首个可发布版本。

### 新增

#### 多供应商适配（3 个适配器 / 9 家供应商）

- `OpenAiCompatibleProvider` —— 覆盖 OpenAI / 通义千问 / 智谱 GLM / DeepSeek / Kimi / 豆包 / 硅基流动
- `AnthropicProvider`、`OllamaProvider`
- 自研 wire DTO：完整保留 `prompt_tokens_details.cached_tokens` 与
  `completion_tokens_details.reasoning_tokens`（Spring AI 的解析器会丢弃这两个字段）

#### Token 精确计量（USG-001）

- 七步归一化算法，`totalTokens` 一律本仓重算
- 流式请求无条件注入 `stream_options.include_usage=true`
- 收不到 usage 时回退估算并显式标注 `ESTIMATED` / `streamUsageReported=false`
- 实测：10 万条构造流量误差 **0.363%**（种子 `20261009`），流式计量为 0 的条数 **0**

#### 配额预留-对账-熔断

- 请求前按 `估算(prompt) + max_tokens` 原子预留；100 线程并发抢 1000 token 恰好 10 个通过
- 请求后按真实用量结算（多退少补）；失败时释放预留，避免额度泄漏
- 超限 fail-soft：返回 `SW_QUOTA_EXCEEDED`（HTTP 429），不抛异常、不产生 5xx
- 预留 TTL 过期自动回收，防止进程崩溃导致额度被永久扣光

#### 语义化重试与降级

- 仅对 429 / 5xx / 超时重试（指数退避 + 抖动，防重试风暴）
- 4xx 绝不重试（同样的非法参数重试 100 次结果一样）
- 滑动窗口熔断器 + 有序降级链；降级过程可查

#### 审计

- 结构化 JSON 日志（独立 logger `switchyard.audit`）
- **默认不落 prompt 原文**，只落 `sha256` 摘要
- `AuditSink` SPI 可替换为任意合规存储

#### 嵌入式 starter

- `router.route("default")` 返回标准 `ChatModel`，`ChatClient` 能力零损失
- 响应头返回成本 / 剩余配额 / traceId / token / 计量来源
- W3C TraceContext 贯穿，虚拟线程安全

#### 控制台与 CLI

- `GET /_switchyard/overview` · `/quota` · `/models`
- `switchyard quota` · `audit` · `doctor`（picocli）

#### 工程

- core 层零 Spring 依赖，CI 用 `architecture-boundaries` job 机器化守护
- 覆盖率门禁：core ≥ 80% · provider ≥ 65% · 其余 ≥ 60%
- `contract/contract-v1.json` + `ContractSchemaTest` 反射校验跨仓契约
- `switchyard-mock-llm` 提供三协议端点 + 9 个故障场景，CI 零真实 API 调用
- `docker compose up` 一条命令跑通 demo

### 已知限制

- **配额是单实例的**。多实例需自行实现 `QuotaStore` SPI（接口已留），P2 计划提供 Redis 实现
- **控制台首版只有 JSON 端点**，SPA 前端在 P1
- **路由是静态的**（主模型 + 有序降级链），成本/延迟/质量三维动态打分在 P1
- **内置价格表多为社区估算值**（`source: ESTIMATED`），控制台会显式标注。
  **请以自己的供应商账单为准**
- **Spring Boot 3.5 的 OSS 支持已于 2026-06-30 结束**，本项目锁定 3.5.16。
  升级路径见 [ADR-0005](docs/adr/0005-upgrade-path.md)
- 不支持 Embedding / Image / Audio 端点；Azure OpenAI / Bedrock 等需自定义认证头

### 破坏性变更预留

对外 API 遵循：新增签名变化升 MINOR，删除/不兼容变化升 MAJOR，并在本文件写「迁移指南」。

<!-- 上传通道校验标记 -->
