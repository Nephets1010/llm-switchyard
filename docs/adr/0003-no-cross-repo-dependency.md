# ADR-0003：三仓只共享规范，不共享 jar

- **状态**：已接受
- **日期**：2026-10-09

## 背景

`llm-switchyard`（①路由与计量）、`agentswitchyard`（②Agent 编排）、`llm-glasshouse`（③可观测与评测）
三个仓库共享一套技术底座与一套遥测约定。最省事的做法是抽一个公共 jar 让三者依赖。

## 决策

**不抽公共 jar。三仓各自在自己的 `*-core` 里独立实现一份，用 `contract/contract-v1.json` 锁定语义。**

## 为什么不用共享 jar

| 理由 | 说明 |
|---|---|
| 版本耦合 | 三仓独立发版节奏不同。共享 jar 意味着任何一仓改动都要等另两仓升版 |
| 单仓可编译 | 每个仓库都能 `git clone` 后独立 `mvn clean verify`。这是最基本的开源底线 |
| 依赖洁癖 | 用户只想装一个网关，结果被拖进来两个无关模块的传递依赖 |
| 升级隔离 | 三个仓的框架升级节奏可能不同（见 ADR-0005），共享 jar 会把升级绑在一起 |

## 那靠什么保证语义一致

**契约测试，而不是共享代码。**

`contract/contract-v1.json` 定义六份契约：

| 编号 | 内容 |
|---|---|
| `CTX-001` | W3C TraceContext 的字段名、格式、传播载体 |
| `USG-001` | Token 计量归一化算法 ★ |
| `PRC-001` | 价格条目与成本公式 |
| `EVT-001` | 审计事件 schema |
| `ERR-001` | 错误码、HTTP 状态、可重试性 |
| `SPN-001` | Span 命名与 `swy.` 属性 |

每仓都有一个 `ContractSchemaTest`，用**反射**把本仓的契约类逐字段与 JSON 比对：
字段名、顺序、类型、必填性、枚举值，逐字符一致。

**任何一方改了字段名而没同步另两侧，CI 立刻红。** 这比共享 jar 更严格 ——
共享 jar 是「编译期保证一致」，契约测试是「语义级保证一致」，后者才是我们真正需要的。

## 为什么不放 git submodule

submodule 对国内 Java 团队的 DX 不友好（clone 要 `--recursive`、CI 要额外配置、忘了初始化就构建失败），
而且早期契约变更频率高，submodule 的合并冲突会淹没真正的语义讨论。

演进到 v2 之后再抽独立仓也不迟。

## 机器化的约束

```xml
<!-- 父 pom 的 enforcer -->
<bannedDependencies>
  <exclude>io.github.nephets:agentswitchyard*</exclude>
  <exclude>io.github.nephets:glasshouse*</exclude>
</bannedDependencies>
```

CI 的 `architecture-boundaries` job 还会扫依赖树，出现另一个仓的 groupId 即失败。

## 代价（诚实列出）

- **同一段逻辑写了两遍**（如 ① 与 ③ 都有 Usage 归一化）。这是本决策最主要的成本。
- 契约变更需要**三仓同 PR**，协调成本存在。

我们接受这个成本，因为它的替代方案（共享 jar）带来的版本耦合与发版地狱更贵，
而且会让「单独 clone 一个仓库就能用」这个开源项目最基本的承诺失效。
