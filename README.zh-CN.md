# OpenEden

[English](README.md)

OpenEden 是一个 Kotlin/Ktor 运行时，用来构建确定性的连续到离散生物状态机。它把 8D 生理向量、VQ-VAE Codebook、记忆检索、Omega 磨损、ShockState、心跳任务和 LLM Prompt 组装放在同一条可追踪的异步流程里。

## 项目历史

### 贡献历史

[![贡献历史](https://github-readme-activity-graph.vercel.app/graph?username=LightWhite520&repo=openeden&theme=github-compact)](https://github.com/LightWhite520/openeden/graphs/contributors)

### Stars 历史

[![Star History Chart](https://api.star-history.com/svg?repos=LightWhite520/openeden&type=Date)](https://www.star-history.com/#LightWhite520/openeden&Date)

## 项目定位

OpenEden 不是一个把人格写死在代码里的聊天机器人。它的核心目标是提供一个高性能、可测试、非阻塞的后端内核：

- 人格作为数据存在于 `persona/*.yaml`、蒸馏提示词和 Codebook 语义定义中。
- Kotlin 代码只负责数学状态、运行时流程、持久化、调度、验证和适配层边界。
- LLM 只能接收 VQ-VAE Codebook 节点语义或已记录的启发式降级状态，而不是直接把 8D 浮点值当作人格规则解释。
- Dissonance `D` 是运行时派生值，公式为 `D = |L - tau| * (1 - E)`，不会作为第九维存储。

## 核心架构

|   模块    | 说明                                                         |
| :-------: | :----------------------------------------------------------- |
|  `core`   | 纯领域类型和异步契约，包括 8D 向量、VQ-VAE/Codebook 边界、Prompt 输入、检索模式、Omega、ShockState、日记队列和序列化写入。 |
| `server`  | Ktor 服务端、运行时启动、SQLite 持久化、后台 worker、WebSocket 和公共 HTTP API。 |
| `client`  | 面向 CLI 和未来平台前端的 HTTP client 辅助代码。             |
| `trainer` | 训练与模型相关的工程入口。                                   |
| `persona` | 人格、显式周目起点、心跳文案等数据配置。运行时代码不能在 Kotlin 中硬编码人格。 |
|  `data`   | 本地模型、运行时 SQLite 数据库和生成产物的默认位置。         |
|  `docs`   | 设计文档、边界说明和工程笔记。                               |

源码包遵循相同的职责边界：

- `io.openeden.runtime.*` 分离 pipeline、session、state、affect、tick、heartbeat、diary 和 inference。
- `io.openeden.cli.*` 分离应用控制、命令、输入、UI 状态、渲染和终端集成。
- `io.openeden.server.*` 分离启动装配、API DTO/路由/plugin 和 SQLDelight 持久化适配器。
- 测试包和目录严格镜像其验证的生产代码。

运行时主要遵循这些边界：

- Runtime 管理向量数学、D 派生、双空间映射、Omega、ShockState、session Mutex 和 DJL 隔离。
- Prompt Builder 注入英文逻辑约束、中文人格/输出层、Codebook 状态、检索结果和派生 D。
- Surface/Adapter 只调用共享 runtime pipeline。当前第三方目标是 QQ OneBot v11 WebSocket。
- Heartbeat 通过完整流水线生成内部主动回合，并且只投递给配置的 owner target。

## 关键不变量

开发时必须保持以下约束：

- 使用 `suspend`、coroutine 和 Flow 风格接口，避免阻塞 Ktor 请求线程。
- DJL 推理、VQ-VAE 量化、Embedding、双空间坐标映射、ShockState 衰减和 pre-tick 扰动必须放在专用推理调度上下文中。
- `vector_delta` 必须应用到 pre-ticked snapshot，而不是原始向量。
- 所有向量写回必须通过每个 session 独立的 Mutex 串行化，并在锁内重新读取最新状态。
- pre-tick 单维扰动上限为 `MAX_PRETICK_DELTA = 0.25`，并且必须按 emotion confidence 缩放。
- 当 VQ-VAE 不可用或置信度不足时，系统必须使用确定性的 heuristic fallback，并记录 `codebook=HEURISTIC_FALLBACK` trace tag。

## 环境要求

- JDK 21
- Kotlin 2.x
- Gradle Wrapper
- 可选：OpenAI 兼容 LLM endpoint
- 可选：DJL/PyTorch 本地模型文件

打包后的交互 CLI 通过 JLine 原生 provider 接管终端。Windows 下直接读取
Unicode 控制台事件，不要求用户修改 PowerShell 编码或执行特定的 `chcp`。

## 配置

复制示例配置：

```powershell
Copy-Item .env.example .env
```

常用环境变量：

| 变量                            | 说明                                                   |
| ------------------------------- | ------------------------------------------------------ |
| `OPENEDEN_LLM_PROVIDER`         | LLM provider，目前默认 `openai`。                      |
| `OPENEDEN_OPENAI_API_KEY`       | OpenAI 或兼容服务的 API key。                          |
| `OPENEDEN_OPENAI_MODEL`         | LLM 模型名。                                           |
| `OPENEDEN_OPENAI_BASE_URL`      | OpenAI 兼容 endpoint。                                 |
| `OPENEDEN_LLM_REASONING_EFFORT` | 推理强度：`low`、`medium`、`high`。                    |
| `OPENEDEN_SERVER_URL`           | CLI 连接的 server 地址，默认 `http://127.0.0.1:8080`。 |
| `OPENEDEN_RUNTIME_DB_PATH`      | SQLite 运行时数据库路径。                              |
| `OPENEDEN_PERSONA_PATH`         | persona YAML 路径，默认 `persona/default.yaml`。       |
| `OPENEDEN_LOCAL_MODEL_ARTIFACT` | 本地模型 artifact 路径。                               |
| `OPENEDEN_DJL_AFFECT_MODEL_PATH` | Thymos 用户情绪模型缓存目录。                         |
| `OPENEDEN_DJL_AFFECT_MODEL_URL` | Thymos Hugging Face 模型目录 URL。                     |
| `OPENEDEN_OWNER_PLATFORM`       | 可选，心跳 owner 投递平台。                            |
| `OPENEDEN_OWNER_USER_ID`        | 可选，心跳 owner 用户 ID。                             |
| `OPENEDEN_HOST_PLATFORM`        | 可选，权威宿主身份平台。                               |
| `OPENEDEN_HOST_USER_ID`         | 可选，权威宿主身份用户 ID。                            |
| `OPENEDEN_HOST_ADDRESS`         | 可选，仅用于精确匹配宿主的偏好称呼。                   |
| `OPENEDEN_ENABLE_CLI_DIAGNOSTICS` | 是否启用需要 token 的 CLI 诊断接口，默认 `false`。   |
| `OPENEDEN_CLI_DIAGNOSTICS_TOKEN` | CLI 诊断面板专用凭据，不写入本地配置。                |

## 快速开始

安装或下载本地模型 artifact：

```powershell
.\gradlew.bat ensureLocalModelArtifact
```

下载 Thymos 用户情绪模型（首次约 1.2 GB，之后使用本地缓存）：

```powershell
.\gradlew.bat ensureThymosAffectModel
```

启动服务端：

```powershell
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="gpt-5.5"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.openai.com/v1"
.\gradlew.bat :server:run
```

另开一个 PowerShell 窗口启动 CLI：

```powershell
.\gradlew.bat installDist
.\build\install\openeden\bin\openeden.bat
```

`gradlew run` 仅用于开发便利。Gradle 会通过管道代理终端流，因此它不是
交互式行编辑的正式启动路径。

也可以发送一次兼容 chat 请求：

```powershell
.\gradlew.bat run --args="chat --message `"你好`""
```

查看本地 CLI 状态：

```powershell
.\gradlew.bat run --args="state"
```

## CLI 命令

```text
/help
/state
/mode inline|full
/inspect on|off
/clear
/exit
```

交互会话默认使用纵向 inline 模式，完成的消息保留在终端原生 scrollback 中。
`/mode full` 或 `Ctrl+T` 可切换到全屏模式，再切回时不会新建服务端 session 或丢失对话。
`/exit` 只关闭 CLI HTTP client，不会停止 server。

交互输入由 JLine 处理历史记录、光标移动、插入、删除、IME 和 emoji 等补充
Unicode 字符。终端接管与编码约定见 [终端输入说明](docs/terminal-input.md)。
`Alt+Enter` 插入换行，`Tab` 补全命令，Esc 或 `Ctrl+C` 取消生成，空编辑器下
`Ctrl+D` 退出，`Alt+I` 切换诊断。Omega、ShockState 和 8D 向量默认不显示。

诊断有两层门控：面板每次启动都隐藏；服务端仅在
`OPENEDEN_ENABLE_CLI_DIAGNOSTICS=true` 且配置独立 token 时开放。授权面板只返回
安全状态摘要，不会返回 prompt、内部推理、凭据或记忆正文。

交互输入输出完全由 JLine 原生 terminal provider 独占，不依赖 shell 当前代码页。
重定向流与一次性命令固定使用 UTF-8，会消费一次可选输入 BOM，且不输出 BOM
或 ANSI 控制序列，也不提供编码覆盖。外部生产者必须输出 UTF-8，其他管道编码
不受支持。CLI 不执行 `chcp`，也不修改 PowerShell 或全局控制台状态。

首次启动 CLI 时，OpenEden 会创建：

```text
%USERPROFILE%\.openeden\config.json
```

该文件只保存 client 设置。LLM、runtime、模型和 persona 配置属于 server。

## HTTP API

默认 server 监听：

```text
http://0.0.0.0:8080
```

公共接口：

```text
GET  /health
POST /api/v1/chat       {"userId":"local","text":"你好"}
POST /api/v1/chat/stream {"userId":"local","text":"你好","clientRequestId":"..."}
GET  /api/v1/state?userId=local
```

流接口只发送 `accepted`、安全 `stage`、`response.delta`、`completed` 和安全
`error`。支持严格结构化流的 provider 会产生已隔离的公开 response delta；不支持时，
系统在完整 schema 校验通过后一次性发送缓冲结果。

Chat 响应包含：

```json
{
  "requestId": "...",
  "status": "...",
  "response": "...",
  "error": null
}
```

内部向量、`evolutionIndex`、prompt、trace、检索模式和日记细节不会暴露在公共 CLI/API 响应中。

## 构建与测试

```powershell
.\gradlew.bat :server:test
.\gradlew.bat :server:build
.\scripts\verify-cli-unicode.ps1
```

常用 Gradle 任务：

| 任务                                                  | 说明                                                |
| ----------------------------------------------------- | --------------------------------------------------- |
| `.\gradlew.bat ensureLocalModelArtifact`              | 如果缺少本地模型 artifact，则从 Hugging Face 下载。 |
| `.\gradlew.bat :server:run`                           | 启动 Ktor server。                                  |
| `.\gradlew.bat installDist`                           | 构建正式支持的打包交互 CLI。                        |
| `.\gradlew.bat run --args="chat --message \"hello\""` | 发送一次兼容 chat 请求。                            |
| `.\gradlew.bat run --args="state"`                    | 打印本地 CLI session 状态。                         |
| `.\gradlew.bat :server:test`                          | 运行 server 测试。                                  |
| `.\gradlew.bat :server:build`                         | 构建 server 模块。                                  |

默认模型 artifact 来自：

```text
https://huggingface.co/0x4C57/openeden-codebook-base-model
```

可通过 `OPENEDEN_LOCAL_MODEL_ARTIFACT_URL` 覆盖下载地址。

## 会话与数据

- CLI/direct/web 1-on-1 默认 session ID 为 `CLI:<userId>` 或对应平台的 `<platform>:<userId>`。
- 群聊部署使用共享状态模型，session ID 为 `<platform>:<groupId>`。
- 个人 `user_id` 仍会记录为 memory metadata，但不会在群聊中创建独立 ATRI 实例。
- 默认 SQLite 路径为 `data/runtime/openeden.db`。

## 许可证

OpenEden 代码、生成的 codebook artifact 和公开的 OpenEden 模型 artifact 使用 GNU Affero General Public License v3.0 发布。详见 [`LICENSE`](LICENSE)。
