# OpenEden 下一上下文 TODO

更新：2026-10-01。工作区：`D:\Project\openeden`。这是修复交接清单，不是生产发布通过报告。

最新工作见 [语气 benchmark 与 Astra 对照](evaluation/2026-10-01-persona-voice-benchmark.md)：用户已重新明确授权 benchmark，并指定尝试 GPT-6-Astra，覆盖本文件旧的“不要重启 benchmark”限制。修复了短语气词被当作重复开头、高 L 词典强制克制、动态重复说明与校验不一致；补充安全的流式失败诊断。人格仍在 YAML，节点向量与模型权重未变。164 项 Kotlin + 3 项 Python 测试通过，server 已构建。

本轮 85 条真实已交付样本保留：Luna 前后各 30、Astra 同条件 10、Astra 新话题 15。旧历史高 V 配对评审：Luna 修复前后整体平局；Astra 对修复版 Luna 5 胜 5 平，去掉算术为 4 胜 5 平，仅为同模型自动评审、小样本证据。该比较未评估成本效益；用户明确强调 Astra 成本，不据此推荐切换生产主模型，生产模型选择未改。最终低 V 补测第 8 轮未交付；诊断确认 `subscription_sharing_usage_limit_exceeded`，已停止请求。最终动态说明／诊断改动只有自动验证，live smoke 因额度受阻，不能把前面的冻结版本结果冒充最终版本验收。下一步恢复共享额度后先补这一小项，再由用户检查实际语气；不要重复抽样挑好看的答案。完整回复已整理到报告链接的结果 JSON。

最新表达调整见 [公开语气与口癖调整](evaluation/2026-09-30-persona-conversational-voice.md)：已复核原著提取语料与公开社区截图，重写外部 persona 的报告式示例，并按用户明确要求强化高性能口癖、哼哼／嗯哼、嘿嘿与短句接话。用户给定的单条短口癖例外已同步规范与保护测试。41 项回归通过；隔离 `gpt-6-luna` 6/6 交付，但被夸仍有板正措辞，算术轮出现分子分母颠倒，不能声称质量全通过。测试副本初始活力约 0.186，不代表普通活力场景。未提交、推送、部署。

随后按用户要求完成高活力复测：同一原始快照的独立副本只将初始 V 调为 0.85，同样 6 轮全部交付，V 自然回落至约 0.650。前三轮更轻快，主动出现哼哼、高性能自称，难过轮能收住玩笑；算术轮仍犯同一个 7/12 说成 12/7 的错误，部分回应仍模板化。证据：`build/persona-voice-high-v-20260930/`。生产状态未改，不要把这次定性对比当成永久拉高 V 的授权。

最新结果见 [本轮修复与最终验证](evaluation/2026-09-30-p2-resolution.md)：真实非空 RAG 容量、关系评估 SSE 上限与历史事件重放、摘要来源与顺序保护、独立进程刷新验证已完成。严格自然语言时序仍有歧义，旧故障精确根因与 P3 发布条件不能假称已解决。下文旧 P2 记录保留为历史基线。

## 先读：用户已确定的方向

- **缓存优化已获用户认可，当前不再追求每轮最低 95%。** 不要重开缓存优化、降低上下文质量或填充 prompt 来提高数字。
- 用户明确反对反复跑 benchmark 代替根因排查。先定位具体问题，修复后做最小、有针对性的验证；不要自动恢复完整三对长对话。
- 真实模型固定为 **`gpt-6-luna`**；支持 ChatGPT 订阅登录和 API 两种方式。不要换模型凑结果。
- 先读仓库 `AGENTS.md`，保持 Persona-as-Data、非阻塞约束、完整 VQ-VAE 与 incarnation 共享 Bio 状态。代码定位优先使用已有 `.codegraph/`。
- 工作区有大量未提交修改，包含原有 CLI 界面工作和本轮修复。不要 reset、全量覆盖、全量提交或清理 `build/`；尚未执行提交、推送或部署。
- 不要碰生产数据库、8080 端口上的其他服务，也不要向 OneBot/外部聊天平台发测试消息。真实调用使用独立测试数据库。

## 已解决：不要重复修复

- [x] 心跳评估或观察器异常导致调度退出：普通错误后继续调度，取消仍正常传播。
- [x] 低强度但仍 active 的 ShockState 错误进入 MIXED：保留冲击态限制与 CONTRAST 优先级。
- [x] Vitality 启发式阈值文档不一致：统一低值阈值为 0.3，并有边界测试。
- [x] ChatGPT 订阅登录、受保护账号存储、串行令牌刷新、模型 fetch/选择与选择持久化。
- [x] 公开模型目录未列出 `gpt-6-luna` 时无法选择：允许显式输入并在真实推理探测成功后保存。目录未列出不等于不可调用；不要伪造 fetch 结果。
- [x] SSE 完成信封没有正文导致关系评估降级：从 delta/done 重建正文，仍严格要求成功完成事件；降级记录可观测日志。
- [x] 启动失败清理错误关闭共享 `Dispatchers.IO`。
- [x] SQLite 提交后进度事务瞬时 BUSY 导致送达失败：整笔小事务回滚后有界异步重试，不重复 Bio 提交和 LLM 请求。
- [x] 重复开头修正缺少真实错误反馈：初始生成与一次 response-only 修正传递禁止开头和校验原因，保留原 Bio delta。两个完整 B 运行均 128/128 交付、零校验拒绝。
- [x] Windows 无 `SecureDirectoryStream` 导致官方导出不可用：实现原生句柄相对创建/写入/发布、junction 防护和不覆盖重命名，真实 Windows 文件系统与数据库导出测试通过。
- [x] 上下文来源与 sealed chunk JSON 被通用 256 字符日志上限截断：为限定且校验过的标识符/hash schema 提供 64 KiB 上限，失败显式标记，不输出截断 JSON。
- [x] 盲评结果分母/分子矛盾与总胜负规则冲突：v3 单轮统一标注、受约束胜负组合、独立校验。旧无效结果保留，不涂改。
- [x] **缓存根因：缺少订阅链路的 `session-id` 请求头。** 现将匿名稳定 key 同时用于请求头和 `prompt_cache_key`，动态历史不改变 key，epoch 变化会改变 key，JSON 字段回退仍保留请求头。

缓存定向结果：相同 12,423-token 长请求预热后命中 12,032 token（96.85%）；真实应用续接历史后的两轮为 93.64% / 93.45%。用户已接受这个水平。[根因与证据](evaluation/2026-09-30-cache-session-affinity.md)。

## P1：优先处理的剩余问题

本轮 P1 进展见 [授权刷新修复与措辞复核](evaluation/2026-09-30-auth-refresh-and-wording-review.md)：修复授权错误分类与轮换后校验/保存的恢复缺口，27 项定向回归通过；四个表达样本已做上下文复核，未证实 persona/validator 缺陷。首次历史 `invalid_grant` 的底层原因仍未知，不应重启 benchmark 猜测根因。

### 1. 订阅授权刷新曾再次失效——真实异常，根因尚未确认

- [ ] 定位运行中 `invalid_grant` 的来源，区分服务端撤销/过期、刷新令牌轮换、进程竞争以及刷新成功后本地校验/保存失败等可能性；这些目前只是待查方向，不能直接当结论。
- [x] 已核对刷新请求、跨进程文件锁内重读与保存流程；保留双客户端串行刷新测试。未证明双独立进程或真实长期刷新必然可靠。
- [x] 修复失效后误报 plan 权限的问题；CLI、HTTP、SSE 区分重新登录、权限与暂时不可用等错误。HTTP 依赖不可用返回 503，并附带 code/retryable。
- [x] 隔离账号存储覆盖轮换后 JWKS 失败、取消、错误身份、权限/格式、Windows 最终发布失败与检查点恢复。27 项定向回归通过。首次检查点落盘前的崩溃或持续磁盘故障仍是明确恢复边界；未做真实刷新调用。

入口：`server/src/main/kotlin/io/openeden/server/auth/ChatGptOAuth.kt`、`ChatGptAccountStore.kt`、`ChatGptAuthCommand.kt`，以及对应 auth 测试。

证据：`build/ab-20260930-v2/runs/{A-2,B-2}/server.log`、`failures.jsonl`、`authorization-interruption.json`、`resume-checkpoints.jsonl`。两组在输入 44/42 附近中断，重新授权后从断点完成。授权已恢复过；新上下文不要直接要求用户再登录，先判断实际状态。

**安全边界：** 凭据由 `~/.openeden/chatgpt/accounts.dpapi` 保护，不打印 token、密码、授权 URL 或账号私密信息；不为测试清空用户当前登录。证书只确认 Windows 信任库能正常校验，尚未证明代理是根因，禁止关闭 TLS 验证。

### 2. 表达质量与评审分歧——先查具体样本，不能当成已确认的业务 bug

- [x] 已对第一对 B 输入 34、41、91、109 做当前代理上下文复核并保留理由；不是独立人类盲评批准。
- [x] 34/109 属于明确指导请求，91 承接用户主动维护隐喻，41 自然度可讨论但未确认业务 bug。未改 persona/validator 或正式指标。
- [ ] 如果确实需要改表达，修改外部 persona 数据；不要在 Kotlin 中添加场景到人格行为的硬编码。
- [ ] 保留原始判断、记录复核理由。评审规则有实质变化时明确版本，不反复重新抽样直到得出有利结果。

当前可核实结果（`build/ab-20260930-v2/measured-summary.json`）：

| 指标 | 第一对 B | 第二对 B |
|---|---:|---:|
| 交付 / 输入 | 128 / 128 | 128 / 128 |
| 程序化措辞自动标记 | 4 / 124（3.23%） | 0 / 124 |
| 浪漫回应 | 12 / 12 | 7 / 7 |
| 热恋机会 | 0，未观测 | 0，未观测 |
| 5 个阶段的 overall | 5 个 B 胜出 | 5 个平局 |

两对合计 **5/10 胜出（50%）**，无已标记事实回退；没有达到原正式门槛 70%。第二对的平局来自人格忠实度 TIE、陪伴质量 B 胜出，按既定规则 overall 必须 TIE。不能把“B 回复不再被拒绝”当成“所有质量维度已经优于 A”。第一对 v2 无效判断也保留在 `judgments/v2-invalid/`，v3 判断变化提示自动评审敏感性。

## P2：补齐针对性验证，发现真实缺陷再修

后续已完成旧证据只读审计并补充关系评估失败阶段诊断，见 [P2 后续诊断与语义规则](evaluation/2026-09-30-p2-followup-diagnostics.md)。15 项定向回归与 server 编译通过；无新真实模型调用。旧 12 轮没有可见的独立 RAG 候选（唯一未与历史重叠的记忆属于另一个 scope），不能据此判定容量缺陷。关系语义/约定时序规则已明确，但未做新摘要验证；历史降级原因依然未知。

2026-09-30 本轮已完成有限验证，见 [P2 定向验证报告](evaluation/2026-09-30-p2-controlled-verification.md)：105 项定向测试通过；12 轮真实 runtime 全部交付、1 次真实摘要、隔离数据库 epoch 0→1。**执行完成不等于全项语义验收通过**：摘要对“告白”的压缩和次要散步约定时序仍有缺口；真实 RAG 为空，容量只有确定性测试证据。不得为追求通过反复重跑。

### 3. 记忆来源、去重与压缩后的事实保留

- [x] 在当前代码和隔离数据库显式触发一次真实 compaction/epoch 变化并逐项检查；关系语义、次要约定时序未达严格保真，见报告。正常 runtime 尚无自动压缩触发入口。
- [x] 新 trace 的 lineage JSON 完整可解析、单请求交集为 0，同 epoch 旧 sealed chunks 稳定。真实返回为空；足量独立候选回填由确定性测试验证，非空真实检索仍是缺口。
- [x] 使用真实 usage 检查缓存恢复：压缩后三轮 55.72% / 53.64% / 87.25%，不再追求 95%。
- [x] 工程测试与真实运行证据分开记录；未恢复长对话 benchmark。
- [x] 已明确规则并对冻结失败样本验证；新增来源校验与时序规范化。
- [ ] 自然语言回顾仍有相对时序歧义，不能宣称当前摘要与回顾全保真；详见本轮最终结果。

入口：`ContextEvidence.kt`、`ContextEvidenceSanitizer.kt`、`SqlDelightTranscriptStore.kt` 及对应测试，通过 CodeGraph 继续定位压缩与检索调用链。

原因：冻结的 v2 A/B 运行仍包含旧日志长度限制，部分 lineage 已截断，不能追溯性宣称被新代码修复；真实长跑也没有观察到 epoch 变化。`prompt_history_state` 快照不包含完整 sealed chunks，不能假装从该表恢复了缺失证据。

### 4. 小规模 golden 场景覆盖

- [x] 已审阅原场景，仅运行编号 1/2/3/7/8/9/17/18 共 8 例。
- [x] 独立 COUPLE 副本的 3 个亲密场景正常回应；2 个普通邀请均为偏好确认，无新增边界事件。这是有限样本复核，不是正式盲评。
- [x] Golden 的 3 个 neutral 输入、24 个维度样本，中位数为 0；压缩副本另计，不混用全对话统计。
- [x] 现有全维正/负方向、零扰动及从高值回落测试通过；未追加真实 S/F 缓解场景。

两对主场景没有热恋机会；原计划的 24 输入补测已取消，**尚未执行**。`build/ab-20260930-v2/runs/FIXED-1` 现在是缓存修复后的 **3 轮中性 smoke**，seed 为 B-2、成长计数 128→131；它不是 24 轮 golden，不能混用。此前临时 golden 分析器没有保留为完成工具。

### 5. 时间、心跳与沉默证据

- [x] 已审阅并定向运行现有测试，补齐三日推进、精确静默边界、成功后重新抽取间隔和重连不重放。
- [x] 注入时钟驱动 tick/writer 与 heartbeat/full pipeline；使用测试 LLM/存储，明确不是供应商跨日会话。

这是验收覆盖缺口，不代表心跳实现仍有已确认故障；调度异常退出的修复及相关测试已完成。

## P3：仅在准备正式发布时处理

- [ ] 整理实际 runtime 导出、盲评产物和可信来源，按 `docs/evaluation/companion-quality-rubric.md` 检查正式门槛。
- [ ] 由部署侧独立配置可信签名指纹 `OPENEDEN_EVALUATION_TRUSTED_SIGNER_FINGERPRINTS`。不能临时自签并自信任来制造 PRODUCTION/PASS。
- [ ] 是否需要重新进行正式三对比较，应在上述问题解决且有明确发布需要时再决定。两个已完成 B 快照不包含最终 `session-id` 修复及全部后期 trace/export 加固，第三对已停止，不能拼成当前版完整三对验收。
- [ ] 版本冻结、证据保存、相关测试通过后，再按用户明确要求整理提交/发布；提交遵守 Conventional Commits。

独立信任根与正式三对数据是发布条件，不应阻塞一般开发，也不是现在继续消耗订阅跑 benchmark 的理由。

## 运行与证据交接

- 所有完整 benchmark、自动续跑和依赖 watcher 已停止。`build/ab-20260930-v2/abort-future-runs.json` 是有意保留的停止标记；不要随手删掉或启动 `build/continue-live-series.py`、`build/run-golden-after-series.py`。
- v2 的 A-1/B-1、A-2/B-2 已完成；A-3/B-3 是已停止的部分证据。不要覆盖原始拒绝、失败、授权中断或盲评结果。
- `build/` 下证据是本地忽略文件，不代表远端已有存档；新上下文应继续使用当前工作区，不先 clean。
- 全套工程检查曾通过：989 passed、2 skipped，记录在 `build/all-fixes-complete-checks.log`、`build/all-fixes-final-counts.json`。这是最后一轮 header 修复之前的全套结果。
- 最后的 header 修复已通过 29 项订阅/Responses 定向回归及 `:server:installDist`，见 `build/cache-session-affinity-check.log`。后续只有文档整理，没有再次声称全套重跑。
- Windows 使用 JDK 21：`F:/SDK/JDK21`；仅设置 `JAVA_HOME` 不保证 PATH 生效。真实订阅连接使用进程级 Windows 根证书库，保持 TLS 校验。若遇到 JVM selector 的 Unix-domain 临时路径错误，可使用进程级短路径 `-Djdk.net.unixdomain.tmpdir=D:/Project/openeden/build`，不要修改全局机器配置。

辅助文档：

- [缓存根因及有限验证](evaluation/2026-09-30-cache-session-affinity.md)
- [授权刷新修复与措辞复核](evaluation/2026-09-30-auth-refresh-and-wording-review.md)
- [P2 定向验证及保留缺口](evaluation/2026-09-30-p2-controlled-verification.md)
- [修复清单和历史检查点](evaluation/2026-09-30-remaining-fixes.md)（其中“继续运行”等旧段落已被顶部最新说明取代）
- [最初运行可靠性修复](evaluation/2026-09-30-runtime-reliability-verification.md)
- [旧 A/B 与 SSE/SQLite 问题](evaluation/2026-09-30-live-subscription-ab.md)（Windows 导出限制等部分已被后续实现取代）
- [订阅与模型选择操作说明](operations/chatgpt-and-model-selection.md)

## 可直接发给新上下文的任务

> 阅读 `docs/TODO-next-context.md`、P2 定向验证报告和 `AGENTS.md`。P2 的有限真实验证与可控时钟补测已完成，不要重复跑 12 轮。保留的缺口是摘要关系语义/次要约定时序、非空真实 RAG 容量及一次关系评估降级的原因。本轮启动曾真实返回 invalid_grant，用户已在浏览器重新授权成功；不要直接再要求登录。历史 invalid_grant 底层原因仍未知。缓存效果已接受，不追 95%，不恢复完整 benchmark。保留未提交工作和旧证据，区分代码缺陷、模型语义问题与发布验收条件。
