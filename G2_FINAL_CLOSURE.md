# 辰栈 G2 正式收口归档

日期：2026-10-26（Asia/Shanghai）。本轮仅作只读核实与归档；没有开发、构建、部署、历史测试重跑或真实模型调用。

## 归档状态与接受范围

```text
G2=CLOSED_SCOPE_LIMITED
G3=READY_TO_START
RUNNING_COMMIT=254bc26f2121e338c0d3dde676cdd4ba010fc5b4
HUMAN_ACCEPTANCE=USER_CONFIRMED_SCOPE
CHAT_RECORD_REVIEW=PENDING
R05_ROOT_CAUSE=UNKNOWN
R05_COMPLEX_QUERY=KNOWN_LIMITATION
SMTP_DELIVERY=NOT_VERIFIED
CHAT_CALLS_DELTA=0
EMBEDDING_CALLS_DELTA=0
```

上述状态为用户本次明确要求的建议归档状态，交 ChatGPT 最终记录审查。用户已将私信真人验收、文章审核知识专项验收列为已完成成果，本文件据此记录用户接受；本轮没有重新操作浏览器，也不把此前的机器验证冒充真人验收。有限范围关闭代表核心交付已被用户接受，不代表所有历史缺陷均已解决。未发现新的实际核心阻断；G3 尚未开展实施。

## 最终运行基线

只读运行核实时间：2026-10-26 02:05:43（北京时间；各检查在本轮连续完成）。

| 项目 | 核实结果 |
|---|---|
| Git HEAD | `254bc26f2121e338c0d3dde676cdd4ba010fc5b4`，与用户正式运行基线一致 |
| 后端 | 容器 running、重启数0；镜像 Commit 标签一致，实际 `/app/app.jar` SHA256 与正式制品一致 |
| 用户端 | 容器 running、重启数0；镜像 Commit 标签一致；实际挂载目录43个静态资源与正式部署清单逐文件匹配，差异0 |
| Catalog | 52条来源记录，其中39条公开、已审核且未撤销/删除；generation=2；来源正文摘要、状态、版本历史摘要与部署基线一致 |
| Qdrant | 集合 `chen_platform_help_release_v1`；请求 ok、状态 green、optimizer ok；43个物理点。既有部署证据区分为42个知识分块与1个控制点 |
| 向量配置 | 1024维、Cosine；当前 `indexed_vectors_count=0`，不宣称已建立 HNSW 索引或通过规模性能验收 |
| 尚未部署的生产修改 | 跟踪文件的工作树及暂存差异均为空；仅保留原有两个未跟踪 `auto-imports.d.ts`，与私信任务保护清单一致，不计为新的可执行业务修改 |

后端镜像：`sha256:7a64b73e2183b633db7bb3484e7f607f03cb50e267e393f82d5e10f1a2dc7374`。

用户端镜像：`sha256:186ca318b9a7a7f59fb9e315a3100fc6340aff914a845dd517330d176e2fff5f`。

实际 JAR SHA256：`e805bba83b1e68bcb9bc882ffa9b9c7df8e017a8692792446f12d26a6d980d1c`。

Catalog 内容核对摘要：`99892508aaf41d0e483eafac5c2b568bec2c4e8173344c28f8b0ed826e6bf101`；Catalog snapshot：`4ab0778c8721c80bc48f6967db478c4bba3af1feceb0019e94f2f1d6c8f26e57`。

本轮只读查询 Catalog 与 Qdrant 元数据，没有发布知识、修改索引或业务数据。Qdrant 全量向量/payload 不变的结论复用既有部署证据，本轮未重复完整向量核对。

## 核心交付与验收证据

| 成果 | 归档结论与证据边界 |
|---|---|
| G2 RAG 知识发布与持久化 | 已发布并持久化；本轮核实 Catalog 摘要与正式部署一致 |
| 39条正式知识及 Qdrant 数据 | 保留；本轮核实39条发布来源、集合正常和43个物理点；不重建、不重新编码 |
| 真实向量检索定向验证 | 复用 V01/V02 两个真实查询及改写一致性证据；实际管道包含向量检索、词法补充和排序，不外推纯 Dense Recall 或全面问答质量 |
| 文章审核知识专项真人验收 | 用户已确认，复用既有专项结论，不重验 |
| SSE 正常完成与错误处理 | 复用正常、失败、截断、重复结束及账号竞态证据；失败页面不得呈现为正常完成；R05 失败单列保留 |
| WebSocket 握手鉴权修复 | 已部署；复用13项测试及正式制品/隔离传输证据：拒绝无效 JWT、禁用、不存在或逻辑删除用户，数据库异常失败关闭；发送者来自认证会话 |
| 私信文字、图片实时通信 | 已部署；图片持久化并审核通过后投递；复用后端15项、前端50项及制品8项、实际 loopback WebSocket 11项机器证据；本次按用户确认归档真人接受 |
| 全局未读、去重及重连恢复 | 已部署并有定向机器证据；按持久消息 ID 去重，重连通过既有历史/会话查询恢复遗漏；本次按用户确认范围归档 |
| 私信真人验收 | `USER_CONFIRMED_SCOPE`，依据本次用户收口工作包；不据此补写未提供的 SMTP 或真实非参与者权限测试记录 |

## 真实模型预算

正式持久账本为 `D:/ChenStackRelease/g2-34522fe/budget/provider-attempts.ledger`。本轮 SHA256 为 `3cbb68a149148534fe69f19ac7e9336083bb085264525087bb07bafed6d577ea`，与最近运行核验证据完全相同，因此复用其计数：

| 类别 | 累计使用/上限 | 剩余 | 本轮增量 |
|---|---:|---:|---:|
| Chat | 56/100 | 44 | 0 |
| Embedding | 9/64 | 55 | 0 |

账本没有重置、退款、回退或扩额。历史53到56的3次 Chat 增长在先前部署报告中已注明来源未调查，本轮不追查、不抹除。供应商实际账单金额仍为 `UNKNOWN`；项目模型预算与 Codex 订阅额度、缓存诊断统计分别记录，不相互换算。

## 已知限制与后续跟踪

1. **R05：** `R05_ROOT_CAUSE=UNKNOWN`、`R05_COMPLEX_QUERY=KNOWN_LIMITATION`。原始最终异常类型/栈和正文证据不足，未实施猜测修复。后续如单独授权再复现，应先补充脱敏异常与阶段诊断；保持现有主题读取上限、导航/事实完整性/安全校验及模型预算。本次不重试。
2. **邮件：** 图文共用现有私信邮件路径，开关、首条未读抑制等有隔离测试；缺少实际 SMTP 收件证据，故 `SMTP_DELIVERY=NOT_VERIFIED`。后续可由用户用获准测试邮箱单独确认，不以私信页面验收替代。
3. **权限与连接边界：** 已有握手、发送者身份及隔离账号测试和真实匿名拒绝证据；真实非参与者对象权限完整 E2E 仍未单独证明。现有每账号单活动 socket，不宣称同账号多标签广播。被拒绝的握手未升级为101，但 HTTP 拒绝状态码标准化尚未完成。
4. **质量边界：** 两个检索用例及已确认专项不代表所有复杂问答、导航点击或大样本检索质量均通过。R05 原始失败及历史未测项继续保留，不改写为 PASS。

以上作为有限范围之外的后续跟踪项，不声称 G2 无缺陷。若后续出现实际越权、知识丢失或已接受核心流程失效，应按新证据重新评估相关状态。

## G3 复用与开发边界

G3 可复用现有 Catalog/JdbcCatalogStore 的来源、版本、Hash、撤销与持久化能力，QdrantKnowledgeIndex/RagKnowledgeService 检索管道，ProductRagAnswerService 的事实及导航校验，SSE 完成/失败协议，ReleaseProviderBudget 持久预算，以及已部署的私信鉴权、统一消息事件、未读与历史恢复能力。

G3 应以本 Commit 和39条正式知识为基线，按独立任务范围开发，保留历史发布审计、预算和失败证据。新知识、数据迁移、模型调用、部署及 Git 操作不因本收口自动获得授权；私信、凭据和未授权私人数据不得进入公共知识或模型上下文。不得把 G2 已知限制写成已修复，也不得将 G3 新能力倒算为 G2 成果。

## 证据索引与本轮操作

- `.work/G2_PRIVATE_MESSAGE_REALTIME_R1/DEPLOYMENT_RESULT.md`、`deployment-plan.json`、`deployment-result.json`、`final-runtime-proof.json`：正式部署、版本绑定、预算和原有验收边界。
- 同目录 `verification.json`、`artifact-realtime-proof.json`、`artifact-websocket-transport-proof.json`、`live-authorization-proof.json`：既有定向机器验证。
- `.work/G2_FINAL_CLOSEOUT_R2/01_FINAL_REPORT.md`、`real-quality-assessment.json`、`r05-analysis-and-reuse.json`：真实检索、SSE 复用与 R05 有界分析；原始 R05 失败文件保留在 `.work/G2_FINAL_ACCEPTANCE_R1/`。
- 本次用户 `G2_FINAL_CLOSURE` 工作包：用户接受范围及建议归档状态的依据。

本轮唯一新增文件为 `G2_FINAL_CLOSURE.md`；未覆盖旧报告，未修改生产源码、Docker、数据库、Qdrant、知识、预算账本或原有未跟踪文件。未执行 Git 写操作，未重跑历史测试。本文件交 ChatGPT 最终记录审查，未代替 ChatGPT 出具审查结论。
