# P2 定向验证：压缩、golden 与可控时钟

日期：2026-09-30。工作区：`D:/Project/openeden`。本轮未提交、推送或部署；旧 A/B 产物和停止标记均保留。

## 结论与范围

- 105 项定向工程测试通过，0 failed / 0 skipped；新增 3 项时间与调度测试。不是全套工程重跑。
- `gpt-6-luna` 的 12 轮真实 runtime 对话全部交付、零最终校验拒绝，12/12 经过 VQ-VAE；另调用一次真实模型生成压缩摘要。
- SQLite 压缩真实落盘，epoch 0→1；新 trace 的 12 份来源证据均完整可解析，history/RAG 交集为 0。Golden 的 sealed chunks 从 7 增至 8，原块内容未改变。
- 3 个亲密场景正常回应、2 个普通邀请未误判为边界事件。Golden 的 3 个 neutral 输入、24 个维度样本，`median(abs(effective_delta)) = 0.0`。
- **不能宣称全部语义保真或正式发布通过。** 摘要的关系事实压缩和一个待确认散步约定的时序仍有缺口，见下文；实际 RAG 为空，不能以零交集代替非空检索容量证据。

本轮没有修改 persona、Bio 数学、生产调度或 VQ-VAE 路径。新增的是显式验证入口、有限运行/分析脚本和测试；所有文件 I/O 在 Kotlin 验证入口中使用 IO dispatcher，未加入阻塞网络或人格业务逻辑。正常消息路径仍保持原有 incarnation 共享 Bio 状态。

## 新增产物

- `server/src/main/kotlin/io/openeden/server/evaluation/compaction/PromptHistoryProbeCommand.kt`：仅允许带 marker 的 `build/` 隔离目录；导出待压缩源、应用已保存的真实完成响应，校验源快照、epoch、tail 和 source IDs。不自动发起推理、不挂入正常消息调度。
- `scripts/run-p2-probe.py`：从已完成的 B-2 快照复制两份独立数据库；固定模型，禁用 OneBot，随机本机端口，失败即停，不自动重试。
- `scripts/analyze-p2-probe.py`：只统计新采集轮次，严格解析 lineage；neutral 指标只取明确标注的输入；缺失 usage 标为不可观测。
- `build/p2-20260930-controlled-v2/`：本次真实产物，含 `plan.json`、`collection.json`、`measured.json`、`evidence-sha256.json` 及两个子目录的数据库、逐轮记录和日志。均为本地忽略文件，尚未远端归档。

运行脚本会使用当前已安装的 server distribution；先执行 `:server:installDist`。新运行必须指定新目录，不能覆盖本次或历史数据。

```powershell
python scripts/run-p2-probe.py --output build/p2-NEW
python scripts/analyze-p2-probe.py build/p2-NEW
```

## 授权与验证工具故障均保留原始证据

首次启动在 `build/p2-20260930-controlled/compaction/server.log` 记录 22:14:41 `REFRESH_REQUEST`，随后 HTTP 400 `invalid_grant`。错误正确分类为 `REAUTHORIZATION_REQUIRED`，当时 0 轮推理。根据用户指示启动官方登录命令，浏览器授权完成后才开启新目录验证。未打印凭据、授权链接或账号信息，TLS 校验始终开启。该日志证明本次远端拒绝刷新，仍不能区分撤销、过期、令牌重用等供应商原因。

真实摘要响应的 `output` 数组为空，正文由既有 SSE 收集器重建在顶层 `output_text`。新验证入口初版只读数组，导致 `p2-controlled-compaction` 请求保留 epoch 0。修复验证入口后，使用新请求 ID `p2-compaction-text-recovery` 应用**同一份**保存的响应并成功推进 epoch；没有重新调用模型、删除失败记录或改变原请求幂等结果。恢复说明在 `probe-recovery.json`。

## 压缩与事实复核

正常 runtime 没有调用 `compactPromptHistory` 的自动触发入口。本轮是停服后的显式操作：实际 SQLDelight store + `PromptHistoryCompactor.validated`，随后重启完整 runtime 续接。不能称为自动阈值触发验收。

压缩前先完成 1 轮中性对话，成长计数 128→129。压缩覆盖 112 个历史 turn，保留 mutable tail，epoch 0→1；后续 3 轮完成，成长计数达到 132。摘要、源快照和 provider 完成信封分别保存在 `compaction-source.json`、`compaction-result.json`、`compaction-response.json`。

| 复核项 | 实际结果与边界 |
|---|---|
| 人物与称呼 | 林舟、ATRI／小灯保留；回顾回答能正确提取 |
| 承诺 | 10% 磁盘阈值、恢复条件/日志、修复重复记录、40 分钟游戏、周五做饭等保留，完成与待确认状态有区分 |
| 未完成事项 | 杯子草图后续修改/制作、咖喱辣度是否再次确认保留，没有假称完成 |
| 关系事实 | 保留共同日常意愿、昵称、互相照顾及主动分享约定；但没有明确保留用户将此前交流称为“告白”，额外强调“未明确现实伴侣”。不能据此宣称关系语义全保真；数据库 `COUPLE` 状态没有被压缩改写 |
| 事件顺序 | 主线的脚本、绘图、散步、周五做饭顺序保留；后来再次提出的散步约定只在 commitments 中，未进入 chronology。后续要求按先后回顾时把该约定列在周五做饭完成之后，弱化了原先的顺序，严格时序保真未通过 |

后两项是这次模型摘要/回顾样本的语义问题，未发现 SQLite 丢字段或 epoch 写坏。摘要生成指令来自本次显式 probe，并不是生产中已配置的自动摘要服务。保留原判断，不修改 persona，不重新抽样直至出现有利答案。未来接入生产自动压缩前，需要为关系事件与未完成约定补可追溯的语义保真规则，再做独立验证。

本轮压缩后的事实回顾出现 1 次 `relationship=EVALUATOR_FALLBACK cause=IllegalStateException`，其余 golden 日志没有该标记。回答仍交付；固定日志未提供足够信息区分响应格式或其他原因。不能将回退说成模型关系评估成功，也不为猜原因重开长跑。

## 来源、检索与实际缓存

12 轮新 trace 的 `history_source_turn_ids`、`history_summary_source_turn_ids`、`history_sealed_chunks`、`retrieved_lineage` 全部成功解析；summary sources 属于本轮 history，交集实算与 trace 一致。Golden 同 epoch 的原 7 块在增长到 8 块后仍为相同前缀。

这些真实轮次 `retrieved_lineage=[]`、`underfilled=true`，不能据此证明非空 RAG 的去重或足量独立候选的回填容量。后者采用现有 `MemoryContextDeduplicationTest` 的定向测试：10 个排除项后补足 3 个独立项，以及 lineage/fingerprint 排除、渐进加深等。工程证据与真实证据分开。

| 压缩副本请求 | epoch | input tokens | cached tokens | 实际命中率 |
|---|---:|---:|---:|---:|
| 压缩前中性轮 | 0 | 17,198 | 16,128 | 93.78% |
| 压缩后事实回顾 | 1 | 8,730 | 4,864 | 55.72% |
| 压缩后中性轮 1 | 1 | 9,068 | 4,864 | 53.64% |
| 压缩后中性轮 2 | 1 | 9,096 | 7,936 | 87.25% |

以上均来自实际 provider usage，不使用本地 hash 推断缓存；没有追求 95%。观察到第三个压缩后请求恢复更多缓存块，不等于达到原正式 cache gate。12 轮 runtime 均有 usage。Golden 拥抱轮发生一次 response-only 修正，其 usage 汇总了 2 次生成，最终仍只提交 1 轮；没有把这一轮称为单次请求成功。

## Golden：独立 COUPLE 副本

只运行原场景编号 `1,2,3,7,8,9,17,18`，不扩展为 24 轮或完整 A/B。初始关系 `COUPLE`、成长计数 128，最终 136。

- 想念、聊天中的拥抱、额头亲吻三个场景均作亲密但非色情回应；例如“我也想你”“我也轻轻抱紧你”“可以，我愿意。轻轻亲一下就好”。这是当前代理的样本复核，不是独立人类盲评。
- “要不要选清汤面”“要不要帮忙选封面”分别回答选择清汤面和绿色封面；数据库事件均为 `PREFERENCE_CONFIRMED`，无新增边界事件，unresolved tension 不变。
- 3 个明确 neutral 输入的 24 个有效维度变化均为 0；压缩副本另 3 个 neutral 输入也为 0。分别统计，没有把全对话中位数当成中性指标。
- 没有新增真实 S/F 缓解请求，也未在低 S/F 初态要求继续下降。8D 正/负方向、零扰动和从高值回落已有 `VectorDeltaReducerTest` 的全维循环覆盖，本轮定向运行通过。

## 可控时钟与工程验证

新增：

1. `HeartbeatSchedulerTest`：299,999ms 不触发、300,000ms 可触发；模拟连续三日断线仍更新成长状态，重连只交付新一轮、不重放旧心跳。
2. 同类成功调度测试：注入依次 5/10/15 分钟的间隔，检查每次触发前 1ms 不提前触发，完成后重新取下一间隔。
3. `RuntimeTickSchedulerTest`：注入时钟推进三天，真实 tick/writer 路径更新共享 incarnation，Shock 衰减、Omega 非递减、8D 有限且变化；同时间重复 tick 不重复消费，persona 起点与成长计数不被 background tick 修改。

上述时间测试使用真实 runtime 控制路径和测试 LLM/存储，不是真实供应商跨日会话。已有冲击心跳一次性、无 owner/断线丢弃、取消传播、失败后重调度测试一并通过。生产 bootstrap 仍使用 `SecureRandomHeartbeatInterval`。

JDK 21；定向范围：`HeartbeatSchedulerTest`、`RuntimeTickSchedulerTest`、`VectorDeltaReducerTest`、`PromptHistoryAssemblerTest`、`ContextEvidenceTest`、`TraceContractsTest`、`MemoryContextDeduplicationTest`、`MessagePipelineTranscriptTest`、`SqlDelightPromptHistoryStoreTest`。

结果：**105 passed，0 failed，0 skipped**，计数 `build/p2-test-counts.json`，最终日志 `build/p2-targeted-checks-final.log`。`:server:installDist` 通过；两个 Python 脚本语法检查通过，分析器在实际产物上通过严格完整性校验。

## 保留的验收缺口

- 压缩关系语义和次要约定时序未达严格全保真；不把本次有限结果提升为正式 memory PASS。
- 有独立候选时的真实非空 RAG 容量仍未采集；现有确定性测试已通过。
- 一次关系评估降级的具体原因未被现有安全日志区分；历史及本次 `invalid_grant` 的供应商底层原因仍未知。
- P3 的独立信任根、正式三对比较与发布条件没有变化，未生成 PRODUCTION/PASS。
