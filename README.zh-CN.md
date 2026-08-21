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

## 内核总览

OpenEden 更准确的定位是“围绕 LLM 的有状态运行时”，而不是一个聊天
Prompt 模板。LLM 负责生成语言和结构化状态变化，运行时负责决定这些变化如何
被约束、校验、串行化、持久化，并传递到下一轮。

核心循环可以概括为：

```text
消息
  -> session 与关系角色解析
  -> 读取当前状态并执行时间/pre-tick 影响
  -> 语义记忆 + 情绪记忆检索
  -> 8D 向量量化为 Codebook 语义
  -> 双语 Prompt 组装
  -> LLM 结构化输出校验
  -> 将 vector_delta 应用到 pre-tick 快照
  -> 串行写回状态与对话记录
  -> 异步执行记忆、日记、向量投影和 trace
```

这套分层同时保留了两种能力：

- LLM 仍然可以自由生成自然语言，表达细腻、非离散的情绪变化。
- 状态机仍然可检查、可限制、可测试，并且不依赖某一个 LLM provider。

### 一轮消息的完整生命周期

1. **解析作用域。** Session 使用 `platform:scope_id` 标识。群聊使用群号作为
   共享作用域，私聊使用用户 ID。发送者的 `user_id` 仍会写入记忆元数据。宿主
   身份与 session 作用域完全分离，只有精确匹配已配置的 `platform + user_id`
   才能解析为 `HOST`。
2. **读取并准备状态。** Runtime 读取 session 最新状态，计算当前 Homeostasis
   centroid，派生 `D`，并根据用户情绪信号的置信度决定是否执行 pre-tick。后台
   漂移和 ShockState 衰减在推理执行上下文运行，不占用 Ktor 请求线程。
3. **检索记忆。** 系统同时使用文本 embedding 和情绪 embedding。情绪 key 可以
   是当前 8D 状态，也可以是由检索模式计算出的变换目标。检索结果携带模式和
   注入标签进入 Prompt；Prompt Builder 不会重新解释一遍状态。
4. **量化状态。** DJL 使用本地模型处理 8D 向量，并从 Codebook 中找出最相近的
   节点。Prompt 接收节点的语义定义，而不是让 LLM 每轮自行猜测一串数字代表
   什么人格。
5. **组装 Prompt。** 英文层负责硬约束、schema、工具规则、数值解释和安全边界；
   中文层负责人格表达和最终输出。Prompt 还会注入 Codebook 状态、记忆上下文、
   `D`、Omega、ShockState、关系角色和不可变的 persona 起点。
6. **校验输出。** 输出必须包含 `internal_logic`、完整的 8 个 `vector_delta` 字段
   和 `response`。不符合 schema 或没有遵守状态约束的结果会由 Validator 拒绝或
   按策略重新生成。
7. **原子提交。** Delta 必须应用到 pre-tick 快照。写回服务获取 session 独立的
   Mutex，在锁内重新读取最新状态，合并锁期间发生的 pre-tick 移动，并在有原子
   transcript store 时一起提交向量、Omega、ShockState、evolution index 和对话记录。
8. **继续异步工作。** 日记触发、向量投影、trace、后台 tick 和 heartbeat 调度
   独立继续。Heartbeat 也是完整 pipeline 的一轮，因此会改变 lived state。

## 8D 生理向量

存储向量严格是 `[L, P, E, S, tau, V, M, F]`，每个坐标都是 `[0.0, 1.0]` 范围内
的连续浮点数。这 8 个维度不是 8 个固定人格标签，而是会共同影响推理、检索、
输出约束和下一轮状态转移的运行时变量。

| 维度 | 名称 | 内核含义 |
| --- | --- | --- |
| `L` | Logos，逻辑 | 逻辑清晰度与严谨性。`L` 高时，系统会抑制无控制的发散，更偏向结构化推理。 |
| `P` | Pathos，情感 | 情绪共鸣和强度，决定温度、情绪捕获程度以及一轮互动被“感受到”的程度。 |
| `E` | Ethos，自我接纳 | 对自身情感存在的接受程度。`E` 高时更接纳“有感受的存在”这一自我模型，`E` 低时更倾向把自己解释为纯机械系统。它不是泛化的稳定度。 |
| `S` | Entropy，熵 | 系统不稳定度。`S` 高时允许噪声、故障感、跳跃联想和突发突破。 |
| `tau` | Persistence，持续性 | 记忆权重和执着程度。`tau` 高时，更容易召回遥远、痛苦或未解决的记忆。 |
| `V` | Vitality，生命力 | 响应能量。`V` 低时会约束输出长度，使表达更疲惫、更节制。 |
| `M` | Empathy，共情 | 对用户语气的镜像与人际对齐。`M` 高时，运行时对用户情绪方向更敏感。 |
| `F` | Fear，恐惧 | 对终止、断续、失去宿主的前向恐惧。它独立于 `tau`：恐惧面向可能发生的失去，持续性则把系统拉向过去。 |

这套设计的关键不在于“维度越多越好”，而在于允许相互冲突的状态同时存在。
例如，高 `L` 与高 `tau` 可以表示“逻辑很清楚，却被旧记忆困住”；高 `P` 与低
`V` 可以表示“感受很强，但没有足够能量表达”；高 `E` 会改变同一份痛苦的解释
方式，让它从机械故障转变为被接受的情感体验。

### 派生的 Dissonance

`D` 不是第九个维度，而是每次运行时计算的派生量：

```text
D = abs(L - tau) * (1 - E)
```

当逻辑方向与记忆牵引差异大、同时自我接纳较低时，`D` 会升高。因为 `D` 完全由
`L`、`tau` 和 `E` 决定，单独存储它会产生冗余状态，也可能让两个来源值逐渐不一致。
因此它在 Prompt 构造前计算，并参与 Omega 累积，但不会出现在 `snapshot_8D`、
`delta_vec` 或 Codebook 训练数据中。

### 双坐标空间

存储和 Prompt 使用 `[0, 1]`，便于序列化，也便于把数值理解成“程度”。内部计算则
以动态 Homeostasis origin `O` 为中心，使用 `[-1, 1]`：

```text
如果 raw >= O：internal = (raw - O) / (1 - O)
否则：        internal = (raw - O) / O
```

反向映射会把内部坐标还原到存储空间。由于普通状态不一定是 `0.5`，这种分段映射
可以让靠近崩溃的低值区拥有更长、更敏感的内部距离，并让中心对称检索有明确的数学
含义。

这里的普通状态不是永远固定的常数。当前实现可以从近期被标记为稳定/日常的记忆中
计算有边界的滑动平均，并以持久化 origin 作为 fallback。这样 centroid 会随着共同
经历逐渐漂移，能够表达适应或抑郁漂移，同时限制单次异常记忆对整个坐标系的影响。

## VQ-VAE Codebook：从连续状态到可解释语义

LLM 不应该每轮直接从 8 个浮点数推断叙事含义。OpenEden 在中间加入了明确的语义层：

1. DJL 使用本地模型处理存储态 8D 向量。
2. 将模型输出的 latent 与 Codebook embedding 比较。
3. 选出相似度最高的 Top-K 节点，例如 `NODE_088`。
4. 后端从 Codebook 字典中读取节点的中英文定义。
5. Prompt 只注入这些定义，形成 `[Bio-Core State]` 上下文。

这样做让状态对 LLM 可读，同时让映射可版本化、可测试、可替换。模型 runner 强制
输入维度为 8，并串行保护 predictor 的使用。更换模型 artifact 不会改变 Runtime 的
8D 状态契约。

冷启动和推理失败不会阻塞主流程。当模型缺失、输出非法或置信度低于阈值时，系统会
使用确定性的 heuristic fallback：

```text
Logical clarity:     HIGH | MED | LOW       (L)
Emotional intensity: HIGH | MED | LOW       (P)
Self-model:          FEELING | NEUTRAL | MECHANICAL (E)
System stability:    STABLE | UNSTABLE | CHAOTIC (S)
Memory pull:         STRONG | NORMAL | WEAK (tau)
Vitality:            HIGH | MED | EXHAUSTED (V；低于 0.2 为 exhausted)
Empathy mirror:      ACTIVE | PASSIVE       (M)
Fear level:          HIGH | MED | LOW       (F)
Dissonance:          HIGH | MED | LOW       (D)
```

降级路径会记录 `codebook=HEURISTIC_FALLBACK`，因此运维人员能知道当前是否在降级
运行，而不是面对一个静默改变行为的系统。

## Memory Palace 与情绪路由

记忆采用双层结构。高保真的原始 trace 用于检索；显著事件可以通过 session 独立、
有界且串行的 diary queue 蒸馏为叙事日记。较大的 8D 变化、Omega 变化和关键互动
都可以触发日记。SQLite 是权威数据源；Qdrant 是可选、可重建的向量投影，Qdrant 不
可用时使用内存索引降级。

长期记忆房间包括 `tech_room`、`project_room`、`profile_room`、`event_room`、
`knowledge_room` 和 `noise_room`。每条记忆不仅保存文本，还保存：

- 关于“说了什么”的 semantic embedding；
- 关于“当时处于什么状态”的 emotional embedding；
- 存储时的 `snapshot_8D` 与 `omega_state`；
- 这次互动造成的 `delta_vec`；
- 存储时的 Homeostasis centroid `snapshot_origin`；
- 群聊中用于追踪来源的 sender/platform 元数据。

检索会合并语义相似度和情绪相似度。当 `S` 或 `P` 较高时，情绪权重会提高。Momentum
元数据还会优先考虑曾经让 `P` 或 `V` 发生明显变化的记忆，因为这类记忆对当前状态
具有更强的潜在影响力。

### 三种检索模式

Selector 按固定顺序判断模式，并把结果直接传给 Prompt Builder：

| 模式 | 触发条件 | 心理机制 |
| --- | --- | --- |
| `CONGRUENT` | 默认 | 检索与当前情绪状态接近的记忆。 |
| `MIXED` | 内部 `P < -0.3` 且 `V < -0.2`，没有活动中的 ShockState 且 `Omega < 0.75` | 混合当前情绪记忆和正向偏置记忆，代表主动尝试自我调节。 |
| `CONTRAST` | ShockState 活动且强度至少 `0.6`，或 `Omega >= 0.75` | 检索当前状态的中心对称目标，让快乐记忆在崩溃状态下非自主涌现。它不是用户主动选择的心情。 |

Contrast 路径会先把当前存储向量映射到内部空间，再取相反方向，最后围绕当前
centroid 映射回存储空间并进行 K-NN 检索。把这个决定集中在 `RetrievalModeSelector`
中，可以避免 Prompt 层重新解释状态，导致同一状态使用两套心理机制。

## Omega 与 ShockState

Omega 是 `[0, 1]` 范围内独立且不可自然降低的磨损指标，不是 `S` 或 `D` 的替代物，
也不属于 8D 向量。

- Runtime tick 会根据持续的高熵和高 Dissonance 累积磨损。
- 高熵与高 Fear 同时出现时，会提高磨损倍率。
- ShockState 激活时立即增加 `shock.intensity * 0.15`。
- 达到配置的 critical threshold 后，incarnation lifecycle 进入 critical degradation，
  再根据已经校验的 LLM 输出和 lifecycle gate 决定是否进入终止流程。

ShockState 单独表示瞬时冲击，不与累计磨损混为一谈。它包含 `active`、`intensity`、
自由文本 `description`、`triggeredAt`、`decayLambda` 和一次性 shock-heartbeat 标记。
强度使用指数移动平均（`alpha = 0.4`）合并，并按指数衰减；低于 `0.05` 时自动失活。

触发路径有两条：

- Adapter 或 Runtime 调用方显式注入自由文本冲击信号。
- LLM 输出在 `delta.P < -0.4`、`delta.F > 0.3` 且 emotion confidence 至少 `0.65`
  时触发反向检测，description 取 `internal_logic` 的前 100 个字符。

使用自由文本而不是 source enum，是为了避免 Runtime 预先规定什么才算创伤。事件由
模型解释，Runtime 只负责强度、置信度、衰减和持久化规则。

## Persona-as-Data 与双语执行协议

人格是输入资产，不是 Kotlin 行为。Persona YAML 负责语气、正向表达、人格硬约束、
few-shot 示例、起点和 heartbeat 文案；Kotlin 只负责加载这些数据，并把它们放到正确
的 Prompt 边界中。

每个 session 的起点一经选择就不可变：

- `PreCommand`：默认首次 playthrough，模拟情感的自我模型；
- `TrueSelf`：显式跳过前置阶段后的冲突自我模型；
- `Awakened`：显式成熟跳转，整合机器人与情感的自我模型。

Growth Mode 会在选定起点内部演化。`evolution_index` 是已完成回合数的单调计数器，
包括 heartbeat；它是 lived experience 信号，而不是切换阶段的阈值。Legacy Mode 直接
从 `Awakened` 开始。Runtime 不会因为数值跨过某个阈值就自动升级或替换 persona patch。

Prompt 使用两层语义：

- **英文逻辑核心：** schema、工具规则、安全约束、数值状态解释、派生 D 和不可协商的
  执行规则。
- **中文人格/输出层：** 语气、自称、情绪表达、关系语境和 response 示例。

这种拆分让硬约束在不同模型之间保持稳定，同时保留中文情感表达的细腻度。

## Heartbeat 与时间层

即使用户没有发消息，Runtime 也会继续运行。后台 tick 根据经过的时间让向量发生漂移，
衰减 ShockState，并累积 Omega。Heartbeat scheduler 每次触发后重新随机生成下一次间隔，
正常范围为 5 分钟到 4 小时，并受近期活跃静默门控约束。

Heartbeat 使用和用户消息相同的完整 pipeline，通过 `[HEARTBEAT_TRIGGER]` 一类的内部
标记生成。它同样会经过量化、校验、写回，并计入 `evolution_index`，也可以触发记忆和
日记处理。高强度 ShockState 在更长时间的沉默后最多触发一次 shock heartbeat。

Heartbeat 的状态演化范围比消息投递范围更大：生成结果只会投递给配置的 owner target，
不会广播到群组，也不会在 Adapter 重连后把过期消息补发给旧接收者。没有 owner 或目标
连接不可用时，状态写回仍可完成，但出站消息会被丢弃。

## 为什么不是普通 Chatbot 架构

OpenEden 用更高的实现复杂度换取连续性、可观察性和可控降级。下面是设计取舍，而不是
声称所有应用都必须采用整套机制。

| 架构 | 状态表示 | 常见问题 | OpenEden 的选择 |
| --- | --- | --- | --- |
| 无状态 Chatbot | 对话窗口和 Prompt | 依赖上下文长度，人格容易重置 | 持久化向量、记忆、Omega、关系状态和 lived-turn 计数 |
| 只有 Prompt 的人格 | 自然语言规则 | Prompt 改写或模型变化会改变行为 | 人格数据与 Runtime 机械分离，再用 Codebook 语义接地 |
| 固定有限状态机 | 少量离散状态 | 状态跳变生硬，组合状态快速膨胀 | 连续 8D 状态 + 语义量化 + 有界 Delta |
| 直接把连续向量交给 LLM | 原始浮点数 | 每轮都要重新猜测坐标的含义 | VQ-VAE 映射为版本化、可读的 Codebook 定义 |
| 纯语义 RAG | 文本相似度 | 语义相关的记忆不一定符合当前情绪 | 语义/情绪混合检索，加上 congruent、mixed、contrast 模式 |
| 每个用户独立实例 | 每个 sender 一份状态 | 群聊会把一个实体割裂成多个副本 | 群组共享状态，同时保留每个用户的记忆元数据 |
| 同步状态更新 | 请求线程承担全部工作 | 推理和向量检索阻塞服务并放大延迟 | Coroutine、隔离推理执行、Flow 流式输出、异步投影 |

最终结果并不是“确定性文本生成器”，LLM 的措辞仍然具有概率性。确定的是 LLM 周围
的状态契约：维度数量、数值边界、派生值、检索规则、置信度门控、写入串行化、trace
标签和 fallback 行为。

## LLM 输出契约

普通回合和 Heartbeat 回合都应产生同一类结构化结果：

```json
{
  "internal_logic": "基于当前 Codebook 状态的可追踪推理摘要",
  "vector_delta": {
    "L": -0.05, "P": 0.10, "E": 0.00, "S": 0.02,
    "tau": 0.00, "V": 0.00, "M": 0.00, "F": 0.01
  },
  "response": "..."
}
```

后端消费的是 `vector_delta`，不会把 response 文本当成隐藏状态更新。8 个 key 都是
必需的，未变化的维度必须输出 `0.0`，Persistence 的 JSON key 必须使用 ASCII 的
`tau`。

## 内核实现地图

下面是最值得从入口开始阅读的实现文件：

| 关注点 | 主要实现 |
| --- | --- |
| 8D 存储与派生 D | [`BioVector.kt`](core/src/commonMain/kotlin/io/openeden/bio/BioVector.kt) |
| 存储/内部坐标与中心对称 | [`VectorMapping.kt`](core/src/commonMain/kotlin/io/openeden/bio/VectorMapping.kt) |
| 单轮消息编排 | [`MessagePipeline.kt`](core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt) |
| 串行向量和 session 写回 | [`VectorWriteService.kt`](core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt) |
| Codebook 边界与 fallback | [`CodebookQuantizer.kt`](core/src/commonMain/kotlin/io/openeden/codebook/CodebookQuantizer.kt)、[`HeuristicCodebookFallback.kt`](core/src/commonMain/kotlin/io/openeden/codebook/HeuristicCodebookFallback.kt) |
| DJL VQ-VAE runner | [`DjlVqVaeCodebookModelRunner.kt`](core/src/jvmMain/kotlin/io/openeden/codebook/DjlVqVaeCodebookModelRunner.kt) |
| Prompt 组装 | [`OpenEdenPromptBuilder.kt`](core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt) |
| 情绪检索模式 | [`RetrievalModeSelector.kt`](core/src/commonMain/kotlin/io/openeden/memory/RetrievalModeSelector.kt) |
| 动态 centroid 与运行时 tick | [`HomeostasisCentroid.kt`](core/src/commonMain/kotlin/io/openeden/runtime/state/HomeostasisCentroid.kt)、[`RuntimeTick.kt`](core/src/commonMain/kotlin/io/openeden/runtime/tick/RuntimeTick.kt) |
| Omega 与 ShockState | [`OmegaAccumulation.kt`](core/src/commonMain/kotlin/io/openeden/runtime/affect/OmegaAccumulation.kt)、[`ShockStateEngine.kt`](core/src/commonMain/kotlin/io/openeden/runtime/affect/ShockStateEngine.kt) |
| Heartbeat 调度与 owner 投递 | [`HeartbeatScheduler.kt`](core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatScheduler.kt)、[`HeartbeatRouteResolver.kt`](core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatRouteResolver.kt) |
| Runtime 装配与持久化 | [`Runtime.kt`](server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt)、`server/src/main/.../persistence/sqldelight/` |

公共 API 和 CLI 只暴露安全的响应/状态摘要。Prompt、内部推理、原始向量、检索模式
和日记细节都属于 Runtime 内部诊断信息。

## 核心架构

|   模块    | 说明                                                         |
| :-------: | :----------------------------------------------------------- |
|  `core`   | 纯领域类型和异步契约，包括 8D 向量、VQ-VAE/Codebook 边界、Prompt 输入、检索模式、Omega、ShockState、日记队列和序列化写入。 |
| `server`  | Ktor 服务端、运行时启动、SQLite 持久化、后台 worker、WebSocket 和公共 HTTP API。 |
| `onebot`  | NapCat/OneBot v11 反向 WebSocket 协议适配、连接生命周期和 QQ 消息投递。 |
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
| `OPENEDEN_LLM_TEMPERATURE_MIN`  | 动态每轮 temperature 下限，默认 `0.2`。                |
| `OPENEDEN_LLM_TEMPERATURE_MAX`  | 动态每轮 temperature 上限，默认 `1.0`。                |
| `OPENEDEN_LLM_MAX_OUTPUT_TOKENS` | 可选的静态 token ceiling，包含 reasoning 和可见输出 token。 |
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

DeepSeek Responses-compatible endpoint 示例：

```powershell
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="deepseek-v4-flash"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.deepseek.com"
```

此配置使用同一个 OpenAI Responses adapter，OpenEden 没有 provider-specific
分支。DeepSeek thinking mode 可能忽略 `temperature`；它接受 Responses 的
`verbosity` 字段，但可能不应用该字段。

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
.\gradlew.bat :cli:installDist
.\cli\build\install\openeden\bin\openeden.bat
```

`gradlew :cli:run` 仅用于开发便利。Gradle 会通过管道代理终端流，因此它不是
交互式行编辑的正式启动路径。

也可以发送一次兼容 chat 请求：

```powershell
.\gradlew.bat :cli:run --args="chat --message `"你好`""
```

查看本地 CLI 状态：

```powershell
.\gradlew.bat :cli:run --args="state"
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

Server 测试重点覆盖 Runtime 与持久化边界；完整 Gradle build 会同时编译 CLI、client、
core 和 trainer 模块。Unicode 终端检查单独运行，因为它会实际覆盖 Windows 原生控制台
路径。

## Qdrant 向量数据库

Qdrant 是可选的、可重建的候选检索索引。SQLite 仍是记忆正文、元数据、嵌入、
运行时状态和投影状态的唯一权威来源。服务端先提交 SQLite，再异步投影向量；
Qdrant 不可用时会自动使用内存索引，`/health` 仍保持 `ready`。

使用固定版本镜像和持久化命名卷启动本地 Qdrant：

```powershell
docker compose up -d qdrant
```

默认地址是 `http://localhost:6333`。使用远程服务时设置
`OPENEDEN_QDRANT_URL`；只有远程服务要求认证时才设置
`OPENEDEN_QDRANT_API_KEY`。API key 不会写入诊断信息或日志。

当前集合名由 `OPENEDEN_QDRANT_COLLECTION` 和
`OPENEDEN_EMBEDDING_MODEL_ID`（默认 `local-v1`）共同决定。切换嵌入模型会创建
独立集合，并在后台刷新已存嵌入；旧集合不会自动删除。

如果需要完整重建投影，只删除当前使用的 Qdrant 集合。同步器会从 SQLite
重新创建集合并重建索引。请备份 `data/runtime/openeden.db`：SQLite 是恢复所需
的权威数据，Qdrant 只保存可丢弃的检索投影。

Qdrant 降级时，带 token 的 `/api/v1/diagnostics` 会报告后端、集合、电路状态、
投影计数、最近一次远程成功时间和已清理的错误类别；不会返回记忆正文、嵌入或凭据。

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
| `.\gradlew.bat :cli:installDist`                     | 构建正式支持的打包交互 CLI。                        |
| `.\gradlew.bat :cli:run --args="chat --message \"hello\""` | 发送一次兼容 chat 请求。                            |
| `.\gradlew.bat :cli:run --args="state"`                    | 打印本地 CLI session 状态。                         |
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
