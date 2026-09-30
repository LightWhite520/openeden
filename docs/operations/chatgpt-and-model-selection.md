# ChatGPT 登录与模型选择

OpenEden 支持两种明确分开的认证方式：`api_key` 使用配置的 API 服务，`chatgpt` 使用官方 Sign in with ChatGPT 授权和账号可用的订阅额度。订阅请求固定发送到公开的 `https://api.openai.com/v1/responses`；失败不会改用 API Key 计费。

## ChatGPT 登录

在仓库根目录运行：

```powershell
.\gradlew.bat :server:chatgptAuth --args=login --console=plain
$env:OPENEDEN_LLM_AUTH_MODE = 'chatgpt'
.\gradlew.bat :server:models --console=plain
```

第一条命令打开浏览器授权，回调仅监听本机 `127.0.0.1` 随机端口，五分钟超时。完成后选择模型，再按原方式启动服务器。永久配置请将 `OPENEDEN_LLM_AUTH_MODE=chatgpt` 写入启动环境；直接运行 Gradle 不会自动导入 `.env`。

其他账号命令：

```powershell
.\gradlew.bat :server:chatgptAuth --args=accounts
.\gradlew.bat :server:chatgptAuth --args="login <已保存的client-id>"
.\gradlew.bat :server:chatgptAuth --args="select <已保存的client-id>"
.\gradlew.bat :server:chatgptAuth --args=logout
```

再次运行 `login` 可添加账号；带 client ID 的登录会核验返回的账号身份。切换账号后重新获取模型列表。Windows 凭据使用当前用户 DPAPI 加密；其他系统使用仅当前用户可读的文件，默认位于 `~/.openeden/chatgpt`，可用 `OPENEDEN_CHATGPT_AUTH_DIR` 更改。访问令牌到期前自动刷新，跨进程串行轮换。退出登录会尝试撤销 refresh token，并删除本地令牌；远端撤销失败时会提示在 ChatGPT 设置中断开连接。

## 获取列表与选择模型

模型目录不一定穷尽可调用模型。2026-09-30 的真实订阅测试中，公开 `/models` 原始响应没有 `gpt-6-luna`，但其 Responses 调用成功。订阅模式允许直接输入目录外的模型 ID，先完成一次最小推理探测，成功后才保存；不会将未验证的模型冒充为 fetch 返回结果。启动时也不会仅因明确配置的模型不在目录里而拒绝服务。

本机选择器无需先启动 OpenEden 服务：

```powershell
.\gradlew.bat :server:models --console=plain                 # 编号/模型 ID 选择；r 刷新；q 取消
.\gradlew.bat :server:models --args=fetch --console=plain    # 只获取并显示列表
.\gradlew.bat :server:models --args="select <model-id>" --console=plain
```

API 模式使用 `OPENEDEN_OPENAI_BASE_URL` 和 `OPENEDEN_OPENAI_API_KEY` 获取 `/models` 的 `data[].id`。订阅模式使用当前账号的 `models[]`，仅显示 `visibility=list` 的条目，展示名称并使用 `slug` 请求模型。保留服务端顺序并去重；不内置猜测的模型列表。API 模式的目录可能同时包含非对话模型，请选择支持 Responses 和结构化输出的模型。

选择保存在 `~/.openeden/models`（可用 `OPENEDEN_MODEL_SETTINGS_DIR` 更改），按服务地址和凭据、或 ChatGPT 账号隔离。保存的选择优先于 `OPENEDEN_OPENAI_MODEL`；无保存记录时使用环境配置。对话、日记和关系评估的新请求均读取新选择，进行中的请求继续使用开始时选定的模型。获取失败不会清除已保存的选择。

## 对话 CLI 中切换

为服务器和 CLI 配置相同的随机私密 `OPENEDEN_MODEL_SETTINGS_TOKEN`，重启以加载该设置，然后使用：

```text
/model
/model refresh
/model next
/model prev
/model 2
/model <model-id>
```

`/model` 获取列表并显示当前模型；编号对应上次显示的列表。管理接口 `GET/POST /api/v1/models` 使用这个独立 bearer token，默认关闭，不能使用它管理账号或重置记忆。远程部署应通过 HTTPS 连接。

## 订阅接口差异与验证范围

Windows 若在令牌交换时出现 `SunCertPathBuilderException`，先验证当前 JDK 与系统证书信任配置。代理或证书链差异可能参与其中，但本次没有通过直连/代理对照确认根因。本次环境通过使用 Windows 系统信任库解决，未关闭 TLS 校验。仅对启动进程设置：

```powershell
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
.\gradlew.bat :server:chatgptAuth --args=check
```

本次真实授权及 `gpt-6-luna` 最小调用、完整运行时单轮均已成功。真实接口返回的 SSE 响应可能缺少 Content-Type；客户端在该情况下仍严格要求有效 SSE 和成功完成事件，不接受 JSON 成功替代流完成。长对话 A/B 结果另行记录。

订阅流的 `response.completed.output` 也可能为空，正文仅在前面的 `response.output_text.delta/done` 事件中。关系评估器现在会保留这些正文，仍要求成功完成事件；不会因为有部分文本就接受失败或中断的流。关系主评估失败时记录 `relationship=EVALUATOR_FALLBACK` 和异常类型，不记录凭据或对话正文。

订阅请求强制 `store=false`、`stream=true`，将 system 消息转换为 developer，发送完整所需上下文，并省略当前官方不支持的 `temperature`、`max_output_tokens`、HTTP `previous_response_id` 等字段。因此 API 模式的温度/最大输出参数不适用于订阅模式。人格数据、VQ-VAE、结构化输出校验及 Bio 状态写回仍使用同一条运行时管线。

订阅请求将按模型、会话及历史 epoch 派生的匿名键同时发送为 `session-id` 请求头和 `prompt_cache_key`。ChatGPT 订阅链路通过 `session-id` 建立缓存路由；仅发送 JSON 字段不能替代该请求头。动态 Bio/新消息不改变该键，压缩换 epoch 会改变它。若服务端明确拒绝 JSON 字段，只重试一次不含该字段的请求，保留会话请求头。路由键及字节稳定的前缀不是供应商命中保证；验收使用实际 `cached_input_tokens / input_tokens` 的加权比例，缺失 usage 保持不可观测。根因与定向验证见[缓存路由诊断](../evaluation/2026-09-30-cache-session-affinity.md)。

自动测试覆盖 OAuth 校验、令牌刷新、订阅请求格式和错误终止、目录获取、模型持久化和管理接口认证。新安装仍需操作者完成浏览器授权；测试替身成功不能视为订阅端到端验收。真实长对话质量由独立 A/B 报告判断。

官方协议参考：

- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
