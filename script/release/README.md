# G2 RAG 发布准备

这里的脚本只在操作者显式执行时工作，不读取或写入仓库 `.env`，不重启现有容器，不自动应用 DDL、发布知识或同步索引。默认 `CHEN_RAG_ENABLED=false`、`CHEN_RAG_RELEASE_ENABLED=false`。执行知识发布仍需独立批准、匹配的 manifest/approval 和正确制品；本目录不是批准凭据。

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

检查只读取已列名的进程变量，不解析 `.env`、不打印密钥、不连接 Qdrant/模型/数据库。测试也可用 `-Configuration` 显式传入内存 hashtable。输出 `CONFIGURATION_ONLY_PASS` 只代表配置形状通过；目标集合是否存在、审批签名、制品一致性仍由正式发布控制校验。

必须明确配置：

- `SPRING_PROFILES_ACTIVE` 包含 `rag`；`CHEN_RELEASE_PROJECT_NAME` 使用新的 `chen-release-*` 项目名。
- `CHEN_RAG_QDRANT_COLLECTION` 使用单独的新目标集合，符合 `chen_[a-z0-9_]{1,90}`，不能沿用默认 `chen_platform_help_v1`。`CHEN_RELEASE_NEW_COLLECTION_CONFIRMED=true` 仅是操作者确认，检查脚本不会查询远端证明集合不存在。
- `CHEN_RAG_QDRANT_HOST`、可选 gRPC 端口（默认 6334）、`CHEN_RAG_QDRANT_TLS` 和独立 `CHEN_RAG_QDRANT_API_KEY`。非 loopback 必须 TLS；Qdrant、Embedding、聊天 Key 不得复用。启用 RAG 必须提供独立 Embedding Key。
- 启用发布控制时，`CHEN_RAG_ENABLED=true` 与 `CHEN_RAG_RELEASE_ENABLED=true` 必须同时设置，并提供非空的独立 Embedding Key。还必须提供 `CHEN_RAG_RELEASE_MANIFEST_PATH`、`APPROVAL_PATH`、`PUBLIC_KEY_PATH`、`OPERATOR`、`ENVIRONMENT`、`ARTIFACT_PATH`。这些名称均带 `CHEN_RAG_RELEASE_` 前缀；文件读取、签名和权限校验由正式发布控制执行。

## DDL

`sql/g2_rag_catalog.sql` 面向 MySQL 8.0，只创建四个独立表，不选数据库、不插入初始 Catalog、不改审核/发布状态。操作者先核对目标数据库并备份受影响状态，再用已有获准数据库工具显式应用。`IF NOT EXISTS` 不会修复既有表结构；已存在时必须核对 `SHOW CREATE TABLE`，不以脚本执行成功认定结构匹配。

`rag_catalog_state`、`rag_source_state` 完全沿用 `JdbcCatalogStore` 的持久化权威，初始化仍走受控代码。`rag_release_operation` 记录操作身份、批准哈希、代际和预算，`rag_release_event` 记录最小事件代码；它们不存另一套知识正文，不存密码、API Key 或原始凭据。

## 独立候选 Compose

`backend-rag.compose.yml` 只用于**新的候选项目**。Qdrant 和后端共享网络命名空间，后端经 `127.0.0.1:6334` 加独立 Key 访问新 Qdrant；6333/6334 不暴露到宿主机，只有选定的后端端口绑定宿主机 loopback。新卷使用项目作用域，不挂载既有生产数据卷。

额外明确设置 `CHEN_RELEASE_BACKEND_PORT`（未占用端口）、`CHEN_RELEASE_BACKEND_IMAGE`（唯一候选 tag）、`CHEN_RELEASE_CONTROL_DIRECTORY`（仅已批准 manifest、approval，挂载到只读 `/release`）、`CHEN_RELEASE_TRUST_DIRECTORY`（由部署安全负责人控制的独立信任公钥目录，挂载到只读 `/trust`），以及 Compose 中要求的 MySQL、Redis、RabbitMQ、MinIO、聊天和 JWT 配置。公钥固定从 `/trust/release-public.pem` 读取，不从操作者提交的 `/release` 目录读取。现有服务不会被本模板启动或修改，必须自行提供此项目可达且已批准的地址，不能假定原项目服务名在新网络内可达。

独立信任目录、部署配置和信任根选择必须由部署安全负责人控制，操作者不得替换、修改或自行选择信任公钥及其挂载路径；私钥只保存在审批侧仓库外。容器只读挂载不能阻止有权限的人修改宿主机文件，必须通过实际部署账户、目录 ACL 及发布权限落实隔离。代码中 approver 与 operator 不同只拒绝同名自批，不证明两者属于不同自然人。这些是必要外部前置条件，本轮没有验证真实人员或 ACL 隔离，也不擅自修改系统 ACL。

检查 Compose 时只使用 `config --quiet`，不把展开后的秘密输出到终端或报告。可用 `COMPOSE_DISABLE_ENV_FILE=true` 防止隐式载入工作目录 `.env`；若部署工具版本不支持，使用显式选定的外部受保护 `--env-file`。本目录不会为用户生成真实 `.env`。

配置、DDL、制品及发布审批全部确认后，操作者才可显式使用单独项目的 `docker compose build backend`、`docker compose up -d`。这里不自动执行这些命令，不提供 `down -v`、清库或回滚账本操作。若使用已批准的远程 Qdrant，应显式采用 TLS 与独立 Key 的部署配置，不把本模板的 loopback 明文连接改为普通跨容器连接。

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
