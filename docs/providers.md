# 供应商支持矩阵

本文档回答两个问题：**我们支持哪些供应商**，以及**每个供应商的能力边界在哪**。

> ★ 每一行的能力声明都有对应的自动化测试作为证据，测试类名见最后一列。
> 没有测试支撑的能力声明不会出现在本文档里。

## 总览：3 个适配器，9 家供应商

| 协议 | 适配器 | 供应商 | 端点 |
|---|---|---|---|
| OpenAI 兼容 | `OpenAiCompatibleProvider` | openai / 通义 / 智谱 / DeepSeek / Kimi / 豆包 / 硅基流动 | `/v1/chat/completions` |
| Anthropic 原生 | `AnthropicProvider` | anthropic | `/v1/messages` |
| Ollama 原生 | `OllamaProvider` | ollama | `/api/chat` |

**为什么 7 家共用一个适配器**：它们的 API 都是 OpenAI 兼容协议，唯一差别是 `base-url` 与模型名。
为每家单独写适配器意味着 6 份几乎相同的代码 —— 收益极低，且六份实现必然在细节上漂移
（总有一份忘了映射 `cached_tokens`）。

## 能力矩阵

图例：`✅` 有测试覆盖 ｜ `—` 该供应商当前协议**不返回**此字段

| 供应商 | providerId | 默认 base-url | 流式 | 结构化输出 | 工具调用 | 精确计量 | 推理 token | 缓存命中 | 测试证据 |
|---|---|---|---|---|---|---|---|---|---|
| OpenAI | `openai` | `https://api.openai.com` | ✅ | ✅ | ✅ | ✅ | — | ✅ | `ProviderContractIT#openai` |
| 通义千问 | `dashscope` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | ✅ | ✅ | ✅ | ✅ | — | ✅ | `ProviderContractIT#dashscope` |
| DeepSeek | `deepseek` | `https://api.deepseek.com` | ✅ | — | ✅ | ✅ | ✅ | ✅ | `ProviderContractIT#deepseek` |
| 智谱 GLM | `zhipu` | `https://open.bigmodel.cn/api/paas/v4` | ✅ | — | ✅ | ✅ | ✅ | — | `ProviderContractIT#zhipu` |
| Kimi | `moonshot` | `https://api.moonshot.cn/v1` | ✅ | ✅ | ✅ | ✅ | — | ✅ | `ProviderContractIT#moonshot` |
| 豆包 | `doubao` | `https://ark.cn-beijing.volces.com/api/v3` | ✅ | — | ✅ | ✅ | — | ✅ | `ProviderContractIT#doubao` |
| 硅基流动 | `siliconflow` | `https://api.siliconflow.cn/v1` | ✅ | ✅ | ✅ | ✅ | — | — | `ProviderContractIT#siliconflow` |
| Anthropic | `anthropic` | `https://api.anthropic.com` | ✅ | — | ✅ | ✅ | ✅ | ✅ | `ProviderContractIT#anthropic` |
| Ollama | `ollama` | `http://localhost:11434` | ✅ | — | ✅ | ✅ | — | — | `ProviderContractIT#ollama` |

**`—` 不是「不支持」，而是「供应商不返回这个字段」**。此时：

- `reasoningTokens` 恒为 `0`，且 `reasoningEstimated = true` —— 我们**不假装有数据**；
- `cachedInputTokens` 恒为 `0`，按输入全价计费。

用户若知道某家私有部署其实返回了这些字段，可以自行扩展 `OpenAiWire` 的 DTO。

## 三套协议的差异（这是必须独立适配的原因）

### OpenAI 兼容系

```jsonc
// 请求：★ 必须带 stream_options.include_usage，否则整条流没有 usage
{"model":"gpt-5-mini","stream":true,
 "stream_options":{"include_usage":true},
 "messages":[{"role":"user","content":"你好"}]}

// 流式分片：增量在 choices[].delta.content（非流式在 .message.content）
data: {"choices":[{"index":0,"delta":{"content":"你好"}}]}

// 非流式：tool_calls 挂在 message 内部，不在 choices[] 层
{"choices":[{"message":{"role":"assistant","content":null,
  "tool_calls":[{"id":"call_1","function":{"name":"search","arguments":"{...}"}}]}}]}

// usage 有两层嵌套，★ cached_tokens 与 reasoning_tokens 都在这里
"usage":{"prompt_tokens":1000,"completion_tokens":200,
  "prompt_tokens_details":{"cached_tokens":400},
  "completion_tokens_details":{"reasoning_tokens":50}}
```

### Anthropic

```jsonc
// system 是顶层独立字段，不在 messages 里
{"model":"claude-sonnet-4-5","system":"你是客服助手",
 "max_tokens":1024,  // ← Anthropic 的 max_tokens 必填
 "messages":[{"role":"user","content":"你好"}]}

// 认证走 x-api-key + anthropic-version，不是 Authorization: Bearer

// 缓存用双字段：写入与读取分开
"usage":{"input_tokens":1500,"output_tokens":250,
  "cache_creation_input_tokens":0,   // 写入缓存
  "cache_read_input_tokens":1000}    // 命中缓存 ← 对应 OpenAI 的 cached_tokens

// 流式：event: + data: 成对；★ usage 分两处到达
data: {"type":"message_start","message":{"usage":{"input_tokens":1200,"output_tokens":1,...}}}
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":350}}
//          ↑ message_start 的 output_tokens 是占位值 1；message_delta 才是真实输出量
//            写「先到者优先」会让每次流式的输出 token 都被记成 1
```

### Ollama

```jsonc
// ★ 字段名与 OpenAI 完全相反
{"model":"qwen3:8b","stream":true,
 "options":{"num_predict":512},      // ← max_tokens 在这里叫 num_predict
 "messages":[{"role":"user","content":"你好"}]}

// 计量字段也是反的
"prompt_eval_count": 777,   // ← 对应 OpenAI 的 prompt_tokens
"eval_count": 123           // ← 对应 OpenAI 的 completion_tokens

// ★ 流式是 ndjson：每行一个裸 JSON，没有 data: 前缀，以 done:true 的分片结束
{"model":"qwen3:8b","message":{"role":"assistant","content":"你好"},"done":false}
{"model":"qwen3:8b","message":{"content":"世界"},"done":true,"done_reason":"stop",
 "prompt_eval_count":600,"eval_count":90}
```

> 如果 mock 或真实服务在这里偷懒返回 SSE，适配器会拿到**空响应且不报错** ——
> 这类静默错误正是本项目最需要防的，因此 `OllamaProvider` 的契约测试专门断言 ndjson 格式。

## 新增一个供应商

三步：

**1. 在 `ProviderCatalog` 注册**

```java
entries.put("myvendor",
    new ProviderCatalogEntry(
        "myvendor",                     // alias：用户在配置里写的名字
        "myvendor",                     // providerId：小写，进 span 与配额 key
        ProviderCatalogEntry.Protocol.OPENAI_COMPATIBLE,   // 若是 OpenAI 兼容，只需换 base-url
        "https://api.myvendor.com/v1",
        new ProviderCapabilities(Set.of(
            Capability.STREAM, Capability.TOOL_CALL, Capability.METERING)),
        List.of("my-model-large", "my-model-small")));
```

**2. 如果不是 OpenAI 兼容协议**，实现 `ChatProvider`（必要时同时实现
`ChatProvider.StreamingChatProvider`），并在 `RoutedChatModelFactory#resolveProvider`
的 `switch` 里加一个分支。

**3. 加契约测试**

```java
class MyVendorIT extends AbstractProviderContractTest {
    @Override protected String baseUrl() { return "http://localhost:" + wireMock.port() + "/v1"; }
    // 覆写响应样本与能力断言即可
}
```

`AbstractProviderContractTest` 保证「多家供应商行为一致」不是一句口头承诺：
任何一家在 usage 解析、错误码映射、流式处理上的偏差都会立刻让测试变红。

## 已知限制（诚实说明）

- **不支持供应商私有的 usage 扩展字段**（如 vLLM 自定义的 `num_cached_tokens`）。
  需要时按需扩展 `OpenAiWire.Usage`。
- **不支持 Azure OpenAI / Bedrock / Vertex 这类「OpenAI 兼容但鉴权不同」的形态**。
  它们需要不同的认证头（`api-key` 而非 `Authorization`），可通过
  `OpenAiCompatibleProvider` 的 `apiKeyHeader` 参数接入，但未做端到端测试。
- **不支持 Embedding / Image / Audio 端点**。首版只做 chat completions，
  `Usage` 契约里为 embedding 预留了 `FALLBACK` 档位。
- **价格表需要自行核对**。内置表多数条目是社区估算值（`source: ESTIMATED`），
  控制台会显式标注。**请以你自己的供应商账单为准。**

## 关于 Spring AI 的 provider starter

本项目**不使用** `spring-ai-starter-model-*`，改为自己实现 wire 层。原因见
[ADR-0006](adr/0006-own-wire-dto.md)：Spring AI 的 `OpenAiApi` 会丢弃
`prompt_tokens_details.cached_tokens` 与 `completion_tokens_details.reasoning_tokens`，
而这两个字段是缓存折扣计价与推理成本的依据 —— 直接丢弃会让「计量精度」这个核心卖点失效。
