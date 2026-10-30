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

模型列表不一定穷尽可用模型。订阅模式支持输入列表外的模型 ID，探测成功后才保存。

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

## 登录排错

Windows 上出现 `SunCertPathBuilderException` 时，检查 JDK 的证书信任配置。
需要使用系统信任库时，仅对启动进程设置：

```powershell
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
.\gradlew.bat :server:chatgptAuth --args=check
```

订阅模式固定使用流式 Responses 请求；API 模式的温度和输出 token 上限设置不适用。

协议参考：[登录](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)、
[模型与推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)。
