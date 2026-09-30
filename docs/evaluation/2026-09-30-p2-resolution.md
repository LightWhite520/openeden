# P2 修复与最终验证结果

日期：2026-09-30。所有运行均使用隔离数据库，模型固定 `gpt-6-luna`。没有启动完整 benchmark、重跑旧 12 轮、修改生产数据库、发送 OneBot 消息、提交、推送或部署。旧失败、旧摘要、CLI 未提交工作均保留。

## 已完成的工程修复

### 关系评估：区分故障、修复流上限和历史事件重放

1. 新增固定失败阶段、HTTP 状态及白名单供应商错误码。应用日志不输出供应商消息、正文、凭据或账号信息。显式 evaluation 命令在本地另存失败事件，失败不再只有无法区分的 `IllegalStateException`。
2. 本轮实际遇到 `subscription_sharing_usage_limit_exceeded`。用户要求重新登录后，原注册重新授权成功但额度仍耗尽；随后新注册经用户浏览器同意、身份校验和 DPAPI 保存成功，恢复真实推理。它与历史 `invalid_grant` 是不同的错误，不能混为一谈。
3. 同一个历史回顾输入实际重现了 `ChatGPT subscription response exceeded limit`。评估器原先对整个 SSE 流使用 64 KiB 上限，帧/推理元数据也计入其中。现将流上限提高到 1 MiB，最终完成响应仍限制为 64 KiB。完整完成事件、schema、取消传播、fallback 均保持。
4. 修复上限后，精确回放生成 12 条历史事件，却只有 5 个唯一事件 ID。模型把旧承诺当新事件，这是另一个实际问题。评估规则现仅允许当前轮新发生的事件，回顾/引用不产生新事件；解析器拒绝同轮重复类型，避免重复 durable ID。
5. 同一历史输入在最终代码下真实返回 `events=[]`、`confidence=1.0`。保存于 `build/p2-20260930-resolution/relationship-result-current-events.json`。这是独立 evaluator 调用，不改写原数据库。

**边界：** 本轮已定位并修复可重现的 SSE 限制问题，不能反向证明旧 P2 那一次 `IllegalStateException` 必然也是同一原因。那次未保存错误阶段/流大小，历史精确原因无法恢复。

### 摘要：来源约束和时序规范化

- 将摘要指令放入独立资源文件，明确关系陈述的说话者归属、请求/接受/完成区别，以及重复活动不能合并。
- 新增可选 `requireSourceAnchors` 校验。检查 commitments、relationship_facts、chronology 的来源 ID；未知或缺失来源使压缩保留原快照/epoch。
- 根据真实输入 turn 顺序整理 chronology；把 commitments/relationship_facts 中遗漏的已生成条目原文补入 chronology，不编造事件或状态；附上 `source_order`，避免随机 UUID 被误当作时间。
- 对话逻辑层明确请求不等于承诺、计划不等于完成、较早活动完成不能证明较晚提议完成。没有新增 Kotlin 人格行为分类，也未改 persona、Bio 数学或 VQ-VAE。
- 使用与旧失败样本完全相同的 112-turn source 生成一份新订阅摘要。初次来源校验拒绝推进 epoch；规范化修复后用新 request ID 应用同一份保存响应，没有再次抽取有利摘要。mutable tail 与 source IDs 保留，epoch 0→1。
- 一份 API 回退响应在新注册恢复前已发出、之后完成，模型同为 `gpt-6-luna`；独立保留，未替代预先选定的订阅验收样本。

**语义结果必须分开：**

| 检查 | 结果 |
|---|---|
| 摘要保留“用户把此前交流称为告白”、ATRI 回应 | 已保留，并带来源 |
| 后续散步提议的提出次序与未知完成状态 | 存储摘要按原始 source order 保留，早于周五做饭 |
| 最终针对性回顾区分已完成的河边散步、后续未定期散步、已完成做饭 | 三项分开回答，完成状态有区分 |
| 自然语言严格时序全保真 | **未完全通过**：第二项仍用“你后来独自走过河边”指代较早活动，存在相对时序歧义；较早广泛回顾也出现过活动合并和遗漏关系陈述 |

新摘要和结构性保护已完成，不能因此把模型每次回顾都宣称为全保真。最终请求是针对保留失败事项的窄场景，不能代替旧广泛回顾质量指标。没有继续修改判断或反复生成直到结果有利。

### 非空真实 RAG 容量

旧样本只读审计证明：可见历史来源全部被去重，唯一不重叠记忆属于另一个 scope，因此空返回本身不是容量缺陷。

本轮使用新隔离副本，准备 12 条带独立来源的人工档案，启动真实 DJL 重新计算 embedding，再走 SQLite、本地向量索引、完整 runtime 和真实订阅模型：

- 排除 127 条历史重叠来源。
- 回填目标 10 条，`backfilled=10`，`underfilled=false`。
- lineage 含常规与 recent 合计 12 个唯一记忆，history/RAG 来源交集为 0。
- 全部返回来自已声明的人工档案，模型回答引用了其中具体安排；最终无校验拒绝。

第一份只有 6 个候选的副本也保留，`underfilled=true` 是候选不足。足量验证保存于 `build/p2-20260930-capacity/rag/`。这是实装 DJL/SQLite 的人工夹具容量验收，不是自然长期记忆数据，也不是 Qdrant 远端容量验收。

### 授权独立进程覆盖

新增两个独立 JVM 同时访问同一个隔离 DPAPI 存储的测试。Mock token endpoint 的原子 marker 确保刷新只能发生一次；两个进程都读到 replacement，最终无 pending checkpoint。测试通过，不使用真实用户凭据、不联网、不模拟成真实供应商长期刷新。

既有授权错误分类、轮换检查点恢复、Windows 发布失败恢复测试保持通过。历史 `invalid_grant` 的服务端撤销/过期/重用原因没有新增可追溯证据，仍未知；这不是需要再次登录或重复长跑的理由。

## 验证与证据

最终工程回归日志：`build/p2-resolution-final-verified.log`；计数另存 `build/p2-20260930-resolution/final-test-counts.json`。汇总 **849 passed、2 skipped、0 failed/error**：core 491、server 325 passed + 2 skipped、client 10、OneBot 23。最后一轮 client 为 Gradle UP-TO-DATE，保留已通过结果；其余相关任务通过。没有声称重跑原有 CLI 界面工作。

结构与来源审计：`build/p2-20260930-resolution/verified-outcomes.json`，包含输入与结果 SHA-256。

实际产物：

- `build/p2-20260930-resolution/`：授权/供应商失败、同输入关系评估的修复前后结果、一次选定订阅摘要、第一份容量副本和结构规范化过程。
- `build/p2-20260930-grounding/`：保持同一摘要的通用 grounding 修复后广泛回顾，仍保留其不完美输出。
- `build/p2-20260930-semantic-final/`：附 source_order 后的固定事项回顾，不冒充广泛回顾全通过。
- `build/p2-20260930-capacity/`：足量人工候选的真实容量验证。

## 不能宣称已解决的部分

1. 自然语言回顾仍有相对时序歧义，严格全语义验收不能标 PASS。
2. 历史 `invalid_grant` 与旧单次关系降级的精确原因无法由缺失的旧证据还原。本轮可重现的新问题有明确原因和修复，但不追溯改写历史结论。
3. P3 独立可信签名根、正式三对数据与原质量门槛仍是发布条件。当前没有独立信任配置，原两对为 5/10 overall 胜出，不能改成 70%，不能把部分第三对拼成完整验收。按用户约束没有重启完整 benchmark，也没有自签自信任或发布。

表达质量四个旧样本已完成上下文复核，没有确认人格代码缺陷，因此没有为了消除自动标记而禁止合理建议或技术玩笑。缓存结果保持用户已接受的结论，不追 95%。
