# OpenEden 当前状态与待办

更新：2026-10-01。源码、配置和回归测试已提交并推送至 `origin/master`，交付提交为 `8a41d97`。这是开发交接，不是生产质量验收或部署完成报告。

## 已交付

- 人格表达、口癖与原创示例位于 `persona/atri.yaml`；短语气词不再被一次重复误拦截，高逻辑状态不再强制声音克制。生产活力和默认模型未调整。
- 订阅 SSE 正文收集、失败诊断、刷新检查点恢复与独立进程互斥已补齐回归；SQLite 交付重试、Windows 安全导出、历史来源保护与关系事件重放已有修复。
- 缓存通过稳定 `session-id` 修复，用户已接受当前效果，不继续追逐每轮 95%。详见[缓存根因](evaluation/2026-09-30-cache-session-affinity.md)。
- 交付前模块回归汇总 1063 项，0 失败、2 跳过，另有 3 项 Python 测试通过。日志：本地 `build/push-all-validation-20261001.log`。部分任务复用了 Gradle 已通过结果。

## 尚未解决或验收

- 表达自然度仍需要真实使用反馈。85 条冻结样本中，Luna 修复前后整体平局；Astra 对照更好，但仅为小样本同模型自动评审，未评估成本效益，不能据此推荐生产切换。算术错误在无 persona 对照中也出现，不能声称提示词已解决模型能力限制。
- 最终动态提示说明与流失败诊断有自动测试；最终 live smoke 因共享额度不足未完成。用户已叫停昂贵评测，后续真实模型调用须另有明确要求，不能自动恢复。
- 摘要/回顾仍有相对时序歧义；历史 `invalid_grant` 和旧单次关系降级的精确根因仍未知。非空 RAG 容量已用人工夹具走真实 DJL/SQLite 验证，不能等同自然长期记忆或远端 Qdrant 验收。
- 正式发布还缺独立可信签名根与完整质量门槛证据。旧两对 A/B 的 overall 为 5/10 胜出，未达到原 70% 门槛，不能拼接部分第三对制造通过。

## 工作边界

- 遵守 `AGENTS.md`：Persona-as-Data、非阻塞、完整 VQ-VAE 和 incarnation 共享 Bio；定位代码优先已有 CodeGraph。
- 后续修复先保存具体失败样本，定位根因，再做必要范围的验证。不要自动启动付费模型评测、旧 watcher 或完整长跑。
- 生产数据库、8080 上的其他服务、OneBot/外部聊天平台不用于测试；本轮没有部署或重置记忆。
- 编译缓存、隔离数据库和原始评测证据保留在本地忽略的 `build/`；不要 blanket clean。散落的旧 `test-artifacts/` 和结果 JSON 已归档至 `build/cleanup-archive-20261001/`，不随源码发布。
- 历史逐步实施计划与被替代的中间测试报告已清理，可从提交 `8a41d97` 的 Git 历史读取；设计规范、运维说明、最终评测报告、评测工具与自动化回归测试保留。

## 有效入口

- [语气 benchmark、模型对照与局限](evaluation/2026-10-01-persona-voice-benchmark.md)
- [原著语料与公开交互研究](evaluation/2026-09-30-persona-conversational-voice.md)
- [记忆、关系评估与授权修复的最终验证](evaluation/2026-09-30-p2-resolution.md)
- [提示词优化方法](evaluation/prompt-optimization-method-and-handoff.md)
- [订阅登录与模型选择](operations/chatgpt-and-model-selection.md)
- [正式质量标准](evaluation/companion-quality-rubric.md)
