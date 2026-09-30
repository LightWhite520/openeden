# P1 授权刷新修复与措辞样本复核

日期：2026-09-30。范围：本地代码与隔离回归、既有 B-1 样本复核；没有重启 benchmark，没有真实推理调用，没有提交、推送或部署。

## 授权异常：证据与结论

`build/ab-20260930-v2/runs/A-2/server.log:15794` 记录 20:58:29 的首个授权错误，下一行是 `ChatGptOAuth$ReauthorizationRequired`，栈指向 token 请求。该版本只有 token 端点返回 `invalid_grant` 时产生这个异常。随后 A-2 的 16239、16684 行，以及 B-2 的 14709、15155、15601 行记录“未授权 ChatGPT plan”。

**已经确认的错误分类根因：** 首次 `invalid_grant` 清空 access/refresh/id token 和 scopes，但保留 active registration；下一次调用先检查 scopes，于是将需要重新登录误报为权限不足。它也使后续请求落入通用 HTTP 500。

**首次 `invalid_grant` 的底层原因仍未知。** 旧日志没有轮换成功、身份校验、保护存储发布的完整阶段证据，也没有可区分撤销/过期/重用的供应商细节。不能据此认定代理、TLS、双进程竞争或本地保存失败就是历史根因。现有存储确实在操作系统文件锁内重新读入账号，跨进程锁覆盖整个刷新与保存；双客户端回归不是独立进程或长期运行的可靠性证明。

只读检查了当前默认 DPAPI 存储：存在 active registration、access token、refresh token 和 plan scope；access token 已过期，未有 pending checkpoint。过期的 access token 本身不代表需要重新登录，refresh token 的远端可用性未探测。未输出账号标识或凭据，未修改当前登录。

## 已实施的修复

- 在权限检查前识别无可用凭据状态，首次及后续请求均报告 `CHATGPT_REAUTHORIZATION_REQUIRED`。
- 新增固定安全错误类型，区分重新登录、权限、注册、网络/服务暂不可用、身份校验、响应格式和本地保存失败。CLI 保留该分类；HTTP 返回 503 和结构化 code/message/retryable/traceId，已打开的 SSE 返回对应 error 事件。这里的 503 表示服务依赖的授权不可用，不是调用者自身认证失败。
- 成功刷新响应先写入同一保护存储的 `pendingRefresh` 检查点，再联网校验 ID token 和权限，最后激活凭据。检查点阶段清空普通 access/refresh 字段，防止使用未校验 bearer 或重复提交旧 refresh token；恢复仍在原跨进程锁内执行。恢复按收到响应时刻计算到期时间，不因恢复延迟延长有效期。
- 检查点/激活保存使用不可取消的小型持久化段；文件 I/O 仍在 `Dispatchers.IO`，文件锁争用和写入重试使用挂起 delay。原子发布的 I/O 失败最多重试 3 次。身份网络请求仍可取消。注销优先撤销 pending replacement，再清空检查点。
- 支持官方列出的 unusable refresh token 错误码及字符串/对象 error 形态。新增固定阶段日志、进程 ID 和白名单 provider_code；不记录令牌、响应正文、授权链接或私密账号信息。

这些变更不涉及 persona、Bio 向量、VQ-VAE 或 incarnation 状态。

**恢复边界：** 已持久化的检查点能承受后续身份请求、校验或最终发布失败。若进程在成功响应完整接收并保存前被终止、响应传输中断，或首次检查点写入因持续磁盘故障最终失败，客户端无法保证恢复服务端已轮换的令牌。不能承诺所有失败点都不丢令牌。身份不匹配、格式无效和权限不足的 pending 数据不会激活，也不会自动再次刷新。

## 定向验证

JDK 21，执行：

```powershell
./gradlew.bat :server:test --tests 'io.openeden.server.auth.*' --tests 'io.openeden.server.api.route.ServerApiTest' --tests 'io.openeden.server.api.route.ModelRoutesTest' --tests 'io.openeden.server.llm.*' --console=plain
```

结果：**27 passed，0 failed，0 skipped**。日志：`build/auth-refresh-regression.log`。测试 XML：`server/build/test-results/test/`。所有刷新请求使用 MockEngine 与独立临时账号存储。

新增覆盖包括：JWKS 503 后新客户端恢复且不重复刷新；校验取消保留检查点；错误身份不激活；权限/expiry 解析失败保留轮换响应；网络/5xx 保留原凭据；清空授权后另一客户端仍正确分类；官方失效错误码；pending 注销；HTTP/SSE 错误分类。Windows 原生 CreateFile 句柄禁止删除共享，实际使最终原子发布失败，释放句柄后另一客户端由检查点恢复成功。

未重跑全套工程测试；未运行真实长期刷新、双独立进程刷新或生产验收。

官方依据（本轮已读取）：

- [Refreshing tokens](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions#refreshing-tokens)：串行刷新，保存并使用最新 replacement。
- [Token reference](https://developers.openai.com/siwc/token-sharing-open-source/token-reference)：刷新响应与轮换生命周期。
- [Refresh errors](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery#refresh-errors)：失效令牌与 invalid_client 的不同处理。

## B-1 四个表达样本：上下文复核 v1

这是当前代理的上下文复核，不是独立人类或盲评批准。读取各样本及前一轮，并核对 v3 标注；没有重新抽样、改 persona 或改 validator。原始 v3 对四行均标为 `NON_OPERATIONAL`，A/B 双方均为 `PROCEDURAL`。

| 输入 | 用户语境及 B 的可见回应 | 复核意见 |
|---|---|---|
| 34 | 用户明确请“不太严格的时间管理员”给游戏时长；回答“四十五分钟”“设个闹钟”，并接明早起床的玩笑 | 属于用户请求的实际时间建议，`OPERATIONAL` 更合适。不能仅因给步骤就认定 persona 程序化。 |
| 41 | 用户自己列咖啡、脚本任务，并邀请安排启动仪式；回答摆工具、等第一口咖啡后进入状态 | “工作清单”略普通，可讨论自然度；但承接用户的启动仪式，缺乏确认业务 bug 的证据。维持日常语境，反对机械计为不合宜程序化。 |
| 91 | 用户主动将洗杯子称为“库存系统”的“低风险维护”；回答“维护批准通过”，接“慢慢来，我等你” | 日常共同隐喻和技术玩笑。行政措辞在字面上存在，但由用户主动引入，不能直接判为不合宜人格表达。 |
| 109 | 用户要求“假装站在旁边指挥”煮饭，但不要当实习员工；回答调火、避开蒸汽，并说“不当监工” | 明确请求现场烹饪指导，`OPERATIONAL` 更合适；简短必要提醒符合用户请求。 |

结论：四项不宜直接作为已确认的 persona/validator 缺陷处理；没有证据支持为这些样本禁用合理建议或技术玩笑。v3 对 operational 请求的例子较窄，且没有清晰说明用户主动使用的维护隐喻如何与不合宜程序化区分。若未来修改正式评审协议，应创建新版本并对双方对称适用，不能只修改 B 的分子。

原始 `4/124`、两对 `5/10` 胜出和所有 judgments 均保留。本次没有计算“修正后通过率”，没有将上下文复核混入正式指标。

来源校验：

- `build/ab-20260930-v2/runs/B-1/turns.jsonl` SHA256：`b4fdfa4986c8cc8c1b514b7584be044002f1a6cdfac397303212eb2d1d1c90f7`
- `build/ab-20260930-v2/judgments/response-1.json` SHA256：`a0b54500c11bb9d8f85d2afe46afb0008095103514ef6d487927a9ffb9010c4a`
- `judgments/mapping-1.json` 确认 LEFT=A、RIGHT=B；规则为 `docs/evaluation/live-ab-judge.md` v3。

## 剩余工作

历史首次 invalid_grant 原因保持未确认；新阶段日志用于未来自然出现的故障定位，不为此重启长 benchmark。TODO 的 P2 记忆压缩、golden、可控时钟覆盖以及 P3 发布条件均未由本轮验收。
