# Prompt Cache Relay Investigation

你是负责 OpenAI Responses API 兼容性和 Prompt Cache 的 Agent。请分析下面的 OpenEden 故障，判断当前中转服务是否真正支持 Prompt Cache，以及怎样可靠判断缓存命中、缓存写入和缓存未命中。

## 背景

OpenEden 使用 Kotlin/Ktor 调用 Responses API。当前服务器配置为：

- Model: `gpt-5.6-luna`
- Base URL: `http://38.175.222.29:8080/v1`
- Prompt caching mode: `auto`
- 请求端点: `${base_url}/responses`

OpenEden 在 `auto` 模式下根据模型名判断 `gpt-5.6-luna` 支持显式缓存，并发送以下字段：

```json
{
  "prompt_cache_key": "<sha256 of stable prompt prefix>",
  "prompt_cache_options": {
    "mode": "explicit"
  },
  "input": [
    {
      "role": "system",
      "content": "<stable logical core>"
    },
    {
      "role": "developer",
      "content": [
        {
          "type": "input_text",
          "text": "<stable persona>",
          "prompt_cache_breakpoint": {
            "mode": "explicit"
          }
        }
      ]
    },
    {
      "role": "developer",
      "content": "<dynamic runtime state>"
    },
    {
      "role": "user",
      "content": "<current user message>"
    }
  ]
}
```

## 已验证事实

1. 使用同一个中转服务、同一个模型，仅发送普通字符串输入和 `prompt_cache_key` / `prompt_cache_options` 时，服务返回 HTTP `200`。
2. 加入上面结构化的 `input_text.prompt_cache_breakpoint` 后，服务返回：

```json
{
  "error": {
    "message": "Upstream request failed",
    "type": "upstream_error"
  }
}
```

HTTP 状态为 `502`。

3. OpenEden 当前把非 2xx 响应转换成普通 `IllegalStateException`，OneBot 适配器最终只记录 `ACTION_FAILED category=UNKNOWN`，所以 QQ 用户看不到回复。
4. 目前没有任何可靠证据证明该中转服务返回了缓存命中或缓存写入指标。

## 请回答

请基于上述事实回答，不要假设这个中转服务完全实现了官方 OpenAI API：

1. `prompt_cache_breakpoint` 是否属于官方 Responses API 的标准字段？它是否可能只被官方 API 或特定上游实现支持？
2. 当前中转返回 HTTP `502 upstream_error` 更可能代表：
   - 中转自身不认识字段；
   - 中转接受字段但上游不支持；
   - 上游支持缓存但当前模型/账户/请求形状不支持；
   - 其他原因？
3. 仅凭 HTTP `200` 能否判断缓存命中？不能的话，应该检查哪些响应字段、SSE 事件或 usage 字段？请区分：
   - cache hit
   - cache write
   - cache miss
   - provider 未提供缓存指标
4. `prompt_cache_key`、`prompt_cache_options` 和 `prompt_cache_breakpoint` 三者分别承担什么作用？它们是否必须成套发送？
5. 对 OpenEden 的 `auto` 模式，推荐什么兼容策略？请比较：
   - 仅官方 `api.openai.com` 自动启用显式缓存；
   - 自定义中转默认使用旧的字符串输入格式；
   - 先发送显式缓存请求，失败后自动降级重试；
   - 完全禁用缓存，等待中转服务明确声明支持。
6. 如果建议降级重试，请说明如何避免重复计费、重复生成、流式响应已经开始后无法重试等问题。
7. 请给出一个最小的诊断请求矩阵，分别测试：
   - 旧格式，无缓存字段；
   - 旧格式，仅 `prompt_cache_key`；
   - 旧格式，`prompt_cache_key` + `prompt_cache_options`；
   - 结构化 `input_text`，带 breakpoint；
   - 连续两次相同稳定前缀、不同用户消息的请求。

## 输出要求

请输出：

1. 结论，明确说明当前是否能证明缓存命中；
2. 对 HTTP `502` 的最可能解释及证据等级；
3. 推荐的 OpenEden 兼容策略；
4. 可执行的请求/响应检查清单；
5. 如果需要修改客户端，请给出精确的字段和分支逻辑，不要直接假设中转服务遵循官方实现。

请不要要求查看或输出任何 API Key、Authorization header 或完整生产 Prompt。
