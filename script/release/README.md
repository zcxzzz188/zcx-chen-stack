# 受控 RAG 知识发布与恢复

本手册用于已经准备好独立 Qdrant、发布权限和真实预算的运维环境。[基础部署和手动 Wiki 发布](../../README.md#5-快速开始)无需执行这里的命令。`init -WithRag` 只启动本地 Qdrant，默认使用无 TLS 的容器网络地址和本地集合名，不能直接通过正式发布预检。

## 发布前准备

- 已应用[数据库迁移](../deploy/README.md#数据库迁移)，核对并备份 MySQL、Qdrant 数据、当前镜像、配置和账本。确定实际 Compose 项目、网络、数据库、集合和回退窗口。
- 配置本次目标的 `SPRING_PROFILES_ACTIVE`（包含 `rag`）、独立的聊天、Embedding 和 Qdrant 凭据，以及适配 Embedding Key 的 `CHEN_RAG_EMBEDDING_BASE_URL` 与模型。正式预检要求非 loopback Qdrant 使用 TLS，集合名须明确且不能用默认的 `chen_platform_help_v1`。确认地址从容器可达，JVM 信任库已信任所用证书。
- 在 `.runtime/<实例名>/rag/control` 和 `.runtime/<实例名>/rag/budget` 或实际部署的等效私有位置准备清单、操作记录和预算账本。Compose 分别挂载为 `/release`（只读）和 `/budget`（可写）。覆盖文件不会创建缺失目录或安装 TLS 信任库。
- 本地 Java 发布入口是 `com.zcx.chenstack.rag.RagPublicationCommand`。普通 Web 后端保持写入开关关闭。日常使用量与受控写操作的有限预算分别管理，参见[AI 持续使用说明](../prod/README-AI-USAGE.md)。

## 构建和准备操作记录

从仓库根目录使用 PowerShell 7。正式制品应来自用户确认的干净工作区。脚本读取本地 HEAD 并构建当前源码，JAR 中的提交号本身无法证明构建前工作区干净。`Build-Backend.ps1` 使用离线 Maven `verify`，需要先备齐依赖及插件缓存。普通 `init` 打包的默认 `UNSET` JAR 不能作为正式发布制品。

```powershell
pwsh -NoProfile -File ./script/release/Prepare-ReleaseOperation.ps1 -SelfTest
pwsh -NoProfile -File ./script/release/Test-ReleaseConfiguration.ps1 -SelfTest
pwsh -NoProfile -File ./script/release/Build-Backend.ps1
```

自检只使用合成数据。正式构建会输出制品路径、提交号和 SHA256。按实际镜像将同一 JAR 放入容器的 `/app/app.jar`，执行写入或只读恢复前核对容器内哈希。

仓库提供两个[知识资源](../../chen-stack-backend/src/main/resources/knowledge/)供审校。运维人员根据 [`PublicationManifest` 契约](../../chen-stack-backend/src/main/java/com/zcx/chenstack/rag/PublicationManifest.java)准备 `manifest.json`，脚本不会自动生成或批准清单。清单绑定提交、制品与资源原始 SHA256、环境、集合、Embedding 签名、目录代际与快照，以及 39 个允许主题的版本和规范化正文哈希。首次发布只有在已核实空目录和新集合时才能选择初始化。配置和签名计算以实际代码为准。

下面以默认实例路径为例。替换全部占位值，确认两个输出文件不存在。默认或 `-ValidateOnly` 只验证，`-Confirm` 才会生成操作记录。首次预算账本用 `-InitializeBudget` 创建，后续保留原账本并省略该开关。

```powershell
$rag = Join-Path (Get-Location) '.runtime/dev/rag'
$artifact = 'chen-stack-backend/target/zcx-chen-stack-backend-1.0-SNAPSHOT.jar'
pwsh -NoProfile -File ./script/release/Prepare-ReleaseOperation.ps1 `
  -ManifestPath (Join-Path $rag 'control/manifest.json') `
  -ArtifactPath $artifact `
  -ExpectedManifestSha256 '<清单原始 SHA256>' `
  -ExpectedArtifactSha256 '<JAR 原始 SHA256>' `
  -OperationId '<本次唯一操作 ID>' -Operator '<实际操作者>' -Environment '<清单环境>' `
  -MaxEmbeddingRequests 64 -OperationOutputPath (Join-Path $rag 'control/operation.json') `
  -InitializeBudget -BudgetLedgerPath (Join-Path $rag 'budget/provider-attempts.ledger') `
  -BudgetId '<已批准预算 ID>' -Confirm
```

按批准范围填写 `MaxEmbeddingRequests`，撤销操作必须为 0。操作记录不可通过重写同一 ID、清零预算或恢复旧账本来重试。SHA256 用于绑定身份和发现漂移，操作记录依赖可信运维账户及目录权限。

## 使用原部署配置预检

`Test-ReleaseConfiguration.ps1` 只验证传入的配置和文件。它接受 `-Configuration` 哈希表，省略该参数时读取当前 PowerShell 进程环境，**不会**自动读取 `.env` 或 Docker。候选模式的项目名以 `chen-release-` 开头，检查已有项目时使用 `-ExistingDeployment`。预检不验证远程集合存在性，不启动容器或调用 Provider。

对已由受控方式管理的项目，保留原项目名、完整 `-f` 顺序、原网络与卷、私有 env 和镜像。仅当实际部署使用 `.runtime/dev/compose.json` 时才以它为基底。新普通实例的基底是 [`script/dev/docker-compose.yml`](../dev/docker-compose.yml)，非默认实例还要使用生成的 override。`backend-rag.override.yml` 提供控制和预算挂载，独立的 `backend-rag.compose.yml` 是候选模板，不能替换已有实例。

以下是**已使用迁移后 Compose 的 `dev` 实例**的预检示例。运行前确认变量与实际发布计划一致。 `CHEN_RELEASE_CONTROL_DIRECTORY`、`CHEN_RELEASE_BUDGET_DIRECTORY`、预算 ID、操作者、环境和 RAG 服务配置须能通过当前 Compose 文件链解析。只有已确认需要新建集合的写操作才把新集合确认设为 `true`。

```powershell
$project = 'dev'
$artifact = 'chen-stack-backend/target/zcx-chen-stack-backend-1.0-SNAPSHOT.jar'
$compose = @('compose', '-p', $project, '--project-directory', 'script/dev',
  '--env-file', '.runtime/dev/.env', '-f', '.runtime/dev/compose.json',
  '-f', 'script/release/backend-rag.override.yml')
docker @compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose 配置预检失败' }
$rendered = docker @compose config --format json
if ($LASTEXITCODE -ne 0) { throw 'Compose 配置解析失败' }
$backend = ($rendered -join [Environment]::NewLine | ConvertFrom-Json -AsHashtable).services.backend
$check = @{}
foreach ($setting in $backend.environment.GetEnumerator()) { $check[$setting.Key] = [string]$setting.Value }
$check.CHEN_RELEASE_PROJECT_NAME = $project
$check.CHEN_RELEASE_CONTROL_DIRECTORY = ($backend.volumes | Where-Object target -eq '/release').source
$check.CHEN_RELEASE_BUDGET_DIRECTORY = ($backend.volumes | Where-Object target -eq '/budget').source
$check.CHEN_RELEASE_NEW_COLLECTION_CONFIRMED = 'false' # 首次获准创建集合时才改为 true
$check.CHEN_RAG_ENABLED = 'true'
$check.CHEN_RAG_RELEASE_ENABLED = 'true'
$check.CHEN_RAG_RELEASE_RECOVERY_ENABLED = 'false'
& ./script/release/Test-ReleaseConfiguration.ps1 -Configuration $check -ArtifactPath $artifact -ExistingDeployment
```

`$rendered` 和 `$check` 含解析后的配置，勿输出或保存。预检通过后，核对实际目标及授权。如果原后端持有目录 owner 锁，按发布计划停止该后端完成交接。下面的写操作会更新知识目录或索引，可能调用付费 Embedding，使用同一已核对的 `$compose`。

```powershell
docker @compose run --rm --no-deps `
  -e CHEN_RAG_ENABLED=true -e CHEN_RAG_RELEASE_ENABLED=true `
  -e CHEN_RAG_RELEASE_RECOVERY_ENABLED=false --entrypoint java backend `
  -Dloader.main=com.zcx.chenstack.rag.RagPublicationCommand -cp /app/app.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher --spring.main.web-application-type=none
if ($LASTEXITCODE -ne 0) { throw '发布未完成，保留记录并排查' }
```

## 读写开关与后续操作

| 场景 | 要求与行为 |
|---|---|
| 首次 `PUBLISH` | 按清单对已确认的目标初始化，生成完成记录，供只读恢复授权。 |
| 增量 `UPDATE` | 复用现有目录和集合，核对旧回执和索引摘要，只向量化变化的已发布主题，变更需新版本。 |
| 日常只读恢复 | `CHEN_RAG_RELEASE_ENABLED=false`、`CHEN_RAG_RELEASE_RECOVERY_ENABLED=true`，仍须开启 RAG。使用对应清单及持久完成记录，无需本次操作文件。恢复不重新向量化或写目录。预检使用上述实际配置并取消新集合确认。 |
| 中断发布 / 写入 `RECOVER` | 普通启动不会续写。按中断状态核对原操作记录、目录代际、索引摘要和剩余预算，再决定续作或显式恢复。 |
| `REVOKE` | 预算为 0，持久化撤销记录使旧授权失效。按当前目录重新受控恢复剩余来源。 |
| JAR 升级 / 回退 | 重新验证制品、资源、清单和回执的对应关系。普通部署脚本的 `upgrade` 和 `rollback` 会拒绝受控实例。 |

受控覆盖文件设置最多 64 次 Embedding 和 20 次 Chat HTTP 尝试。实际发送前持久记账，失败或不确定结果不退款。额度是尝试次数，实际费用以供应商账单为准。正常 Web 运行启用 `NORMAL_AI_USAGE` 时使用持续计量，发布命令仍使用独立预算文件。出现回执、制品、目录或索引不匹配时保留现场并按批准计划恢复。

知识模式可在 **AI知识管理 → AI配置** 在线切换。数据库中的在线值优先，只有变更部署默认值才需修改实际生效的 env 和重建后端。维护已有实例时沿用原项目和全部配置；更改模式不会发布知识。Wiki 模式需开启 Wiki 产品及完成迁移，Hybrid 需要两侧各自具备有效知识才能覆盖两路检索。
