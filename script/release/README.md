# 受控本地知识发布与只读恢复

正式发布不再使用Ed25519、签名信封、公私钥或独立签名人。`operation.json`是可信运维账户确认的操作记录，SHA256用于绑定原始字节和发现身份漂移，不能防止拥有部署/数据库/文件权限的恶意管理员篡改。本方案依赖运维账户、部署权限、受保护宿主目录和审计。

## 默认与权限

- 普通后端不提供发布HTTP路由或客服写工具；`CHEN_RAG_RELEASE_ENABLED`默认false。即使普通Main启动时误设true，没有专用命令的Invocation也会失败关闭。
- 写PUBLISH/RECOVER/REVOKE只由 `com.zcx.chenstack.rag.RagPublicationCommand` 本地非Web进程执行。它只加载DataSource、Jackson及RAG组件，不加载业务调度器、控制器或聊天服务，完成后关闭上下文退出。
- 独立入口要求显式写开关、匹配的运维身份/环境、confirmed=true的操作原件、实际JAR/资源/Manifest、持久预算及真实Catalog/集合前提。不是固定伪造签名，也不存在永真验签。
- control目录仅放manifest.json和operation.json，宿主可信运维账户可写，容器只读。budget目录独立受保护、持久保存并以可写挂载供发送前记账，不放凭据；正式目录须在仓库外。部署前检查ACL，普通用户不得改写。TLS truststore仅用于Qdrant传输安全，与已取消的发布签名无关，继续沿用。

## 构建与检查（PowerShell 7）

```powershell
pwsh -NoProfile -File script/release/Build-Backend.ps1
pwsh -NoProfile -File script/release/Prepare-ReleaseOperation.ps1 -SelfTest
pwsh -NoProfile -File script/release/Test-ReleaseConfiguration.ps1 -SelfTest
```

正式Build-Backend读取用户已提交的新HEAD，以离线Maven verify构建并核对JAR内Git身份。源码修改后必须由用户本人提交，再生成正式JAR及对应Manifest；未提交的UNSET构建只作测试，不能作为发布制品。Java正式入口重新核验两个知识资源的原始Hash、39条正文规范化Hash、版本和精确白名单，help-history及12个后台主题不发布。

操作记录字段固定为schemaVersion=1、operationId、operator、environment、manifestSha256、maxEmbeddingRequests、allowedOperation、confirmed。operationId为ASCII `[A-Za-z0-9._-]{1,80}`，与持久操作ID一致；同ID不得改记录Hash/预算/目标。没有expiresAt或签名字段。REVOKE的Embedding预算必须0。

以下只准备操作记录，只有获准操作者显式-Confirm才创建，默认/ValidateOnly不写文件。替换所有占位值，输出不得存在。首次费用账本通过-InitializeBudget创建且禁止覆盖，已有账本的后续操作省略该开关，继续使用原账本和预算ID。

```powershell
pwsh -NoProfile -File script/release/Prepare-ReleaseOperation.ps1 `
  -ManifestPath 'D:\ChenStackRelease\control\manifest.json' `
  -ArtifactPath 'D:\zcx-chen-stack\chen-stack-backend\target\zcx-chen-stack-backend-1.0-SNAPSHOT.jar' `
  -ExpectedManifestSha256 '<本次已核对的Manifest原始SHA256>' `
  -ExpectedArtifactSha256 '<本次已核对的JAR原始SHA256>' `
  -OperationId '<本次唯一操作ID>' -Operator 'codex-deployer' -Environment 'production' `
  -MaxEmbeddingRequests 64 -OperationOutputPath 'D:\ChenStackRelease\control\operation.json' `
  -InitializeBudget -BudgetLedgerPath 'D:\ChenStackRelease\budget\provider-attempts.ledger' `
  -BudgetId '<本轮固定预算ID>' -Confirm
```

此脚本检查显式Hash、JAR提交/资源和主题范围，不代替Java完整语义校验，不启动发布、不调用Provider、不改Git。数据目录及ACL必须先准备；若创建账本后后续操作失败，保留已有账本，不重建/清零。

## 持久费用边界

本轮部署将同一个 `/budget/provider-attempts.ledger` 供发布命令与后端读取查询/聊天使用，budgetId和64/20限额保持不变。同步Chat、流式Chat以及查询/发布Embedding均在实际HTTP发送前取得文件锁、校验累计记录、追加保守消耗并force落盘；失败/超时/UNKNOWN不退款。没有账本、截断、锁冲突、预算ID/额度不符或超限均在发送前拒绝，不自动创建或修复账本。费用账本不保存提示词、用户数据、API Key或响应。

该账本统计接入客户端的所有发送尝试，因此本轮窗口中的其他同客户端流量也消耗额度，不能再额外花掉64/20之外的预算。现有发布operation的Embedding发送前持久预留仍独立保留；可能保守双记账的未发送请求不能释放/重置。这是尝试次数上限，不是供应商金额或token用量证明。默认未配置费用账本时原有普通业务保持原行为；实际运维发布入口强制要求配置，受控E2E也必须用已配置的同一账本。

容器环境：`CHEN_RAG_RELEASE_BUDGET_LEDGER_PATH=/budget/provider-attempts.ledger`、`CHEN_RAG_RELEASE_BUDGET_ID=<本轮固定ID>`、`CHEN_RAG_RELEASE_MAX_EMBEDDING_HTTP_ATTEMPTS=64`、`CHEN_RAG_RELEASE_MAX_CHAT_HTTP_ATTEMPTS=20`。不得删除账本或换ID/路径绕过限额。增加预算需要新批准，不通过修改旧文件额度实现。

## 既有dev项目正式部署

保留原Compose项目dev、base顺序、端口、网络、凭据注入和Qdrant卷。现有 `backend-rag.override.yml` 仅补control只读、budget持久可写挂载与环境参数，create_host_path=false，未准备目录时拒绝；不再挂trust。`backend-rag.compose.yml`仍是隔离候选模板，不能用其新Qdrant卷代替正式索引。

正式切换前一次性授权必须绑定新Commit/JAR/Manifest、production/chen_stack/chen_platform_help_release_v1、39条范围、创建集合/Catalog写入、短时backend中断、回退及64/20付费上限。执行前核对最新29表备份及隔离恢复、实时Catalog/集合、TLS和独立Key（不打印值）、候选镜像JAR身份及旧后端回退。生成配置可使用 `config --quiet`，禁止打印含密钥的展开配置。

下面只是获准后的命令形态，不是部署授权。先按原部署注入可信环境并在当前进程设控制路径、预算路径/ID和操作者。普通后端不得保留写开关；设置本地命令时使用一次性容器环境。若现有RAG后端持有Catalog owner锁，按批准计划只停止backend以交接，不停其他服务。

```powershell
pwsh -NoProfile -File script/release/Test-ReleaseConfiguration.ps1 -ExistingDeployment
if ($LASTEXITCODE -ne 0) { throw '发布配置预检失败。' }
docker compose --project-name dev --project-directory 'D:\zcx-chen-stack\script\dev' `
  --env-file 'D:\zcx-chen-stack\script\dev\.env' `
  -f 'D:\zcx-chen-stack\script\dev\docker-compose.yml' `
  -f 'D:\zcx-chen-stack\script\release\backend-rag.override.yml' `
  run --rm --no-deps -e CHEN_RAG_ENABLED=true -e CHEN_RAG_RELEASE_ENABLED=true `
  -e CHEN_RAG_RELEASE_RECOVERY_ENABLED=false --entrypoint java backend `
  -Dloader.main=com.zcx.chenstack.rag.RagPublicationCommand -cp /app/app.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher --spring.main.web-application-type=none
if ($LASTEXITCODE -ne 0) { throw '本地运维操作失败，停止；保留所有记录，不自动重试。' }
```

`CHEN_RELEASE_PROJECT_NAME`须明确dev，profiles保留dev并加rag；`CHEN_RELEASE_CONTROL_DIRECTORY`和`CHEN_RELEASE_BUDGET_DIRECTORY`为已核对外部绝对目录。首次发布需新集合确认；稳态读取及撤销不得沿用创建确认。宿主Manifest/operation目录映射为/release，JAR固定/app/app.jar。数据库名未写入Manifest字段，必须由实际部署检查与一次性授权限定为chen_stack。

## AI 知识模式手动切换

实际 dev 部署由 `script/dev/.env` 经原 Compose 文件链向 backend 传入 `AI_KNOWLEDGE_MODE`，Spring 使用 `chen.ai.knowledge-mode` 绑定，默认 `rag`。固定模式为 `chat`（不检索 RAG/Wiki）、`rag`（既有 G2 问答检索）和 `wiki`（仅当前有效且正式发布的 Wiki）；`hybrid`、`auto` 尚未实现，启动拒绝，非法值也不回退。模式只改变知识依据，不扩大小辰业务工具权限。

`wiki` 还要求在已批准并完成 Wiki SQL/触发器部署的环境显式设置 `CHEN_WIKI_ENABLED=true`、`CHEN_WIKI_PRODUCT_ENABLED=true`；未就绪时拒绝启动或知识读取失败关闭，不使用旧索引或 RAG 兜底。它不需要启用编译器。`chen-stack-backend/.env.example` 是变量模板，普通 JVM 启动须显式导入环境变量。

修改 `.env` 后仅对原 Compose 项目的 backend 执行 `up -d --no-deps --force-recreate backend` 才能应用；`restart` 不会重新注入变量。必须保留原有项目名、所有 `-f` 覆盖文件、`--env-file`、镜像和预算挂载，不能用候选 Compose 替换正式项目。正式容器切换需用户另行批准，本轮只允许隔离验证。切换不修改 Java、不重建知识库、不删除 Qdrant 集合、不重新编译或发布 Wiki。可通过仅管理员可读的模式诊断接口核对实际后端模式；不得打印完整 `docker compose config` 或容器环境。

## 恢复、幂等、撤销和失败关闭

| 场景 | 行为 |
|---|---|
| 首次PUBLISH | 只在受控本地命令按明确许可初始化空目录、创建唯一目标集合，发布39条，持久保存operation Hash/ID、operator、模式、环境、代际、目标和预算。 |
| 增量UPDATE | 保持39条主题白名单，仅变更已有已发布主题；正文或元数据修改须使用新版本。先核验当前完成回执与旧索引完整摘要，仅编码变化主题的新块，保留其余向量并更新目录绑定，清理变化来源的旧块。不得初始化目录或创建集合。完成后沿用正常只读重启恢复；中断且目录已应用时失败关闭，保留现场供核验，不自动重放。 |
| 普通JVM重启/Docker重建 | 服务器写开关false、recovery-enabled=true；只需原Manifest和已完成持久记录，不读operation文件。核对同一JAR/资源、当前代际/快照、来源/墓碑、数据库/集合owner、index authority/epoch及全物理摘要，0发布/0Embedding/0目录写入。 |
| 中断发布 | 普通启动拒绝续作。只有显式本地命令使用同一不可变操作ID/记录和剩余预算才可继续；不清零预算。 |
| 写入RECOVER | 显式新受控操作与匹配当前代际/快照的Manifest；不改变知识正文/版本，不复活撤销来源。旧签名完成记录不能直接冒认为新模式。 |
| REVOKE | 显式本地操作、预算0，持久墓碑立即使旧receipt失效；即使物理删除失败也不复活。剩余来源需新RECOVER完成后恢复读取。 |
| JAR升级/回退 | 新制品/Manifest重新核验；不能把旧Manifest当新JAR授权。保留账本，兼容性不符则RAG关闭。 |

持久旧SQL列兼容保留：approval_sha256现在存操作记录原始SHA256，approver_id存CONTROLLED_OPERATOR，不是审批人或签名证明。VALIDATED审计标记为LOCAL_OPERATOR_AND_SCOPE_VERIFIED。只读恢复只认可新模式的完整完成记录及索引摘要；同Manifest出现多条可候选完成记录则失败关闭，不猜哪一条。

异常时关闭RAG和发布/恢复开关以保留普通客服；保留Catalog、墓碑、索引及费用账本。禁止清卷、删除来源、恢复旧账本或自动重试来消除失败。真实Provider质量、正式重启及真人验收另记实际证据，Mock/HTTP200不构成最终验收，G2不由执行工具自闭。
