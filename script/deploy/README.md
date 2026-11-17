# 辰栈本地部署与运维

以下命令在 **Windows 11 + PowerShell 7** 中从仓库根目录执行。启动 Docker Desktop，使用 Linux 容器和支持 `docker compose config --format json` 的 Compose 插件。首次正常构建需要 JDK 21、Maven 3.9+、Node.js 22.12+ 和 npm，以及镜像和依赖仓库的网络访问。Linux/macOS 有脚本分支，但此指南的部署步骤针对 Windows。

`up`、`init`、`restart`、`upgrade`、`rollback` 结束前都会校验宿主机连通性：容器运行、实际端口发布存在、后端与两个前端可从 `127.0.0.1` 访问。任一项不满足就打印诊断并非 0 退出，不再仅凭容器 `Running` 或容器内健康 `UP` 报告成功。日常检查和故障处理使用同一条命令入口的 `doctor` 与 `repair-ports`，见下文。

## 首次初始化

按[根目录 README](../../README.md#52-首次部署空数据卷)复制私有 env 并填写 DeepSeek 和 Embedding 配置，再从 PowerShell 7 执行：

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance chenstack-release -PortBase 29220 -Action init
```

`init` 要求独占的新容器、网络和卷。它建立 Redis ACL、本地服务凭据和 Qdrant TLS，从当前源码构建后端及两个前端，然后启动八个服务。MySQL 在新卷上导入[当前基线](../../sql/bootstrap/chen_stack_baseline.sql)。基线校验数据库为空，创建 33 张表和 15 个触发器，并预置角色、菜单和权限。空库保留完整的 8 张 AI 表，知识库从空状态开始。

启动完成后使用脚本输出的两个网站地址。管理端提供[两个本地演示账号](../../README.md#52-首次部署空数据卷)，安装时分别生成 BCrypt 密码摘要。首次登录后更新密码。注册与找回密码需要 SMTP。日常启停和升级会沿用当前账号。

`-SkipBuild` 需要三个实例专用的匹配镜像已存在。首次克隆使用正常 `init`。

### 首次初始化与已有数据

`init` 只适用于**没有容器、网络和数据卷**的全新实例，任一资源已存在都会直接拒绝。它还会导入基础 SQL 并写入初始管理员密码，属于不可逆的“新建”流程。

判断该用哪条命令：

| 现状 | 使用 | 不要做 |
|---|---|---|
| 全新克隆、本机没有该实例 | `init` | 不要手工复制别人的 `.runtime/` 或数据卷 |
| 已有该实例，只是被 `stop` 或电脑重启过 | `up` | 不要重跑 `init`，也不要删除容器“重来” |
| 已有数据，需要更新代码或配置 | `upgrade`（受控实例按发布手册） | 不要 `docker compose down -v`、不要 `docker system prune` |
| 已有正式/受控发布数据 | 按 [受控发布手册](../release/README.md) | 不要用普通 `init`/`upgrade` 覆盖发布清单 |
| 疑似故障，服务还能访问或容器在运行 | 先 `doctor`（只读） | 不要先重启、先删除、先重建 |

恢复已有数据时保留实例的 `.runtime/<实例名>/`（env、Redis ACL、账本、发布授权）以及 Docker 命名卷；`up` 不会重建或重置它们。初始化新实例不会读取、覆盖或迁移已有实例的数据。

## Windows 本机正式实例（G4）

正式实例使用 `.runtime/prod/compose.json` 中的本机私有配置。首次迁移须以当前正式容器为依据，保存现行环境变量、命令、端口、健康检查和镜像 ID。现有命名卷和 `chen-stack-network` 按 external 引用。保留私有配置的访问控制，凭据、TLS 私钥、Redis ACL 和正式账本均不得纳入 Git。

```powershell
# PowerShell 7
pwsh -NoProfile -File script/deploy/ChenStack.ps1 -Instance prod -Action check
pwsh -NoProfile -File script/deploy/ChenStack.ps1 -Instance prod -Action status
pwsh -NoProfile -File script/deploy/ChenStack.ps1 -Instance prod -Action up
pwsh -NoProfile -File script/deploy/ChenStack.ps1 -Instance prod -Action stop
pwsh -NoProfile -File script/deploy/ChenStack.ps1 -Instance prod -Action restart
```

`up` 启动已有正式容器，缺失时才从固定的本地镜像创建对应容器。`stop` 和 `restart` 只操作后端及两个前端。数据服务保持运行。Compose 不从仓库外加载配置，不拉取镜像，不构建镜像，不创建正式网络或数据卷。操作前核对项目内挂载、外部命名卷和 `D:\ChenStack-Backups`。当前 Redis 和 Qdrant 容器仍读取仓库内的 `.runtime/dev/redis` 和 `script/dev/rag-tls`，清理时须保留这些目录。下次重建对应容器时使用 `.runtime/prod` 内的配置副本。

当前知识库模式下，验证本地候选制品后，可以仅替换后端。两项参数均填完整的本地镜像 ID（`sha256:…`）。

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance prod -Action replace-backend -BackendImage $candidate -ExpectedBackendImage $current
```

脚本校验现有正式配置和后端身份，更新后端镜像引用，等待 readiness 并核对运行镜像。失败时恢复旧引用与镜像。保留旧镜像，在验收失败需要回退时交换两个 ID 重跑。使用 `RECOVER` 清单的旧发布模式继续使用受控发布流程。

知识库启用时，AI 配置保存在 `.runtime/prod/ai/settings.yml`。旧文件缺少 `reply-style` 时使用简洁专业，首次保存配置时写入该字段。部署前核对并备份 Compose 和该 YAML。回退到仅识别旧字段的后端时，先停止后端，再在保留当前模式、模型参数、检索条数、额度及旧提示词的前提下，从 YAML 移除 `reply-style`，或恢复已经核对过的旧版 YAML。最后用原 Compose 和镜像启动，确认 5000 readiness 为 `UP`。

私有配置中的后端关键字段如下，镜像占位符需替换为实测的完整镜像 ID。

```yaml
services:
  backend:
    image: sha256:<本机正式镜像ID>
    container_name: chen-stack-backend
    pull_policy: never
    restart: unless-stopped
    environment:
      CHEN_RAG_RELEASE_MANIFEST_PATH: /release/manifest.json
      CHEN_RAG_RELEASE_OPERATION_PATH: /release/operation-cfgfix.json
      CHEN_RAG_RELEASE_ARTIFACT_PATH: /app/app.jar
      AI_USAGE_LEDGER_PATH: /ai-usage/events.jsonl
    volumes:
      - type: bind
        source: ./rag/control
        target: /release
        read_only: true
        bind: {create_host_path: false}
      - type: bind
        source: ./rag/tls/qdrant-truststore.p12
        target: /opt/java/openjdk/lib/security/cacerts
        read_only: true
        bind: {create_host_path: false}
      - type: bind
        source: ./ai-usage
        target: /ai-usage
        bind: {create_host_path: false}
```

切换后端挂载前，先导出容器内账本并验证完整性。确认现行清单、运行 JAR 和数据库 RECOVER 回执一致后，复制发布状态并逐文件校验 SHA256。旧 `operation.json` 随历史发布材料保留。正式运行状态与备份位置见 [AGENTS.md](../../AGENTS.md)。恢复备份时使用同版本服务，MySQL 使用 SQL 转储，Qdrant 使用原生集合快照，MinIO 使用数据归档恢复到兼容卷。恢复前另行核验目标卷及备份哈希。

### 历史性能验收摘要

性能优化 R2 的前端候选通过 262 项单测、13 个文件的 lint 检查和干净构建。浏览器回归使用隔离夹具，覆盖封面与首页生命周期。该轮保留了原运行容器和数据卷，正式用户端候选尚未部署。这份摘要保留构建与验收范围，供后续定位正式用户端制品来源。

## 日常运维

| Action | 条件和实际行为 |
|---|---|
| `up` | 启动已有容器，等待基础设施与应用就绪，并校验宿主机连通性（端口发布 + 后端/前端 HTTP）。不会创建缺失容器，也不会应用新的 env。 |
| `stop` | 停止该实例正在运行的容器，保留卷、容器和私有配置。 |
| `restart` | 重启后端和两个前端，使用容器原有镜像、挂载和环境；结束后同样校验宿主机连通性。 |
| `status`（默认） | 显示容器、镜像标识、后端健康、env 中的部署默认知识模式，并附一份宿主机连通性摘要。实际知识模式以管理端显示的在线配置为准。 |
| `doctor` | 只读诊断：Docker Engine、实例容器与历史容器、端口发布、Windows 监听与端口排除、容器网络、内外健康、前端访问与请求目标、后端日志。输出固定摘要并复制到剪贴板。 |
| `repair-ports` | 端口发布异常的安全恢复。默认只诊断并打印命令；`-Apply` 才执行一次受控的 `docker restart`。条件、上限与受控实例边界见下文。 |
| `upgrade` | 要求普通实例及可用的 MySQL。先保存当前应用镜像到 `:previous`，确认现有库符合当前基线，再构建并按 Compose 配置更新应用容器。基础服务及其端口不更新。 |
| `rollback` | 要求三个 `:previous` 镜像标签，按当前 Compose/env 恢复应用镜像。不会恢复旧配置或回滚数据库迁移。 |
| `migrate` | 旧版恢复入口。检测到当前知识库表时拒绝执行。 |

例如：

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action stop
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action up
```

普通实例的镜像升级前先备份数据和私有配置，确认新版数据库兼容性与回退方案。部署脚本会拒绝对带受控发布标记、挂载 `/release` 或启用 RAG 恢复的后端执行 `upgrade` 或 `rollback`。这类实例使用[受控发布手册](../release/README.md)，迁移也需纳入实际发布计划。

`status` 会探测运行后端的 HTTP 健康端点，并附上宿主机连通性摘要。只想核对容器状态时，可直接按实例查询 Docker。

```powershell
docker ps -a --filter 'label=com.docker.compose.project=dev'
```

## 部署诊断（doctor）

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor
```

`doctor` 全程只读，可在正式环境执行。它按顺序检查：

1. Docker Engine 是否可用（`docker version`，不使用需要写权限的命令）。
2. 实例 Compose 项目下的容器；同项目里已停止的历史容器单独列出，不当作正式服务。
3. 基础设施与 health 状态（MySQL / Redis / MinIO / RabbitMQ，存在时含 Qdrant）。
4. 后端容器状态与容器内 `/actuator/health`。
5. 端口发布：`HostConfig.PortBindings`（配置意图）与 `NetworkSettings.Ports`（实际绑定）分开判断。
6. Windows 端口的监听进程、占用情况与 TCP 排除范围。
7. 宿主机 `http://127.0.0.1:<BACKEND_PORT>/actuator/health` 与用户端、管理端首页。
8. 前端产物内嵌的 API 地址是否与当前后端端口一致。
9. 容器网络共用情况与后端能否解析 `mysql`。
10. 失败时抓取后端最近日志中的关键错误行（口令、Token、Key 统一脱敏）。

所有 `docker inspect` 都通过 Go Template 只取需要的字段（`{{json .HostConfig.PortBindings}}`、`{{json .NetworkSettings.Ports}}` 等），不对整份 inspect JSON 做转换。

结束时输出固定摘要并复制到剪贴板（`Set-Clipboard` 后立即回读校验；剪贴板不可用时只报告状态，不影响结论）：

```text
CHEN_STACK_DOCTOR=PASS/FAIL
BACKEND_INTERNAL_HEALTH=UP/DOWN
BACKEND_HOST_ACCESS=PASS/FAIL
PORT_5000_PUBLISHED=YES/NO
FRONTEND_USER=PASS/FAIL
FRONTEND_ADMIN=PASS/FAIL
FRONTEND_REQUEST_TARGET=CONSISTENT/MISMATCH/UNKNOWN
ROOT_CAUSE=...
RECOMMENDED_ACTION=...
INSTANCE=...
BACKEND_PORT=...
RELEASE_MANAGED=YES/NO
```

隔离实例的后端端口不是 5000 时，`PORT_5000_PUBLISHED=N/A`，并额外输出 `PORT_<实际端口>_PUBLISHED=YES/NO`。诊断失败时退出码为 1；`-NoClipboard` 可关闭自动复制。

## 端口发布安全恢复（repair-ports）

```powershell
# 只诊断，不执行任何写操作
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports

# 人工确认后执行
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports -Apply
```

只有同时满足以下条件才允许恢复，否则返回 `REFUSED` 并说明原因：

- 后端容器正在运行，容器内 `/actuator/health` 为 `UP`；
- `HostConfig` 中存在 `127.0.0.1:<BACKEND_PORT> -> 5000/tcp`；
- `NetworkSettings.Ports` 中没有实际发布；
- 宿主机 HTTP 健康检查不可达；
- 该端口没有被其他进程占用（若被占用则先处理占用者）；
- 实例未受控，或已显式追加 `-AllowReleaseRestart`。

执行时只做 `docker restart --timeout 30 <后端容器>`（`--time` 是旧别名，新版 Docker 会提示已弃用），随后在限定时间内重新核对：容器仍在运行、镜像身份未变、挂载未变、实际端口发布存在、内部与宿主机健康均为 `UP`。默认最多 2 次（`-MaxAttempts 1..3`）。镜像身份变化会立即停止；失败时保留现场并输出下一步建议。

恢复过程**不会**执行 `docker compose down -v`、`docker system prune`、数据库或卷操作，不会执行普通 `upgrade`/`rollback`，不会重建容器或更换镜像。受控发布实例被要求加 `-AllowReleaseRestart`，就是为避免把“重启同一个容器”扩大成“重建运行制品”。

摘要字段：`CHEN_STACK_REPAIR=SUCCESS/DRY_RUN/NOT_NEEDED/REFUSED/FAILED`、`REPAIR_ATTEMPTS`、`IMAGE_IDENTITY_UNCHANGED`、`CONTAINER_STILL_RUNNING`、`PORT_5000_PUBLISHED`、`BACKEND_HOST_ACCESS`、`NEXT_STEP`。

## 常见故障处理

| `ROOT_CAUSE` | 现象 | 处理 |
|---|---|---|
| `DOCKER_ENGINE_UNAVAILABLE` | 所有 docker 命令失败 | 启动 Docker Desktop，等待 Engine 就绪后重试；脚本不会自行拉起 Desktop。 |
| `CONTAINERS_NOT_CREATED` | `docker ps` 里没有该实例 | 确认是全新环境后再 `init`；已有数据环境不要用 `init`。 |
| `BACKEND_NOT_RUNNING` / `INFRASTRUCTURE_NOT_RUNNING` | 容器退出或未启动 | `up`；仍失败看 `docker ps -a` 与对应容器日志。 |
| `BACKEND_INTERNAL_HEALTH_DOWN` | 容器在跑但容器内健康不是 `UP` | 看 `docker logs --tail 200 chen-stack-backend`、MySQL/Redis 状态与启动异常；重启容器不解决这类问题。 |
| `DOCKER_PORT_PUBLISH_MISSING` | 容器健康、内部健康 `UP`，宿主机端口不通 | 用 `repair-ports`（见上）；不要反复 `compose up -d` 或删除容器。 |
| `WINDOWS_PORT_CONFLICT` | 端口被其他进程占用 | `Get-NetTCPConnection -State Listen -LocalPort 5000` 找到占用者，释放后再诊断。 |
| `PORT_PUBLISH_UNVERIFIED` | 绑定信息缺失但宿主机能访问 | 记录现场后再诊断一次，不要自动重启。 |
| `PORT_CONFIG_MISMATCH` | 容器端口与当前 env 不一致 | 普通实例用 `upgrade -SkipBuild` 重建应用容器；受控实例按发布手册。 |
| `BACKEND_HOST_HTTP_FAILED` | 端口已发布但宿主机请求失败 | 按摘要提示重启后端并复核；仍失败时检查 Windows 排除端口范围与网络栈。 |
| `FRONTEND_NGINX_UNREACHABLE` | 用户端或管理端打不开 | 看前端容器日志，再 `restart` 应用容器。 |
| `FRONTEND_REQUEST_TARGET_MISMATCH` | 页面能开但 API 全失败 | 重新构建前端（普通实例 `upgrade`），使内嵌 API 端口与发布端口一致。 |

## 本地验收测试

```powershell
pwsh -NoProfile -File ./script/deploy/tests/Run-DeployTests.ps1
```

三个套件依次执行，全部使用测试替身与临时实例，不会接触正式容器：

- `Test-DeployStaticSafety.ps1`：语法、inspect 用法、只读边界、凭据与摘要字段，并检查正式源码、构建、部署和迁移输入对 `.work`、阶段目录及辰栈绝对源码路径的引用。
- `ChenStackDoctor.Tests.ps1`：故障注入单元测试（端口未发布、端口占用、内部健康 DOWN、Engine 未启动、前端不可达、请求目标不一致、历史容器、恢复上限、受控边界、幂等、脱敏）。
- `ChenStackDoctor.Cli.Tests.ps1`：用真实脚本进程 + Docker 替身 + loopback 测试服务跑的端到端用例，其中包含“`up` 在端口未发布时不得报告成功”。

## 私有配置与应用变更

默认读取 `.runtime/<实例名>/.env`。只有该文件不存在时，才兼容旧环境的 `script/dev/.env`（默认实例）或 `script/dev/.instances/<实例名>/.env`（旧隔离实例）。新安装由脚本生成 env，无需手工复制模板。Docker Compose 的同名进程环境变量可覆盖 env 文件，启动前清除与目标实例冲突的临时变量。

聊天 Key、模型和服务地址在实例 env 配置。对已经运行且已完成迁移的**普通实例**，只修改这些应用配置时可复用本地应用镜像。

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action upgrade -SkipBuild
```

该命令检查当前数据库结构，并按配置更新应用容器。进程重启不会重新注入 env。Redis 用户、ACL、健康检查密码文件和后端密码必须一致。修改 env 中的基础服务端口或密码不能单独完成既有实例的配置变更，需按该服务的持久化与轮换流程操作。

本地持久目录为 `.runtime/<实例名>/ai-usage`。后端默认启用 `NORMAL_AI_USAGE` 并创建 `events.jsonl`，应用重建后继续使用原文件。MySQL、Redis、MinIO、RabbitMQ 和 Qdrant 使用 Docker 命名卷。

## 隔离实例

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init -Instance chenstack-test -PortBase 29100
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action status -Instance chenstack-test
```

实例名以小写字母开头，后接小写字母、数字或连字符，最长 48 个字符。非默认实例的首次 `init` 需要 `-PortBase`，可选范围为 `1024..65525`。脚本检查从基准端口起连续 11 个 TCP 端口。末两个端口用于 Qdrant。后续操作只需继续指定相同实例名。

| 相对基准偏移 | 服务 | 示例宿主端口 |
|---|---|---|
| +0 / +1 | MySQL / Redis | 29100 / 29101 |
| +2 / +3 | MinIO API / 控制台 | 29102 / 29103 |
| +4 / +5 | RabbitMQ AMQP / 管理台 | 29104 / 29105 |
| +6 / +7 / +8 | 后端 / 管理端 / 用户端 | 29106 / 29107 / 29108 |
| +9 / +10 | Qdrant HTTP / gRPC | 29109 / 29110 |

脚本生成 `.runtime/<实例名>/override.yml`，隔离项目、容器、镜像、卷和网络。同一工作树中的初始化和升级应顺序执行，因为宿主机构建目录共用。初始化为两个前端打包隔离后端的 API 地址，并在后端配置对应的两个浏览器来源。用 `http://127.0.0.1:<端口>` 打开前端。已有隔离实例的 override 不会随升级改写，如果缺少来源配置，应按实际端口在原 override 中合并 `backend.environment.SPRING_APPLICATION_JSON` 的 `chen-stack.allow-origins`，保留其他设置。

## 可选 Qdrant 与知识准备

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init -Instance chenstack-rag -PortBase 29200
```

新实例的后端通过 `qdrant:6334` 使用 TLS 和独立 API Key。安装时生成的证书、私钥和 Java 信任库保存在实例 `rag/tls` 目录。备份私有运行目录时保留这些文件。添加知识后系统按需创建文档向量集合。新实例的 G5/G6 知识功能从 `知识文档` 入口使用，自动处理需要配置真实的聊天与 Embedding 服务。

## 数据库迁移

当前首次安装只导入 [`sql/bootstrap/chen_stack_baseline.sql`](../../sql/bootstrap/chen_stack_baseline.sql)。种子包含管理员、内容管理员、普通用户角色及当前菜单、权限和角色关系。安装脚本核对用户表为空后，按角色编码创建两个本地演示账号。SQL 不包含已有用户、密码、初始知识和业务记录。当前知识处理无需预导入公共知识，第一次上传会从空库开始。

`sql/` 中的阶段迁移仅供对应历史版本恢复参考。当前首次安装和现有正式实例启停均无需运行历史脚本。`-Action migrate` 只保留为旧版恢复入口，遇到当前的知识库结构会拒绝执行。生产迁移应根据实际结构、备份和停写计划操作。
