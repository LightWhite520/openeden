# OpenEden

[English](README.md)

**一个实验性的 Kotlin/Ktor 运行时，让 AI 角色跨对话保留状态和记忆。**

OpenEden 研究过去的互动如何改变 AI 角色之后的回应、选择，以及对自身经历的理解。

运行时在不同回合和对话作用域之间保留同一份内部状态。它影响系统检索哪些记忆、给模型提供什么上下文；经过校验的模型输出再更新状态，供后续回合使用。项目希望检验这种反馈能否让角色的行为保持连续。

## 工作方式

一个 incarnation 跨对话共享 Bio 状态，各对话保留自己的记录和投递上下文。
每轮依次准备状态、生成 Codebook 语义、检索记忆、组装 Prompt，校验 LLM 的回应和
增量提案，再将结果写入 SQLite。

- 八个存储维度为 `[L, P, E, S, tau, V, M, F]`：逻辑清晰度、情感共鸣、
  情感自我接纳、不稳定性、记忆牵引、活力、共情和恐惧，范围均为 `[0,1]`。
- 认知失调 `D = abs(L - tau) * (1 - E)` 在运行时派生，不作为第九维存储。
- VQ-VAE 将状态映射为 Codebook 描述；量化不可用时使用带日志的启发式降级。
  Prompt 接收状态语义，不直接解释原始八维浮点数。
- 记忆检索结合文本和情绪相似度；状态更新有幅度限制，并由 incarnation 锁串行写入。
- 人格表达和起点保存在 `persona/*.yaml`。Growth 默认从 PreCommand 开始；
  TrueSelf、Awakened 是显式选择的起点，不按回合数自动晋级。
- 后台 tick 改变状态；心跳经过完整回合流水线，仅向配置的 owner 投递。

这些状态是工程抽象。长期行为连续性，以及相对简单 Prompt 或 RAG 方案的优势，仍需实证评估。

## 从源码运行

需要 JDK 21。在仓库根目录执行：

```powershell
.\gradlew.bat ensureLocalModelArtifact
.\gradlew.bat ensureThymosAffectModel
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="your-responses-compatible-model"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.openai.com/v1"
.\gradlew.bat :server:run
```

首次下载 Thymos 约 1.2 GB。使用 ChatGPT 订阅而非 API Key 时，见
[登录与模型选择](docs/operations/chatgpt-and-model-selection.md)。
`.env.example` 提供配置参考，服务端和 CLI 不会自动导入 `.env`。

另开一个终端：

```powershell
.\gradlew.bat :cli:installDist
.\cli\build\install\openeden\bin\openeden.bat
```

服务端和 CLI 默认连接地址均为 `http://127.0.0.1:8080`。
服务端端口通过 `OPENEDEN_SERVER_PORT` 修改；客户端地址从
`~/.openeden/config.json` 读取，自定义地址请修改其中的 `serverUrl`。
当前 JVM CLI 不读取 `OPENEDEN_SERVER_URL`。

CLI 通过 HTTP 连接，不负责启停服务端。交互输入使用打包后的 JLine 启动器，
重定向流使用 UTF-8。npm 打包与启动方式见 [npm CLI 说明](npm/README.md)。

## 使用文档

- [运行、CLI 配置、命令与 HTTP API](docs/runtime-cli-server.md)
- [ChatGPT 登录与模型选择](docs/operations/chatgpt-and-model-selection.md)
- [NapCat / OneBot v11 接入](docs/onebot-napcat.md)
- [模型训练与 DJL 配置](docs/local-model-training.md)
- [对话导出与 incarnation 重置](docs/operations/companion-quality-production-rollout.md)

## 开发

| 目录 | 职责 |
| --- | --- |
| `core` | 状态计算、流水线、人格、检索与模型契约 |
| `server` | Ktor 服务端、持久化和运行时装配 |
| `cli` / `client` | 终端界面与 HTTP 客户端 |
| `onebot` | QQ / OneBot v11 适配器 |
| `trainer` | 本地模型训练 |
| `persona` | 人格数据 |

```powershell
.\gradlew.bat build
.\scripts\verify-cli-unicode.ps1
```

工程约束见 [AGENTS.md](AGENTS.md)。
`docs/evaluation/live-ab-judge.md` 和 JSON 场景是 A/B 评测脚本的输入文件，不是用户说明。

## 许可证

代码及公开的 OpenEden 模型、Codebook artifact 使用 [GNU AGPL v3.0](LICENSE)。
