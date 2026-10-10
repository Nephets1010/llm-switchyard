# ADR-0005：升级路径（Boot 4.1 + Spring AI 2.x）

- **状态**：已接受
- **日期**：2026-10-09
- **影响范围**：全部模块的依赖分层、README「支持状态」小节

## 背景（必须先如实说明的事实）

**Spring Boot 3.5.x 的 OSS 支持已于 2026-06-30 结束，最后一个 OSS 补丁是 3.5.16。**
本项目锁定的就是 3.5.16。

同时：Spring AI 2.0 只能配 Spring Boot 4.x（Boot 4 带来 Jackson 3 迁移与 starter 拆分），
而 Spring AI 1.1.8 是 Boot 3.x 的对应线。

我们仍然选择 Boot 3.5 + Spring AI 1.1.8，理由是：

1. 目标用户是「5–30 人 Java 团队」，存量服务绝大多数跑在 Boot 3.x。
   要求他们**先做一次全量框架迁移**才能试用一个网关，是获客杀手。
2. Spring AI 2.0 的收益（Jackson 3、starter 拆分）对「接入一个 LLM 网关」这个场景
   并不直接，而迁移成本会被转嫁给用户。

**隐瞒 EOL 会损伤信任，而披露 + 给出经过设计的迁移路径反而是加分项。**
因此本项目的做法是：在 README 首屏的「支持状态」小节如实披露，并把迁移边界设计成可验证的。

## 关键设计：升级影响被限制在约 15 个文件内

| 层 | 是否依赖 Spring AI / Boot 内部 API | 升级到 Boot 4.1 + Spring AI 2.x |
|---|---|---|
| `switchyard-core`（Usage / PriceEntry / CostCalculator / QuotaStore / TraceContext / AuditEvent） | **完全不依赖**（纯 POJO + record） | **零影响，一行不改** |
| provider 的 usage 解析（UsageNormalizer 实现） | 只用自己的 wire DTO，不引用 `org.springframework.ai.*` | 零影响 |
| provider 的 `ChatModel` 适配器 | 依赖 `ChatModel` / `Prompt` / `ChatResponse` | **需改（约 8 个文件）** |
| starter 自动装配 | 依赖 Boot auto-configuration | **需改（约 7 个文件）** |
| core 与全部契约测试 | — | **不动** |

## 决策

1. **core 层零 Spring 依赖**，并用机器化方式守住这条边界：
   - `core` 的编译依赖只有 `org.yaml:snakeyaml` 一个；
   - CI 的 `architecture-boundaries` job 执行
     `mvn -pl switchyard-core dependency:list`，出现 `org.springframework` 即失败。
2. **provider 层自研 wire DTO**，不把「解析 OpenAI/Anthropic/Ollama 响应」这件事
   交给 Spring AI 的解析器 —— 见 ADR-0006。
3. **README 首屏披露 EOL 事实与本表**，不做任何淡化。

## 为什么 core 能做到零框架依赖

- `Usage` / `PriceEntry` / `AuditEvent` / `TraceContext` 都是 record + 基本类型；
- 价格表用 SnakeYAML 直接解析（而不是 Spring 的 `YamlPropertiesFactoryBean`）——
  SnakeYAML 是通用库，不属于 Spring；
- 供应商目录、能力矩阵、降级链、熔断器全是纯 POJO；
- 需要 `Clock` 的地方全部支持注入，便于测试（架构约定：超时/TTL 逻辑不依赖 `Thread.sleep`）。

## 后果

- **positive**：升级路径是一个可被测试与承诺的工程事实，而不是一句「未来会支持」。
- **negative**：core 用户拿不到 Spring 生态的自动配置便利。这是**刻意**付出的代价：
  core 的定位是「可被任何框架调用的纯逻辑库」，把它绑到 Spring 上会让上面的
  「升级零影响」承诺失效。

## 承诺

我们不承诺「一定在某个时间点升级到 Boot 4.1」。
我们承诺：**当升级发生时，改动被限制在约 15 个文件内，core 与全部契约测试不动**，
并且这条边界由 CI 持续校验，而不是靠人的自觉。
