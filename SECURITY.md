# 安全策略

## 支持版本

| 版本 | 状态 |
|---|---|
| 0.1.x | ✅ 支持 |

## 报告安全漏洞

**请不要用 GitHub Issue 报告安全问题。**

请通过 GitHub 的 [Private vulnerability reporting](https://github.com/nephets/llm-switchyard/security/advisories/new)
提交，或邮件联系维护者。

我们会在收到报告后 **48 小时内**确认收到，**7 个工作日内**给出修复计划或缓解方案。

## 我们特别关注的问题

作为 LLM 网关，以下几类问题我们会**优先处理**：

| 类型 | 为什么重要 |
|---|---|
| **API Key 泄漏** | 网关持有全部供应商密钥，任何泄漏都是全线事故 |
| **审计日志中的 PII 泄漏** | 我们默认不落 prompt 原文，但如果代码路径绕过了这个默认，等同于把用户数据写进了日志系统 |
| **配额绕过** | 配额是本项目的核心承诺，绕过它意味着用户的预算控制失效 |
| **计量失真** | 成本算错会让用户拿错误数据做采购决策，性质上等同于财务数据造假 |
| **反序列化漏洞** | 价格表用 SnakeYAML 的 SafeConstructor 解析，任何绕过都是远程代码执行 |

## 关于 API Key 的使用建议

- **永远用环境变量注入**，不要写进 `application.yml`（它会被提交进 git）
- 本项目已提供 `.gitignore` 与 `.dockerignore` 来降低误提交风险，但这不能替代你的注意力
- 定期轮换密钥；密钥轮换不需要重启网关（读取的是环境变量）

## 关于审计与隐私

本项目的默认设计：

- 审计**只落 payload 的 sha256 摘要**，不落 prompt 原文
- 需要原文时必须显式开启 `llm.switchyard.audit.capture-payload=true`，且**由用户自行完成脱敏**
- 响应头只暴露成本与 token 数，不含任何用户数据

如果你所在组织有更严格的合规要求（等保、个保法、金融监管），
建议实现自己的 `AuditSink` 而不要开启 `capture-payload`：

```java
@Bean
AuditSink switchyardAuditSink() {
  return event -> myComplianceStore.write(redact(event));  // 脱敏在你自己手里
}
```

## 依赖安全

CI 使用 Dependabot 监控依赖更新（`.github/dependabot.yml`）。
框架版本锁定为 Spring Boot 3.5.16（3.5 线最后一个 OSS 补丁），
相关背景与升级路径见 [ADR-0005](docs/adr/0005-upgrade-path.md)。
