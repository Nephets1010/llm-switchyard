# ADR-0004：自定义属性统一使用 `swy.` 前缀

- **状态**：已接受
- **日期**：2026-10-09
- **影响范围**：契约 SPN-001、全部审计事件与 span 属性

## 背景

三个仓库同属一个家族（`switchyard` / `agentswitchyard` / `glasshouse`），但名字各不相同。
如果各自定义自定义 span 属性，会出现 `swy.tenant_id`、`agent_tenant`、`gh_tenant` 三种写法。

这带来两个具体问题：

1. **后端要写三套查询规则**。Langfuse / 自研看板要分别记住三套属性名才能跨仓聚合。
2. **撞名风险**。裸的自定义属性名（如 `tenant.id`、`cost.amount`）随时可能与
   OpenTelemetry 后续新增的标准属性冲突，届时会造成**静默的语义错位** ——
   老看板上的数字含义悄悄变了，而没有任何报错。

## 决策

**三仓所有自有 span 属性一律以 `swy.` 开头，且必须带点分隔**（`swy.tenant.id` 而非 `swyTenantId`）。

| 属性 | 类型 | 出现位置 |
|---|---|---|
| `swy.tenant.id` | string | 三仓 |
| `swy.biz.line` | string | 三仓 |
| `swy.feature` | string | 三仓 |
| `swy.prompt.version` | string | 三仓 |
| `swy.agent.id` / `swy.agent.version` | string | ②③ |
| `swy.flow.instance.id` / `swy.step.id` / `swy.step.seq` / `swy.step.kind` | — | ② |
| `swy.usage.source` | string | ①③ |
| `swy.usage.stream_reported` | boolean | ①③ |
| `swy.usage.reasoning_tokens` | int | ①③ |
| `swy.usage.cached_input_tokens` | int | ①③ |
| `swy.cost.amount` / `swy.cost.currency` | double / string | ①③ |
| `swy.price.source` | string | ①③ |
| `swy.approval.state` | string | ② |
| `swy.payload.digest` / `swy.payload.full` | string | 三仓 |

**`swy` 取自三个仓库名的公共词（switch-yard）**，是「家族前缀」而不是「仓库前缀」——
这正是「一个 trace 里的属性口径完全一致」这个卖点的技术前提。

## 后果

- 后端只需要一套属性名即可跨仓聚合，README 可以直接给出统一查询语句。
- 与 OTel 标准属性（`gen_ai.*`）明确分离：标准属性由 semconv 定义，
  自有属性由本契约定义，两者不会互相污染。
- `ContractSchemaTest` 断言「所有 `customAttributes` 都以 `swy.` 开头，
  且没有裸 `swy`」—— 写错前缀会直接让 CI 变红。

## 关于 `gen_ai.usage.*` 的历史改名

OTel 在 1.42.0 之前用 `gen_ai.usage.prompt_tokens` / `completion_tokens`，
之后改为 `input_tokens` / `output_tokens`。本项目**统一使用新名**，
并在 span 上**额外冗余旧名一个发布周期**，让存量看板平滑过渡。

## 备选方案与放弃理由

| 方案 | 放弃理由 |
|---|---|
| 各自前缀（`sy.` / `ag.` / `gh.`） | 三套查询规则，直接摧毁「跨仓同源」的核心卖点 |
| 无前缀，用自然语义命名 | 与 OTel 未来标准属性撞名时会造成静默语义错位 |
| camelCase（`swyTenantId`） | 点分隔是 OTel 生态的惯例，且便于按前缀做批量检索与权限控制 |
