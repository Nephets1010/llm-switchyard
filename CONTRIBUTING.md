# 贡献指南

感谢你考虑为 `llm-switchyard` 做贡献。这份文档说明本地环境、提交规范，以及 PR 必须通过的检查。

## 本地环境

```bash
git clone https://github.com/Nephets1010/llm-switchyard.git
cd llm-switchyard
./mvnw clean verify
```

只需要 **JDK 21** 与 Maven（`mvnw` 会自动下载 3.9.9）。

> ⚠️ **仓库路径请避免使用非 ASCII 字符**。
> 在 Windows 上，含中文的路径会让 `maven-jar-plugin` 在组装 JAR 时把路径编码搞坏，
> 报出 `Error assembling JAR: ... NoSuchFileException` 且路径显示为乱码。
> 这是 Maven/Windows 的编码问题，不是代码问题，但会浪费你半天时间。

## 提交规范

[Conventional Commits](https://www.conventionalcommits.org/)：

```
feat: 支持 Azure OpenAI 的 api-key 认证头
fix: Anthropic 流式 usage 合并方向错误
docs: 补充价格表热更新的配置说明
perf: 配额预留的分桶锁改为 StampedLock
test: 补 400 不重试的边界用例
```

## PR 必须通过的检查

| check | 内容 | 怎么本地复现 |
|---|---|---|
| `build (Java 21)` | 全量测试 + 覆盖率门禁 | `./mvnw clean verify` |
| `spotless` | google-java-format（AOSP 风格） | `./mvnw spotless:apply` 后再提交 |
| `contract-consistency` | 契约字段与 `contract/contract-v1.json` 逐字符一致 | `./mvnw -pl switchyard-core test -Dtest=ContractSchemaTest` |
| `architecture-boundaries` | core 零 Spring 依赖；不依赖另两个仓库 | `./mvnw -pl switchyard-core dependency:list` |
| `compose-smoke` | 零 API Key 跑通 demo | `docker compose up --build` |

## 代码风格

- 缩进 2 空格（`spotless` 会自动格式化，不要手工对齐）
- **注释与文档用中文**，代码标识符用英文
- 注释解释「**为什么**」，不解释「是什么」

  ```java
  // ✗ 不好：把 used 加到 used 上
  used += delta;

  // ✓ 好：解释为什么覆盖方向是「后到者优先」
  // message_start 的 output_tokens 是占位值（典型为 1），
  // message_delta 的才是真实输出量。先到者优先会让每次流式的输出 token 都被记成 1。
  usage = usage.merge(event.usage());
  ```

## 架构约束（改代码前必读）

| 约束 | 原因 | 怎么守住 |
|---|---|---|
| **core 不得依赖任何 Spring 类型** | 保证升级到 Boot 4.1 + Spring AI 2.x 时 core 零改动 | CI 的 `architecture-boundaries` job |
| **不得引入另两个仓库的依赖** | 决策 4：三仓只共享规范，不共享 jar | `maven-enforcer-plugin` 的 `bannedDependencies` |
| **`totalTokens` 一律本仓重算** | 拷贝上游会因各家口径不同而对不上账 | `UsageContractSchemaTest` |
| **不要给业务代码引入必须的新 API** | 嵌入式网关的价值就是「零侵入」 | 装饰器模式：`ChatModel` 的来源变化是唯一侵入点 |
| **审计默认不落 prompt 原文** | PII 泄露风险 | `AuditEventContractTest` |

## 最需要帮助的两个方向

### 1. 新增供应商

三步：注册 `ProviderCatalog` → （非 OpenAI 兼容时）实现 `ChatProvider` →
继承 `AbstractProviderContractTest` 写契约测试。

细节见 [docs/providers.md](docs/providers.md#新增一个供应商)。

### 2. 核对价格表 ★

内置价格表的准确性直接决定成本报表的可信度。目前多数条目标为
`source: ESTIMATED`（社区估算）。

如果你用真实账单核对过某个模型的价格，欢迎提 PR：

```yaml
- providerId: deepseek
  modelId: deepseek-chat
  inputPricePerUnit: 0.002      # ← 你核对过的真实值
  outputPricePerUnit: 0.008
  cachedInputPricePerUnit: 0.0005
  effectiveFrom: 2026-10-09      # ← 核对日期
  source: OFFICIAL              # ← 改成 OFFICIAL
```

**这可能是对用户最有价值的一种贡献** —— 成本报表的可信度完全取决于它。

## 报告 Bug

请用 [Bug 模板](.github/ISSUE_TEMPLATE/bug_report.md)，务必包含：

- Spring Boot / Spring AI / JDK 版本
- 供应商与模型
- 最小可复现代码
- 实际行为 vs 预期行为
- 如果与计量有关：`/_switchyard/overview` 的输出

**如果涉及计量误差，请附上你的 prompt 与模型的 usage 响应。** 这是我们定位问题最重要的信息。

## 安全问题

不要提 Issue，见 [SECURITY.md](SECURITY.md)。
