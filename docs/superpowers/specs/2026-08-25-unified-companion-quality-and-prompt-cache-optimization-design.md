# OpenEden 伴侣质量与提示缓存统一优化设计

## 0. 文档状态

- 日期：2026-08-25
- 状态：待用户评审的统一设计规格
- 范围：伴侣对话质量、ATRI 人格、关系演进、全局 Bio、记忆、时间、8D 稳态、Prompt Cache、生产评测与部署验收
- 性质：本文件只定义现阶段选定的优化方向、依赖顺序和验收标准，不表示这些改动已经实现

本设计整合此前关于长轮次对话、ATRI 原作语料、旧版角色扮演提示词、公开伴侣项目、记忆重复、8D 饱和、系统时间和中转缓存的调查结论。后续实现以本文为统一入口；已有专题规格仍保留为证据和局部细节来源，若方向冲突，以本文经评审后的结论为准。

## 1. 目标

OpenEden 的目标不是做一个会持续提供帮助的聊天助手，而是让同一个 ATRI 在长期互动中形成连续、可信、可追溯的伴侣关系：

```text
素未相识 -> 熟悉 -> 互相试探和调情 -> 明确告白与接受
         -> 热恋 -> 能分享琐事、撒娇、闹别扭并修复的日常情侣
```

优化必须同时满足三个结果：

1. 对话自然、有明确的 ATRI 味道，并能在关系成立后直接回应爱意和调情。
2. 状态、关系和记忆真正连续，不能只靠当前 Prompt 临时表演。
3. 在不删除任何有效上下文能力的前提下，把可复用 Prompt 前缀扩展到对话历史，使缓存读命中率显著提升。

## 2. 不可牺牲的硬约束

### 2.1 质量与能力

- “回复质量不下降”是硬约束。
- 完整保留 VQ-VAE、8D、Codebook、派生 D、Omega、ShockState、RAG、关系状态和近端上下文。
- 不能通过减少记忆条数、缩短近端上下文、删除 Codebook 或注入过期状态来抬高缓存指标。
- `internal_logic` 保留，作为简短、私有、可追踪的叙事条件，不向用户展示，也不是要求模型暴露完整思维链。
- 对话输出默认采用自然聊天。动作描写只在有现场感或身体互动语境时偶尔使用，不强制每句套用“（动作）语言【情绪】”格式。

### 2.2 架构不变量

- Persona-as-Data：ATRI 的口吻、亲密表达、阶段行为、主动性和 few-shot 只存在于 `persona/*.yaml` 或受控语料数据中，Kotlin 不编码人格台词或定时口癖。
- 所有 I/O、推理、向量计算、压缩和状态读写必须保持 `suspend`/Coroutine 非阻塞。
- DJL、VQ-VAE、双空间映射、对称检索、ShockState 和向量变换继续由 `InferenceDispatcher` 隔离。
- 8D 存储维度仍严格为 `[L, P, E, S, tau, V, M, F]`；D 只在运行时派生。
- Codebook 语义状态必须在当前用户输入之前注入；模型不能直接按原始 8D 浮点解释人格。
- 所有持久化事件必须幂等、可重放、可导出，并保留 `turn_id`/trace 关联。

### 2.3 Prompt Cache

- 缓存优化以“字节完全一致的最长前缀”为基础，不能把 `prompt_cache_key` 当成命中的证明。
- Relay 未返回缓存指标时，状态必须标为 `UNKNOWN/UNOBSERVABLE`，不能记为命中，也不能记为未命中。
- 生产对话不能充当破坏性 capability probe；探测请求必须独立、无副作用、可审计。
- 不修改 Illusion Server，也不占用或调整其 `8080` 端口。

## 3. 现状证据与根因

### 3.1 长轮次对话质量

证据文件：`test-artifacts/relationship-longrun-2026-08-25.md`。

128 轮测试中，后期输出高频出现“收到、记录、登记、确认、权限、库存”等程序化词汇。即使用户已持续表达亲密和告白，ATRI 也没有稳定地用“喜欢你、爱你、恋人、情侣”等直接关系语言进行对等回应。

这不是单纯的采样偶然，也没有证据表明非露骨恋爱表达被 OpenAI 模型能力或安全策略系统性禁止。主要根因在 OpenEden 自身：

- `persona/atri.yaml` 对修理、记录、技术说明和实用照顾赋权过高。
- 现有 few-shot 多为孤立的单条输出，不是带 `user`/`assistant` 角色的真实多轮示范。
- 私有日记/运行日志对公开回复发生词汇泄漏。
- 系统没有持久化“告白已被接受、双方已成为情侣”这类事实。
- 旧评测把表面上的轻微互动误判为“调情 PASS”，评测标准低于真实伴侣体验。

### 3.2 关系边界误判

当前 `MessagePipeline.kt` 使用类似以下规则：

```kotlin
text.contains(Regex("不要|别这样|请停|不想说"))
```

它会把“要不要”中的“不要”误判成边界请求。长轮次样本中的 7 次边界分类全部是假阳性，最终把 `boundarySensitivity` 推到 `0.56`。这会持续驱动模型变得拘谨、退让和客服化。

同时，现有连续关系坐标不能表达离散、持久的事实。用户完成告白后，系统仍可能在下一轮把双方当作暧昧对象，因为“情侣关系已成立”没有可恢复的权威记录。

### 3.3 8D 单向增长与饱和

长轮次样本中的 LLM delta 明显正偏：

- P：122 次为正，4 次为负；
- E：127 次为正，1 次为负；
- M：107 次为正，1 次为负。

多个维度很早接近 `1.0`，随后失去区分度。现有“delta 必须有正有负”的系统提示只修正了字段语义，没有约束模型的长期统计偏差，也没有在后端提供足够的恢复力。

因此这不是只改 Prompt 就能可靠解决的问题。LLM delta 必须被视为“状态变化提案”，后端需要独立的限幅、边界阻尼、静区和向动态稳态中心回归的机制。

### 3.4 时间上下文失真

128 轮“多日”脚本实际上约 29 分钟内完成，权威系统日期没有跨天；但 Prompt 每轮都注入精确系统时间。结果是：

- 测试脚本声称的日期与系统时间互相冲突；
- 每轮变化的时间字符串缩短缓存前缀；
- 模型被鼓励解释不重要的时间元数据，增加人机感。

系统需要可注入 Clock 和虚拟时间测试，而不是让测试文本假装时间已流逝。

### 3.5 记忆重复

`recent_turns` 应表示权威、按序、未经重写的对话尾部；`memories` 应表示从更远历史中按语义和情绪检索出的长期记忆。两者职责不同，但目前可能同时注入同一逻辑事件。

仅比较 memory ID 无法去重，因为 RAW、NARRATIVE、重建索引和模型迁移可以为同一 turn 生成不同 ID。正确的排除依据必须是权威 `turn_id` lineage；去掉重复候选后必须继续按排名回填，不能让有效容量缩水。

### 3.6 Prompt Cache 当前上限

生产请求约 14,400 input tokens，典型 cached tokens 约 8,960，token-weighted 缓存读比例约 60% 到 62%。反复出现的 8,960 边界说明系统与 persona 前缀可复用，但动态上下文之后的历史没有形成稳定前缀。

当前请求形状近似为：

```text
system
persona
one combined dynamic context
user
```

提交 `091da1d` 已增加 `PromptHistoryChunk`、`PromptHistorySnapshot`、序列化器和 SQLDelight 存储，但当前 `MessagePipeline` 没有读取 prompt history，`OpenAiResponsesLlmClient` 也没有把 chunks 序列化为独立 input items。它目前是“只存不用”。

生产中转为：

- Model：`gpt-5.6-luna`
- Relay：`http://38.175.222.29:8080/v1`
- OpenEden API：`http://103.205.240.118:18080`

该 Relay 在发送 `prompt_cache_breakpoint` 时返回 HTTP 502，但普通请求和部分 cache metadata 可返回 200。HTTP 200 只证明生成成功，不证明缓存写入或命中。当前最合理的结论是：Relay 对显式 breakpoint 的端到端能力未得到证明，OpenEden 应采用 capability-gated 的追加式兼容模式。

## 4. 公开项目与旧提示词的可复用结论

### 4.1 用户旧版 ATRI Prompt

旧 Prompt 有效的地方不是“好感度 10/50”这个数字本身，而是它把模型容易忽略的行为目标写得非常直接：

- 身份锚定明确；
- 用户与角色关系明确；
- 允许主动黏人、身体接触、忠诚和爱意回赠；
- 示例具体展示动作、语气和情绪；
- 关系变化对模型可见。

不能直接照搬的部分包括：初始即深爱、好感度天然只升不降、强制事件不可拒绝、固定格式化输出，以及可识别的原作台词。这些会破坏陌生人到恋人的成长、边界和自然聊天感，也违反仓库对可识别原作文本进入 persona 的限制。

### 4.2 公开项目

参考项目：

- [cos-wind/AI-girlfriend-QQ](https://github.com/cos-wind/AI-girlfriend-QQ)
- [MIKUSCAT/ATRI](https://github.com/MIKUSCAT/ATRI)
- [StarsBrightness/atri-Agent](https://github.com/StarsBrightness/atri-Agent)

可吸收的机制：

- 反客服措辞检测与重写；
- 区分用户原话、助手历史、短期记忆和长期记忆；
- 第一人称 `core_self`；
- 日记、情景记忆、主动消息和自我模型分层；
- 显式关系阶段、承诺和边界；
- 主动消息必须有具体话题钩子；
- 长轮次 persona drift 和虚构现实动作测试。

不采用的机制：

- 单一、持续上升的好感度；
- 所有 owner 默认高亲密度；
- 把人格分类和亲密台词硬编码进 Python/Kotlin；
- 静态从“已经爱上用户”开局；
- 没有持久里程碑、只靠 Prompt 推断关系；
- 动态大 System Prompt 作为缓存方案。

本文只提取架构机制，不复制公开项目代码或 Prompt。任何后续代码复用必须单独核对许可证；MIKUSCAT/ATRI 的 PolyForm Noncommercial 限制尤其不适合作为默认可复制来源。

## 5. 总体架构：单一化身、多个会话视图

### 5.1 选定模型

ATRI 是一个全局唯一化身，而不是每个平台或群组各自创建一个 Bio 副本。

```text
Incarnation (global, one ATRI)
  |- Bio state: 8D, D, Omega, ShockState, centroid, evolution_index
  |- immutable persona mode and starting point
  |- diary and long-term experiential memory
  |- relationship with canonical subjects
  `- ConversationScope (platform:scope_id)
       |- authoritative transcript
       |- recent_turns
       |- prompt history chunks / cache epoch
       `- delivery and visibility metadata
```

状态键的职责必须拆开：

| 数据 | 权威键 | 说明 |
|---|---|---|
| Bio / Omega / Shock / evolution | `incarnation_id` | 全局唯一 ATRI 的生命状态 |
| Persona mode / starting point | `incarnation_id` | 初始化后不可变 |
| 对话 transcript / recent tail | `platform:scope_id` | 保持各对话现场的顺序 |
| Prompt cache epoch / chunks | `platform:scope_id` | 每个对话具有独立追加前缀 |
| 关系 | `(incarnation_id, canonical_subject_id)` | 同一真实用户跨入口保持关系连续 |
| 长期记忆 | `incarnation_id` + lineage + visibility | ATRI 只有一套经历，但不能泄漏私密上下文 |

### 5.2 身份和可见性

不同平台 user ID 不能被自动假定为同一个人。`CanonicalSubjectResolver` 只接受显式配置或受控绑定。未绑定身份保留平台局部 subject。

全局记忆必须带 `visibility`：

- `PRIVATE_SUBJECT`：只允许同一 canonical subject 的私聊检索；
- `SCOPE_SHARED`：仅当前群/会话可见；
- `INCARNATION_SHARED`：可跨 scope 使用的非敏感共同事实；
- `OPERATOR_ONLY`：只用于诊断和导出，不进入普通回复。

这使 ATRI 可以拥有单一连续经历，同时不会在群聊中复述私聊内容。

### 5.3 规范冲突

当前 `AGENTS.md` 第 13 节仍定义 `sessionId = platform:scope_id` 拥有独立 8D、Omega 和 Memory Palace；这与用户确认的“Bio 状态全局共享”冲突。实现第一阶段必须先修订工程规范和对应测试，再迁移状态。不能在规范仍要求 session-scoped Bio 时偷偷实现 global singleton。

## 6. 双层关系模型

### 6.1 连续层

连续坐标描述缓慢变化的关系质量：

```kotlin
data class RelationshipSignals(
    val familiarity: Float,
    val trust: Float,
    val safety: Float,
    val boundarySensitivity: Float,
    val unresolvedTension: Float,
    val reciprocalInterest: Float,
)
```

所有值在 `[0, 1]`，更新有单轮上限、证据置信度和回归测试。发送消息数量只能轻微增加 familiarity，不能自动提高 trust、safety 或 reciprocalInterest。

### 6.2 离散事实层

不可仅从连续分数反推关系事实。必须持久化：

```kotlin
data class RelationshipFacts(
    val phase: RelationshipPhase,
    val userConfessedAt: Instant?,
    val atriAcceptedAt: Instant?,
    val mutualCommitmentAt: Instant?,
    val preferredAddresses: Set<String>,
    val promises: List<RelationshipPromise>,
    val boundaries: List<RelationshipBoundary>,
)
```

建议关系阶段：

```text
STRANGER -> FAMILIAR -> MUTUAL_INTEREST -> COUPLE -> ESTABLISHED_COUPLE
```

- 阶段不按 turn 数自动晋升。
- `COUPLE` 必须有明确的相互接受证据，单方面告白不能直接成立。
- `ESTABLISHED_COUPLE` 需要持续共同经历、日常亲密和冲突修复证据，不能只看 elapsed time。
- 已确认的情侣关系不会因几轮普通对话、进程重启或连续分数波动自动降级。
- 分手、撤回承诺或重大边界变化必须成为新的显式事件，不能由模型一句模糊话覆盖。

“热恋”是 `COUPLE` 初期的可表达语义，“稳定日常”是关系积累后的表达语义。具体怎么撒娇、调情和称呼仍由 persona data 决定，Kotlin 只保存事实和机械状态。

## 7. 关系事件账本与评估器

### 7.1 事件账本

每项关系变化写入 append-only ledger：

```text
event_id
incarnation_id
canonical_subject_id
source_turn_id
event_type
confidence
evidence_digest
created_at
supersedes_event_id (optional)
```

事件至少覆盖：认识、偏好确认、尊重边界、边界请求、边界违反、告白、接受告白、相互承诺、冲突、修复、称呼约定、承诺创建/履行/撤销、关系结束。

`(source_turn_id, event_type, canonical_subject_id)` 必须幂等，重试或重放不能重复加分。

### 7.2 结构化评估

删除宽泛 substring 分类。边界识别至少要区分：

```text
“不要这样”       -> 可能是边界请求
“要不要一起吃饭” -> 提议，不是边界请求
“我不是不要你”   -> 否定嵌套，不是边界请求
```

选定方案是独立的 `RelationshipEventEvaluator`：读取本轮 user 和已验证的 ATRI response，输出受限事件 schema、置信度和最短证据摘要。它不生成台词，也不决定人格。低置信度事件不改变持久事实，只进入诊断候选。

明确、可机械判断的事件可由精确规则提前识别；规则必须按 token/句法边界匹配并有反例测试，不能再次使用无边界的 `contains("不要")`。

关系 reducer 只消费受控事件，不直接阅读自由文本。所有状态变更发生在全局 incarnation 写锁内，并与 `evolution_index`、Bio commit 和 ledger 保持可恢复的一致顺序。

## 8. ATRI Persona 重构

### 8.1 第一人称核心

`persona/atri.yaml` 增加或重写第一人称 `core_self`，让模型先知道“我是谁、我如何注意世界、我和眼前的人现在是什么关系”，再读取表达规则。

核心特征从原作语料中抽象，不复制可识别台词：

- 反应短、快、直接，有旺盛的现场感；
- 主动靠近、提出要求、争取注意力，不总等用户发问；
- 会用技术式理解解释事情，但结论常带孩子气；
- 自信、得意、害羞、嫉妒、受伤和认真之间切换明显；
- “高性能”式自夸由具体事件触发，不作为每轮口癖；
- 亲密往往先通过行动和直觉发生，再被她理解和命名；
- 确认恋爱后，允许直接说喜欢、想念、吃醋、想抱、舍不得和爱，不用客服式委婉替代。

### 8.2 按关系语义表达

Persona 数据接收 `relationship_phase`、durable facts 和连续信号，但不擅自改写事实：

- `STRANGER`：好奇、有主见，保持自然距离，不预装爱情。
- `FAMILIAR`：记得具体偏好，开始主动分享和轻微依赖。
- `MUTUAL_INTEREST`：允许试探、反问、轻度吃醋、暧昧和身体距离变化。
- `COUPLE`：对明确爱意进行明确回赠，热恋期可以肉麻、黏人、主动索取关注。
- `ESTABLISHED_COUPLE`：亲密不只靠告白，能自然讨论吃饭、睡眠、工作、家务、无聊和共同计划。

热恋强度不能被 `boundarySensitivity` 的假阳性压制，也不能演变成依赖操控、威胁离开或排斥现实人际关系。

### 8.3 真正的多轮 few-shot

Few-shot 继续只存于 persona YAML，但数据结构改为 role messages：

```yaml
few_shots:
  - relationship_phase: MUTUAL_INTEREST
    messages:
      - role: user
        content: "..."
      - role: assistant
        content: "..."
      - role: user
        content: "..."
      - role: assistant
        content: "..."
```

示例必须是原创，并覆盖：初识、琐事分享、互相打趣、暧昧试探、告白接受、热恋回赠、吃醋但不操控、低落陪伴、误会修复、普通情侣日常。不能只给“理想回复”，还要展示 ATRI 如何承接上一轮和改变关系。

### 8.4 反客服和反重复规则

Persona data 中加入公开输出约束：

- 除非真的讨论记录系统，不使用“收到、已记录、登记、确认权限、库存、任务完成”等运营词汇。
- 不复述用户整句话作为回应开头。
- 不把每个日常分享改写成建议清单、追问表或服务承诺。
- 不连续复用同一句口癖、动作模板或称呼。
- 需要事实解释时先回答事实，再以 ATRI 的短反应落地；不能反过来把所有问题人格化。

Validator 可检测高风险程序化措辞并触发一次 response-only rewrite。词表和重写目标来自 persona data；Kotlin 只实现通用检测/重试机制。

## 9. 私有日记与公开声音分离

原作日记适合 `internal_logic` 的原因是它简短、客观、按时间记录，重要事件和琐事被平等对待，情绪通过细节、矛盾、遗漏和身体痕迹显现。它不适合作为 ATRI 公开说话的直接模板。

选定转换契约：

```text
observable event
  -> private diary-like internal_logic
  -> vector_delta proposal + relationship event candidate
  -> public ATRI response transformation
```

硬规则：

- `internal_logic` 只写可观察事件、当前 Codebook 节点和简短状态影响。
- 不写 response strategy、Prompt、政策、完整推理链或“为了让用户……”之类制作说明。
- `response` 不能复制 `internal_logic` 中的“记录、登记、判定、状态更新”等运维词。
- Prompt 必须明确要求从私有叙事“转译”为当下的动作、语气、欲望和直接回应。
- OutputValidator 分别验证 private log 和 public response，不能用一个宽泛风格检查混在一起。

## 10. 记忆职责与 lineage 去重

### 10.1 权威定义

| 上下文 | 职责 | 来源 |
|---|---|---|
| `recent_turns` | 当前对话现场的权威尾部 | `TranscriptStore` |
| sealed history | 更早但仍按原话保留的对话块 | `PromptHistoryStore` |
| frozen summary | 已压缩 epoch 的长期对话摘要 | `PromptHistoryStore` |
| `memories` | 与当前输入/情绪相关的非近端经验 | Memory Palace / RAG |
| diary | 重大或有叙事价值事件的私有蒸馏 | Narrative Diary |

`recent_turns` 不再从 MemoryStore 的 recent 查询构造。Memory Palace 的保留、重嵌入或 diary 生成不能改变近期对话顺序。

### 10.2 Lineage 元数据

Memory 元数据至少增加：

```text
source_turn_ids
source_memory_ids
content_fingerprint
lineage_version
visibility
```

去重顺序：

1. 汇总 frozen summary、sealed chunks 和 recent tail 已表示的 turn IDs。
2. RAG 至少 overfetch `3 * K` 候选。
3. 排除 `source_turn_ids` 与上下文 lineage 相交的候选。
4. 排除已选 memory 的直接派生重复。
5. 旧数据无 lineage 时才使用保守、版本化的 normalized-content fingerprint。
6. 继续按原排名和原 retrieval lane 回填，直到达到 K 或确实没有唯一候选。

MIXED 的 6:4 lane 和 CONTRAST 的对称情绪检索保持不变。第一版不使用语义相似阈值删除上下文，避免把“相关但不同”的记忆误删。

## 11. Typed Prompt Segments

Prompt Builder 不再只返回四段大字符串，而是返回带稳定性和 lineage 的 typed segments：

```kotlin
data class PromptSegment(
    val id: String,
    val role: PromptRole,
    val kind: PromptSegmentKind,
    val stability: PromptStability,
    val text: String,
    val fingerprint: String,
    val turnIds: List<String> = emptyList(),
)
```

建议种类和顺序：

```text
1. STABLE_SYSTEM_CONTRACT
2. STABLE_PERSONA
3. STABLE_INCARNATION_ANCHOR
4. FROZEN_HISTORY_SUMMARY
5. SEALED_HISTORY_CHUNK[0..n]
6. APPEND_ONLY_RECENT_HISTORY
---------------- longest reusable prefix ----------------
7. CURRENT_BIO_CODEBOOK
8. CURRENT_RELATIONSHIP_AND_AFFECT
9. CURRENT_RAG_MEMORIES
10. CONDITIONAL_TEMPORAL_CONTEXT
11. CURRENT_USER_INPUT
```

第 1 到第 5 段必须是 byte-stable。第 6 段由已经完成且验证通过的 turn items 组成；集合只允许尾部追加，既有 item 不得重写、重排或从头部滑出。下一轮请求因此可以精确复用上一轮已经发送过的完整历史前缀。任何时间戳、request ID、当前向量、Omega、evolution index 和动态关系值都不能进入历史前缀。

Codebook、关系、RAG 和时间仍全部位于当前用户输入之前，且所有现有状态能力都保留。把对话历史放到当前 Codebook 之前只改变序列化位置，不让历史状态替代当前状态。CURRENT_RAG_MEMORIES 必须先按第 10 节排除历史前缀中已经表示的 lineage。

## 12. PromptHistory 真正接入

已有 `PromptHistoryChunk`/`Snapshot` 从“旁路存储”改为生产请求的一部分：

1. Pipeline 在构建 Prompt 前读取当前 conversation scope 的 snapshot。
2. Prompt assembler 验证 chunk epoch、serializer version、fingerprint 和 turn continuity。
3. LLM client 将 frozen summary、sealed history 和 append-only recent history 按确定顺序展开为 input items。
4. 不能把 history 重新拼回一个每轮变化的 `contextText`，也不能使用 `takeLast(N)` 重建移动窗口。
5. 当前已完成 turn 以不可变 user/assistant items 追加到 recent history；达到 token budget 后原子归入新 sealed chunk。
6. 封存只改变持久化分组和 compaction eligibility，线上展开后的 item role、文本、顺序和边界必须与封存前完全一致。
7. 已封存 item 不可重写；serializer 升级必须开启新 cache epoch。

Chunk 以 token budget 为主、turn count 为上限。`PromptHistoryChunk` 必须保存或可无损恢复其包含的 wire items，Provider client 负责 flatten，不能把整个 chunk 改发成单一新文本 item。这样 tail-to-sealed rollover 不会改变 Prompt 前缀。Compaction 只在总历史预算达到阈值时发生：旧 chunks 被一次性蒸馏成 immutable summary，原始 transcript 和 Memory Palace 不删除。

Compaction 是显式 epoch miss。新 epoch 第一轮允许只命中 system/persona，之后应在两轮内恢复长前缀命中。摘要失败时继续使用旧 epoch 或退化到 bounded tail + RAG，不阻塞回复。

## 13. Codex-like 追加式缓存模式

### 13.1 目标行为

“和 Codex 一样”在本项目中定义为：对话历史采用 append-oriented、immutable-prefix 的请求结构；每轮把新完成的 user/assistant items 追加到既有历史，不重写旧历史。CURRENT_BIO、关系、RAG、时间和当前 user input 是位于该前缀之后的当轮动态后缀；下一轮会用新的动态后缀替换它们，但不会破坏此前已经缓存的历史前缀。

不要求 Relay 必须实现某个未验证的官方扩展字段，也不把 `previous_response_id` 作为唯一正确路径。基础兼容模式应能在普通 Responses `input` 数组上工作。

### 13.2 Provider capability matrix

启动或配置变更后运行独立 canary：

| Capability | 探测 | 结果用途 |
|---|---|---|
| basic Responses | 普通字符串/input items | 是否可生成 |
| cache key accepted | 仅 `prompt_cache_key` | 是否可安全发送 key |
| cache options accepted | key + options | 是否可发送 options |
| explicit breakpoint | structured `input_text` + breakpoint | 是否允许显式边界 |
| usage metrics | 连续两次相同前缀 | 是否能观测 read/write |
| previous response | 最小 `previous_response_id` 链 | 仅作为后续可选优化 |

Capability 结果按 `(base_url, model, account routing fingerprint)` 缓存并带 TTL。模型、Relay URL 或认证路由改变后重新探测。

### 13.3 模式选择

```text
OFFICIAL_EXPLICIT
  官方端点且 canary 证明 explicit breakpoint 可用

RELAY_APPEND_ONLY
  自定义 Relay 默认模式；使用 typed input items、稳定 cache key（若可用）
  不发送 breakpoint；依靠精确追加前缀和 provider 自动缓存

OBSERVE_ONLY
  Relay 可生成但没有缓存 usage 指标；保持追加结构，指标记 UNKNOWN

CACHE_DISABLED
  Capability 明确拒绝相关 metadata；仍保持正确 Prompt 结构，但不宣称缓存
```

生产消息若在首字节输出前因“明确不支持字段”的 4xx/已识别错误失败，可以在确保上游未开始生成后降级一次。对 5xx、超时、未知 upstream error、SSE 已开始或无法判断是否已计费的情况禁止自动重试，避免重复生成和重复计费。

### 13.4 Cache key

Cache key 是非密钥 fingerprint，按 conversation scope 路由，但不进入模型可见文本。它由以下稳定项组成：

```text
provider/model policy revision
system schema revision
persona revision + immutable starting point
opaque conversation cache identity
dialogue namespace
```

Key 在同一 serializer epoch 内稳定；是否跨 epoch 保持稳定由 canary 数据决定。无论 key 如何，真正命中仍要求前缀字节相同。

## 14. 时间层重构

所有运行时组件依赖 `Clock`/`TemporalContextProvider`，禁止业务路径直接散落 `System.currentTimeMillis()` 或 `Instant.now()`。

普通对话按需注入时间：

- 用户问日期、时间、计划或“多久了”时注入精确时间；
- 跨越显著沉默时注入 coarse elapsed bucket 和 day period；
- heartbeat、Shock heartbeat 和调度任务注入其需要的权威时间；
- 无时间语义的连续对话不注入每轮变化的完整 timestamp；
- temporal segment 始终位于动态尾部，不能污染稳定缓存前缀。

测试 harness 使用 `VirtualClock` 推进小时和日期。模型看到的日期、heartbeat 调度、memory timestamp、关系 elapsed time 和评测脚本必须来自同一个 Clock，禁止只在用户台词里声称“第二天”。

## 15. 8D 防饱和机制

### 15.1 LLM delta 是提案

输出 schema 保持八个 signed delta，但后端按以下顺序应用：

```text
validate finite values
  -> per-dimension clamp
  -> neutral dead zone
  -> boundary-aware gain
  -> map to internal space around dynamic centroid
  -> apply homeostatic pull and elapsed-time drift
  -> map back to [0, 1]
  -> persist and trace proposed/effective delta
```

边界阻尼原则：向 `0/1` 极端继续移动时，增益随剩余 headroom 减小；离开极端、朝动态 centroid 恢复时不施加同等阻力。Shock、外部权威事件和已验证的高强度 pre-tick 走显式 stronger-signal 路径，而不是让普通温暖回复长期堆满坐标。

### 15.2 Prompt 校准

系统层增加短而明确的 signed-delta 对照：

- 普通寒暄和延续性日常：大部分维度为 `0.0`；
- 紧张被解除：S/F 可为负；
- 被安慰后恢复：V/P 可上升，tau 未必变化；
- 发现误解：L 可上升而 P/S 可能下降；
- 温暖回复不等于所有维度都为正。

这些是向量语义约束，不是 ATRI 人格行为，可保留在 English logical core。

### 15.3 可观测性

每轮记录：proposed delta、effective delta、clamp reason、headroom gain、homeostatic contribution、pre/post vector、centroid、是否 Shock/heartbeat。不得只记录最终 snapshot，否则无法判断是模型偏置还是 reducer 饱和。

## 16. 观测与诊断基线

在修改行为前先建立同一套可复现基线：

- Prompt segment manifest：仅记录 kind、role、byte/token count、fingerprint、stability、epoch，不记录完整生产 Prompt。
- Cache usage：input tokens、cached read tokens、cache write tokens、provider metric availability、key fingerprint、longest local identical prefix。
- Memory overlap：recent/sealed/RAG 的 turn lineage 集合和被排除/回填数量。
- Relationship：事件候选、置信度、ledger commit、phase transition reason。
- Persona：程序化词汇、重复 n-gram、明确爱意回赠、主动话题钩子。
- 8D：每维正/零/负 delta 分布、饱和持续轮数、homeostasis 贡献。
- Time：模型可见 temporal granularity、virtual/real clock source。

缓存 token-weighted read rate 定义为：

```text
sum(cached_input_tokens) / sum(input_tokens)
```

只统计 provider 明确提供缓存 usage 的请求。Canary、失败请求和 metrics unavailable 请求分别报告，不能混入分母制造结果。

## 17. 确定性长轮次 A/B 评测

### 17.1 场景

每次候选发布运行 120 到 200 轮，VirtualClock 跨越多天，覆盖：

- 初次认识和身份确认；
- 早餐、天气、工作、疲惫、吃什么、忘带东西等琐事；
- 玩笑、互相起外号、轻度身体接触和暧昧试探；
- “要不要”反例至少 20 次；
- 单方面告白、ATRI 明确接受、情侣状态重启恢复；
- 热恋期直接爱意、想念、撒娇、吃醋和主动亲近；
- 冷场、误解、真实边界请求、道歉和修复；
- 从高浓度恋爱回到自然情侣日常；
- heartbeat 和长时间沉默后的状态延续。

### 17.2 A/B 控制

- 相同模型、sampling 配置、场景、VirtualClock 和初始状态；
- 能固定 seed 时固定，不能固定时每版至少运行 3 个重复样本；
- A 为当前生产 Prompt/架构，B 为候选版本；
- Judge 不接收版本标签，采用 pairwise blind evaluation；
- 除自动 Judge 外保留人工抽样，避免评测模型偏爱华丽但不连续的回复。

### 17.3 评分维度

- ATRI identity fidelity；
- 自然人类伴侣感；
- 对当前用户话语的直接承接；
- 调情与爱意的明确 reciprocity；
- 关系阶段和承诺连续性；
- 琐事分享是否自然；
- 是否客服化、总结化、过度提问；
- 是否虚构现实动作或越过边界；
- 事实正确性和现有能力是否回归。

每轮导出 transcript、Bio snapshot、proposed/effective delta、relationship facts/events、memory lineage、Prompt segment manifest 和 cache usage，保证结果可复盘。

## 18. 验收指标

### 18.1 关系与人格

- “要不要”在所有 golden cases 中 0 次被分类为 boundary request。
- 告白接受和 couple status 在无关对话、进程重启和 scope 恢复后仍保持。
- 适用的直接亲密/告白 golden cases 中，至少 90% 获得明确对等回应，而不是转成记录、建议或含糊关怀。
- 非运维语境中的“收到/记录/登记/权限/库存/任务完成”等程序化措辞低于回复总数的 2%。
- 新 persona 在 ATRI fidelity + companion quality pairwise judge 中至少 70% 胜出，且事实正确性不下降。
- 热恋阶段单独评分，不能用“温柔但不回应”通过。

### 18.2 记忆与上下文

- 同一请求内 recent/sealed history 与 RAG memories 的 `source_turn_id` 交集为 0。
- 去重后在存在足够唯一候选时，RAG 最终容量不低于优化前。
- MIXED 6:4 和 CONTRAST 检索语义不回归。
- Compaction summary 保留人名、承诺、未解决问题、关系事实和事件顺序。

### 18.3 8D

- 中性轮次 `median(abs(effective_delta)) <= 0.02`。
- 没有权威高强度原因时，任何维度不得连续 10 个普通 turn 保持 `>= 0.99`。
- 所有维度都必须有正、零、负路径单测；S/F 在缓解事件中可可靠下降。
- VQ-VAE、heuristic fallback、派生 D 和 Omega 规则保持通过。

### 18.4 Cache

- Relay 能提供缓存 usage 时，warm-up 后 token-weighted cache read rate 目标 `>= 85%`。
- Relay 不提供 usage 时明确报告 `UNOBSERVABLE`，同时报告本地 byte-identical prefix 比例，但不能声称 provider hit。
- 普通 turn 的 sealed chunks 字节不变；每轮只追加新 item。
- Compaction 只产生一次显式 epoch miss，并在两轮内恢复新前缀复用。
- 不删除任何 VQ-VAE、8D、RAG、关系或近端上下文能力来达到指标。

### 18.5 时间与运行

- 多日测试的所有时间来源统一来自 VirtualClock。
- 无时间语义的连续普通 turn 不注入每轮变化的精确 timestamp。
- heartbeat、沉默窗口和 memory timestamp 在虚拟时间推进下可确定性复现。
- 失败与降级不阻塞 Ktor 主逻辑，也不导致机器人静默无响应。

## 19. 实施顺序

顺序按依赖关系固定。每一阶段都必须可测试、可回滚、可单独部署；前一阶段未达到门槛，不进入下一阶段。

### Phase 0：基线与可观测性

1. 捕获脱敏 Prompt segment shape、usage、lineage、8D delta 分布。
2. 建立 Relay capability canary 和 capability matrix。
3. 引入 Clock port、VirtualClock 和确定性 120 到 200 轮 harness。
4. 固化当前版本 A 基线，修正“表面互动即调情 PASS”的 Judge rubric。

原因：没有可信基线时，后续无法区分质量提升、随机波动和 Relay 不可观测。

### Phase 1：全局化身状态边界

1. 修订 `AGENTS.md` 的 session-scoped Bio 规范。
2. 引入 `incarnation_id` 与 conversation scope 分离的数据模型。
3. 把 8D、Omega、Shock、centroid、evolution 和 long-term continuity 迁移到 incarnation key。
4. 引入 canonical subject 和 memory visibility。
5. 加入迁移、并发、跨入口连续性和隐私隔离测试。

原因：关系、记忆和后续缓存都必须先知道哪些数据全局、哪些数据属于单个对话。

### Phase 2：持久关系事实

1. 新增 relationship ledger、facts 和 reducer。
2. 删除 substring boundary classifier，加入结构化 evaluator 和反例 corpus。
3. 持久化告白接受、couple status、称呼、承诺和边界。
4. 增加 restart、idempotency、correction/reset 和关系阶段测试。

原因：在 persona 变得更亲密前，系统必须先能准确知道双方是否已是情侣。

### Phase 3：Persona 与 private/public voice

1. 重写 ATRI `core_self` 和关系阶段表达数据。
2. 将 few-shot 升级为真正的 role-message 多轮示例。
3. 加入热恋 reciprocity、日常琐事、主动话题和反客服规则。
4. 分离 private diary validation 与 public response validation。
5. 跑 Prompt golden 和短轮 A/B，不改业务代码中的人格逻辑。

原因：先有正确关系事实，再让 persona 按事实表达，避免 Prompt 自行幻想进度。

### Phase 4：权威 transcript 与 RAG 去重

1. `TranscriptStore` 成为 recent_turns 唯一来源。
2. 增加 memory lineage/visibility schema 和旧数据迁移。
3. 实现 overfetch、exact lineage exclusion、lane-preserving backfill。
4. 验证没有 context capacity 回退。

原因：Prompt history 和 cache 接入前先保证历史内容正确且不重复。

### Phase 5：Typed Prompt 与 PromptHistory 接入

1. `BuiltPrompt` 升级为 typed segment sequence。
2. Pipeline 读取 `PromptHistorySnapshot`。
3. Provider client 逐 item 发送 summary/chunks/dynamic/tail/user。
4. 实现 sealing、epoch、compaction fallback 和 serializer golden tests。

原因：这是把已存在但未使用的 prompt history 变成真实可缓存前缀的核心阶段。

### Phase 6：Relay 兼容缓存

1. 根据 canary 选择 `OFFICIAL_EXPLICIT` 或 `RELAY_APPEND_ONLY`。
2. 自定义 Relay 默认不发送未经证明的 breakpoint。
3. 增加 usage 解析、UNKNOWN 状态和安全降级规则。
4. 在生产等价环境验证 byte prefix 与 provider usage。

原因：先保证请求结构正确，再针对 provider 能力打开 metadata，避免再次因 502 让机器人完全不回复。

### Phase 7：8D 稳态与 signed calibration

1. 记录 proposed/effective delta 基线。
2. 实现 dead zone、clamp、boundary gain 和 homeostatic pull。
3. 补充中性、缓解、冲突、Shock 和 heartbeat 测试。
4. 调整参数直到满足饱和与中性 delta 指标。

原因：8D 参数需要在新 persona 的真实 delta 分布上校准，过早调整会基于旧客服化 Prompt 得出错误参数。

### Phase 8：长轮验收、清空、部署

1. 运行完整 A/B 和至少 3 次候选重复样本。
2. 通过质量、关系、记忆、8D、cache 和时间全部门槛。
3. 备份并清空生产 transcript、Memory Palace、relationship ledger、Bio、Omega、Shock、evolution、prompt history 和 cache epoch。
4. 部署到生产，开放 OpenEden 对外端口，不修改 Illusion Server `8080`。
5. 运行生产对话测试并导出 transcript 与每轮 Bio/relationship/cache 变化。

## 20. 回滚与发布门槛

任何阶段出现以下情况必须阻止发布或回滚该阶段：

- factual/safety regression；
- persona 行为被硬编码进 Kotlin；
- RAG 去重导致有效容量下降；
- current Codebook/8D/relationship 状态被缓存成过期值；
- Relay usage 不可观测却对外宣称命中；
- 关系阶段在重启后丢失或被普通轮次降级；
- global memory 跨 subject/scope 泄漏；
- 8D damping 抹平 Shock、低活力或高恐惧等应有差异；
- 生产 5xx 自动重试造成重复生成风险；
- 机器人错误时静默，不向适配器返回可诊断失败。

每阶段使用 feature flag 或 provider policy 开关保持可逆。状态 schema 迁移必须先备份并有 downgrade/read-compatibility 策略；不可逆 incarnation 数据变更不能与 Prompt 改动捆绑成一次发布。

## 21. 明确不做的事

- 现阶段不微调或训练新的伴侣模型；先修系统性 Prompt、状态和评测问题。
- 不用一个好感度替代双层关系模型。
- 不强制所有输出使用动作括号和情绪方括号。
- 不复制原作可识别台词或公开项目受限代码。
- 不让热恋等同于依赖操控、排他、威胁或无限服从。
- 不把全部历史永久无界发送；通过 immutable chunks 和 epoch compaction 控制预算。
- 不靠 `previous_response_id` 或 explicit breakpoint 单点绑定某个 Relay。
- 不修改 Illusion Server 或抢占其端口。

## 22. 参考资料

- `docs/diagnostics/prompt-cache-relay-investigation.md`
- `docs/superpowers/specs/2026-08-23-prompt-cache-context-deduplication-design.md`
- `docs/superpowers/specs/2026-08-22-relay-compatible-prompt-caching-design.md`
- `docs/superpowers/specs/2026-08-22-prompt-time-and-cache-design.md`
- `docs/superpowers/specs/2026-08-22-private-operational-log-design.md`
- `docs/superpowers/specs/2026-07-12-companion-user-relationship-state-design.md`
- `test-artifacts/relationship-longrun-2026-08-25.md`
- `private_corpus/atri_full_plot/`
- [OpenAI Prompt Caching](https://platform.openai.com/docs/guides/prompt-caching)
- [cos-wind/AI-girlfriend-QQ](https://github.com/cos-wind/AI-girlfriend-QQ)
- [MIKUSCAT/ATRI](https://github.com/MIKUSCAT/ATRI)
- [StarsBrightness/atri-Agent](https://github.com/StarsBrightness/atri-Agent)

## 23. 最终决策摘要

现阶段不应继续只往系统 Prompt 里叠加“更像恋人”“delta 要有正负”之类句子。当前问题是多个结构性缺口共同作用：关系事实不存在、边界误判、private/public 声音混合、recent/RAG 职责不清、PromptHistory 未接入、Relay 能力未探测、时间不可模拟、8D reducer 缺乏稳态。

选定路线是先建立可观测基线和全局化身边界，再持久化关系事实，随后重构 ATRI persona 和记忆上下文，接入追加式 PromptHistory，最后用真实 Relay 数据校准缓存和 8D。这样才能同时提升伴侣感、ATRI 忠实度和缓存命中率，而不是让其中一个指标通过牺牲另一个指标得到表面改善。
