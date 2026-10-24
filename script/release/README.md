# G2 RAG 发布准备

这里的脚本只在操作者显式执行时工作，不读取或写入仓库 `.env`，不重启现有容器，不自动应用 DDL、发布知识或同步索引。默认 `CHEN_RAG_ENABLED=false`、`CHEN_RAG_RELEASE_ENABLED=false`、`CHEN_RAG_RELEASE_RECOVERY_ENABLED=false`。执行知识发布仍需独立批准、匹配的 manifest/approval 和正确制品；本目录不是批准凭据。

## 本地构建与检查（PowerShell 7）

从仓库根运行无网络自检：

```powershell
pwsh -NoProfile -File script/release/Test-ReleaseConfiguration.ps1 -SelfTest
```

使用已缓存的 Maven 依赖，构建并运行正式测试；依赖缺失时失败，不自动联网下载：

```powershell
pwsh -NoProfile -File script/release/Build-Backend.ps1
```

脚本只读本地 `git rev-parse HEAD`，将 `chen.release.gitCommit` 交给正式 POM 的资源过滤，检查成品 JAR **根目录** `META-INF/chen-release-build.properties` 的 `gitCommit`，并兼容仅位于 `BOOT-INF/classes/META-INF/chen-release-build.properties` 的布局；两处同时存在或同名条目重复时拒绝，避免构建身份歧义。当前 Spring Boot repackage 将此 `META-INF` 元数据保留在 JAR 根目录，知识资源仍位于 `BOOT-INF/classes/knowledge/`。脚本输出 JAR 路径和 SHA256。HEAD 是当前源码的基准提交，不代表工作树干净，也不代替独立审查或最终提交。

操作者从受保护的凭据来源配置**当前进程环境变量**后运行：

```powershell
pwsh -NoProfile -File script/release/Test-ReleaseConfiguration.ps1
```

检查只读取已列名的进程变量，不解析 `.env`、不打印密钥、不连接 Qdrant/模型/数据库。测试也可用 `-Configuration` 显式传入内存 hashtable。发布或只读恢复启用时，还会检查显式指定的宿主机控制目录、独立信任目录、Manifest/Approval 原文件、公钥格式、审批字段、JAR SHA256 及 JAR 内嵌 Git 身份；`-ArtifactPath` 可指定待复制到 `/app/app.jar` 的宿主机 JAR，默认使用正式 `target/zcx-chen-stack-backend-1.0-SNAPSHOT.jar`。禁用时不读取这些文件。输出 `CONFIGURATION_ONLY_PASS` 仍不证明真实签名权威、目录代际、远端集合、索引或人员/ACL 隔离；这些由后端控制与独立人工验收确认。

必须明确配置：

- `SPRING_PROFILES_ACTIVE` 包含 `rag`；候选项目的 `CHEN_RELEASE_PROJECT_NAME` 使用新的 `chen-release-*` 项目名。既有部署的只读预检显式加 `-ExistingDeployment`，并填入从原部署记录核对的真实项目名。
- `CHEN_RAG_QDRANT_COLLECTION` 使用明确批准的目标集合，符合 `chen_[a-z0-9_]{1,90}`，不能沿用默认 `chen_platform_help_v1`。仅当新的受控 PUBLISH/RECOVER 操作在 Manifest 中指定 `createCollection=true` 时，才要求 `CHEN_RELEASE_NEW_COLLECTION_CONFIRMED=true`；对已有集合的操作、REVOKE 和正常只读恢复均设为 `false`。检查脚本不查询远端证明集合存在或为空。
- `CHEN_RAG_QDRANT_HOST`、可选 gRPC 端口（默认 6334）、`CHEN_RAG_QDRANT_TLS` 和独立 `CHEN_RAG_QDRANT_API_KEY`。非 loopback 必须 TLS；Qdrant、Embedding、聊天 Key 不得复用。启用 RAG 必须提供独立 Embedding Key。
- 发布控制 `CHEN_RAG_RELEASE_ENABLED=true` 与只读恢复 `CHEN_RAG_RELEASE_RECOVERY_ENABLED=true` **互斥**，任一启用都要求 `CHEN_RAG_ENABLED=true` 和独立 Embedding Key。只读恢复不会调用 Embedding，但恢复后的正常语义检索仍使用该 Key。
- 两种模式均提供 `CHEN_RELEASE_CONTROL_DIRECTORY`、`CHEN_RELEASE_TRUST_DIRECTORY` 两个已存在且独立、不相互嵌套的宿主机绝对目录，分别只含已核对的 `manifest.json` / `approval.json` 与独立维护的 `release-public.pem`。公钥文件只含公开的 Ed25519 X.509/SPKI `BEGIN PUBLIC KEY`，不含私钥。
- 容器路径固定为 `CHEN_RAG_RELEASE_MANIFEST_PATH=/release/manifest.json`、`APPROVAL_PATH=/release/approval.json`、`PUBLIC_KEY_PATH=/trust/release-public.pem`、`ARTIFACT_PATH=/app/app.jar`；这些后缀均带 `CHEN_RAG_RELEASE_` 前缀。还需显式设置 `CHEN_RAG_RELEASE_OPERATOR`、`CHEN_RAG_RELEASE_ENVIRONMENT`。宿主机路径不应直接填到容器路径参数中。预检不会代替后端原字节验签。

## DDL

`sql/g2_rag_catalog.sql` 面向 MySQL 8.0，只创建四个独立表，不选数据库、不插入初始 Catalog、不改审核/发布状态。操作者先核对目标数据库并备份受影响状态，再用已有获准数据库工具显式应用。`IF NOT EXISTS` 不会修复既有表结构；已存在时必须核对 `SHOW CREATE TABLE`，不以脚本执行成功认定结构匹配。

`rag_catalog_state`、`rag_source_state` 完全沿用 `JdbcCatalogStore` 的持久化权威，初始化仍走受控代码。`rag_release_operation` 记录操作身份、批准哈希、代际和预算，`rag_release_event` 记录最小事件代码；它们不存另一套知识正文，不存密码、API Key 或原始凭据。

## 独立候选 Compose

`backend-rag.compose.yml` 只用于**新的候选项目**。Qdrant 和后端共享网络命名空间，后端经 `127.0.0.1:6334` 加独立 Key 访问新 Qdrant；6333/6334 不暴露到宿主机，只有选定的后端端口绑定宿主机 loopback。新卷使用项目作用域，不挂载既有生产数据卷。

额外明确设置 `CHEN_RELEASE_BACKEND_PORT`（未占用端口）、`CHEN_RELEASE_BACKEND_IMAGE`（唯一候选 tag）、`CHEN_RELEASE_CONTROL_DIRECTORY`（仅已批准 manifest、approval，挂载到只读 `/release`）、`CHEN_RELEASE_TRUST_DIRECTORY`（由部署安全负责人控制的独立信任公钥目录，挂载到只读 `/trust`），以及 Compose 中要求的 MySQL、Redis、RabbitMQ、MinIO、聊天和 JWT 配置。公钥固定从 `/trust/release-public.pem` 读取，不从操作者提交的 `/release` 目录读取。现有服务不会被本模板启动或修改，必须自行提供此项目可达且已批准的地址，不能假定原项目服务名在新网络内可达。

独立信任目录、部署配置和信任根选择必须由部署安全负责人控制，操作者不得替换、修改或自行选择信任公钥及其挂载路径；私钥只保存在审批侧仓库外。容器只读挂载不能阻止有权限的人修改宿主机文件，必须通过实际部署账户、目录 ACL 及发布权限落实隔离。代码中 approver 与 operator 不同只拒绝同名自批，不证明两者属于不同自然人。这些是必要外部前置条件，本轮没有验证真实人员或 ACL 隔离，也不擅自修改系统 ACL。

检查 Compose 时只使用 `config --quiet`，不把展开后的秘密输出到终端或报告。可用 `COMPOSE_DISABLE_ENV_FILE=true` 防止隐式载入工作目录 `.env`；若部署工具版本不支持，使用显式选定的外部受保护 `--env-file`。本目录不会为用户生成真实 `.env`。

配置、DDL、制品及发布审批全部确认后，操作者才可显式使用单独项目的 `docker compose build backend`、`docker compose up -d`。这里不自动执行这些命令，不提供 `down -v`、清库或回滚账本操作。若使用已批准的远程 Qdrant，应显式采用 TLS 与独立 Key 的部署配置，不把本模板的 loopback 明文连接改为普通跨容器连接。

## 既有部署的发布/恢复挂载

`backend-rag.override.yml` 是供 `script/dev/docker-compose.yml` 或既有 prod base **显式合并**的 override，只补后端公共控制文件、独立信任目录及身份输入，不改变镜像、数据库、Qdrant、网络、端口、卷或其他服务。三个开关默认关闭；仅选择该文件不会执行发布。宿主机目录必须预先存在，`create_host_path=false` 防止拼错路径时自动创建空目录。

由部署负责人从原部署记录确认项目名、原 base 文件和凭据注入方式；不得换一个项目名把既有实例当成候选实例，也不得把候选 Compose 的新 Qdrant 卷当作正式索引。下例只做静态检查（PowerShell 7），不启动或替换容器：

```powershell
# 先在当前进程配置上文列出的参数；不要打印或粘贴秘密。
$env:COMPOSE_DISABLE_ENV_FILE = 'true'
pwsh -NoProfile -File script/release/Test-ReleaseConfiguration.ps1 -ExistingDeployment
if ($LASTEXITCODE -ne 0) { throw 'Release precheck failed' }
docker compose --project-name $env:CHEN_RELEASE_PROJECT_NAME `
    -f script/dev/docker-compose.yml -f script/release/backend-rag.override.yml config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration failed' }
```

若原部署使用多个 base 文件，必须保持原有文件及顺序，再将本 override 放到最后。不要输出展开后的 `docker compose config`；其中可能含秘密。静态检查成功不授予部署权限，真正替换容器仍需独立授权。

## 正常重启、修复、升级与撤销

| 场景 | 开关与审批 | 行为和边界 |
|---|---|---|
| 首次发布 | `RELEASE_ENABLED=true`，`RECOVERY_ENABLED=false`；未过期 PUBLISH 签名 | 仅按 Manifest 批准初始化空 Catalog/新集合、发布 39 个用户主题；`help-history` 和 12 个后台主题排除。发布预算持久化且发送前预留；失败请求也消耗预留。 |
| 无数据变化的正常 JVM/Docker 重启 | `RELEASE_ENABLED=false`，`RECOVERY_ENABLED=true`；保留原 PUBLISH/RECOVER 的 Manifest、Approval 和可信公钥 | 对历史原签名、同一 JAR、环境、数据库身份、当前代际/快照、来源状态、集合绑定及全索引摘要进行只读校验。原授权可过期；不重新发布、不写 Catalog、不创建集合、不调用 Embedding。此模式不能用来继续未完成操作。 |
| 发布中断或索引不一致 | 发布开关启用、恢复开关关闭；有效且匹配的原审批仅可在原检查点/预算内继续，或新批准 RECOVER | 写索引/Embedding 仍需未过期授权和持久预算。正常只读恢复不会自动修补。 |
| 旧 COMPLETED 没有索引摘要 | 新的、独立批准的 RECOVER，明确当前代际/快照和费用 | 先受控重建并生成完整摘要，以后同一制品的正常重启才能零 Embedding 恢复；不能仅信任旧 COMPLETED 字段。 |
| JAR 升级或回退 | 目标提交构建对应 JAR、新 Manifest、新独立 RECOVER 授权 | 仅代码变化、知识内容/版本/元数据仍与持久来源一致时可用 RECOVER；它不更新知识正文或来源版本。旧 Manifest 的 JAR Hash 不能代表新代码。重新核对资源、持久 schema/状态和索引兼容性；未经验证的旧版本不准直接恢复读取。不能回滚 Catalog 账本或清理正式索引来配合旧 JAR。 |
| 来源撤销 | 新 REVOKE 签名，预算为 0，发布开关启用、恢复开关关闭 | 持久墓碑立即令旧读取凭据失效；即使物理删除失败也不复活来源。撤销使原完成快照失效，剩余来源要另行授权 RECOVER 后才恢复读取。REVOKE Receipt 不能当作正常重启读取凭据。 |

表中的 `RELEASE_ENABLED` / `RECOVERY_ENABLED` 均带 `CHEN_RAG_RELEASE_` 前缀。写操作完成后由获准部署方切换为只读恢复配置，保留相应原件；不要让限时写操作 Runner 随普通容器重启重复执行。`ApplicationRunner` 只在对应显式开关开启时执行，双开必须失败。

失败关闭时保持普通客服可用的回退入口：按部署授权关闭 RAG 与两个控制开关，保留 Catalog、墓碑、发布账本和正式 Qdrant 数据以供调查。不要删除数据、覆盖已完成记录、下调权限门禁或改用合成签名。真正的部署回退须先验证目标 JAR/配置的兼容边界。

读取 Embedding 的日上限目前是每进程上限，重启会重置；它不能证明跨进程/跨实例总日额。发布的审批级预算独立持久化，正常只读恢复消耗为 0。真实 Provider 质量、生产 TLS/ACL 和真人体验仍需另行授权验收。

## 外部审批人离线签名（Java 21，PowerShell 7）

审批人先独立核对 manifest 原文件及其制品/资源哈希、主题清单、环境、目标集合、操作和预期目录代际，再签名。操作者不能给自己审批。通过既有受保护密钥流程，在**仓库外**预置 Ed25519 私钥（未加密 PKCS8 PEM，`BEGIN PRIVATE KEY`）与匹配公钥（X.509/SPKI PEM，`BEGIN PUBLIC KEY`）；本工具不生成正式密钥、不复制或输出私钥，也不读取 `.env`。私钥文件权限与保管由审批人负责。

先在仓库根执行无文件密钥、无网络自检：

```powershell
java script/release/SignReleaseApproval.java --self-test
```

下例路径和身份为示例，审批人必须换成已独立核对的真实批准对象。输出目录须已存在，输出审批文件必须不存在。参数顺序为私钥、公钥、manifest、输出、approvalId、approver、operator、environment、operation、UTC 截止时间、最大 Embedding 请求数；全部显式提供：

```powershell
$expiresAt = [DateTime]::UtcNow.AddHours(2).ToString('yyyy-MM-ddTHH:mm:ssZ')
$approvalArgs = @(
    'C:/ApprovedKeys/reviewer-private.pem'
    'C:/ApprovedKeys/reviewer-public.pem'
    'C:/ApprovedRelease/manifest.json'
    'C:/ApprovedRelease/approval.json'
    'approval-20261024-01'
    'reviewer-a'
    'operator-b'
    'production'
    'PUBLISH'
    $expiresAt
    '64'
)
java script/release/SignReleaseApproval.java @approvalArgs
if ($LASTEXITCODE -ne 0) { throw 'Approval signing failed' }
```

`operation` 仅可为 `PUBLISH`、`RECOVER`、`REVOKE`；REVOKE 的预算必须为 0，其他操作预算为显式非负 int。日期必须带 UTC `Z` 且尚未过期。`approvalId` 只接受 ASCII `[A-Za-z0-9._-]{1,80}`；approver/operator 以字母或数字开头，后续可含 `._:@/-`，最长 128 字符；environment 以字母或数字开头，后续可含 `._-`，最长 80 字符。工具采用比服务端更窄的身份字符集，避免构造 JSON 时引入转义歧义。

工具按 `PublicationAuthorization.Grant` 固定字段生成 UTF-8 payload，对 **manifest 原始字节**计算 SHA256，使用 Ed25519 签署 **payload 原始字节**，再把 payload 与 signature 各自编码成 Base64URL without padding，写入 `{ "payload": "...", "signature": "..." }` envelope。不要重新序列化或改写 payload。工具会用给定公钥自验签，并输出 `PUBLIC_KEY_SPKI_SHA256`；这是公钥 DER/SPKI 的 SHA256，不是 PEM 文本文件的哈希。

审批人通过独立受信渠道核对并交付公钥指纹及审批文件。部署安全负责人按已批准流程维护 `/trust` 及信任根，操作者不得根据自己的签名工具输出替换或自选信任锚。工具只证明签名与所给公钥一致，不解析完整 manifest 或证明内容获批；正式后端仍核对可信公钥、操作者、环境、操作、过期时间、原始 manifest 哈希、制品和目录状态。签名成功不执行发布或 Provider 调用。
