# 辰栈本地部署与运维

以下命令在 **Windows 11 + PowerShell 7** 中从仓库根目录执行。启动 Docker Desktop，使用 Linux 容器和支持 `docker compose config --format json` 的 Compose 插件。首次正常构建需要 JDK 21、Maven 3.9+、Node.js 22.12+ 和 npm，以及镜像和依赖仓库的网络访问。Linux/macOS 有脚本分支，但此指南的部署步骤针对 Windows。

`up`、`init`、`restart`、`upgrade`、`rollback` 结束前都会校验宿主机连通性：容器运行、实际端口发布存在、后端与两个前端可从 `127.0.0.1` 访问。任一项不满足就打印诊断并非 0 退出，不再仅凭容器 `Running` 或容器内健康 `UP` 报告成功。日常检查和故障处理使用同一条命令入口的 `doctor` 与 `repair-ports`，见下文。

## 首次初始化

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init
```

`init` 要求目标项目的容器、网络和数据卷均未创建。首次启动时脚本按以下顺序执行。

1. 生成 `.runtime/dev/.env`，为空的本地服务密码和密钥生成随机值。创建 Redis 配置、账本目录及 RAG 私有目录。
2. 构建后端 JAR、两个前端的静态产物和应用镜像。首次初始化会复用已有的目标路径 JAR，因此切换过源码时请先构建匹配的 JAR。
3. 启动 MySQL、Redis、MinIO、RabbitMQ 并等待就绪。MySQL 在新卷上导入基础 SQL，脚本再执行[增量迁移](#数据库迁移)。
4. 写入初始管理员密码并启动后端和两个前端。浏览器使用脚本打印的地址。

在本机打开 `.runtime/<实例名>/secrets/initial-admin.env` 获取 `chenstack` 的随机密码。登录管理端后修改密码，再删除该文件。普通账号的邮件注册和找回密码需要另行配置 SMTP。初始管理员的密码不会随常规启停或升级重置。

`-SkipBuild` 同时跳过宿主产物和 Docker 镜像构建。仅当本机已有目标实例所需的三个匹配镜像时使用。只有 JAR 和 `dist/` 仍需构建镜像，首次克隆应使用正常 `init`。

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

## 日常运维

| Action | 条件和实际行为 |
|---|---|
| `up` | 启动已有容器，等待基础设施与应用就绪，并校验宿主机连通性（端口发布 + 后端/前端 HTTP）。不会创建缺失容器，也不会应用新的 env。 |
| `stop` | 停止该实例正在运行的容器，保留卷、容器和私有配置。 |
| `restart` | 重启后端和两个前端，使用容器原有镜像、挂载和环境；结束后同样校验宿主机连通性。 |
| `status`（默认） | 显示容器、镜像标识、后端健康、env 中的部署默认知识模式，并附一份宿主机连通性摘要。实际知识模式以管理端显示的数据库配置为准。 |
| `doctor` | 只读诊断：Docker Engine、实例容器与历史容器、端口发布、Windows 监听与端口排除、容器网络、内外健康、前端访问与请求目标、后端日志。输出固定摘要并复制到剪贴板。 |
| `repair-ports` | 端口发布异常的安全恢复。默认只诊断并打印命令；`-Apply` 才执行一次受控的 `docker restart`。条件、上限与受控实例边界见下文。 |
| `upgrade` | 要求普通实例及可用的 MySQL。先保存当前应用镜像到 `:previous`，再构建、检查迁移，并按 Compose 配置更新应用容器。基础服务及其端口不更新。 |
| `rollback` | 要求三个 `:previous` 镜像标签，按当前 Compose/env 恢复应用镜像。不会恢复旧配置或回滚数据库迁移。 |
| `migrate` | 只对已有、可用的 MySQL 执行缺失的增量迁移。不启动应用或构建镜像。 |

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

- `Test-DeployStaticSafety.ps1`：语法、inspect 用法、只读边界、凭据字段与摘要字段的静态检查。
- `ChenStackDoctor.Tests.ps1`：故障注入单元测试（端口未发布、端口占用、内部健康 DOWN、Engine 未启动、前端不可达、请求目标不一致、历史容器、恢复上限、受控边界、幂等、脱敏）。
- `ChenStackDoctor.Cli.Tests.ps1`：用真实脚本进程 + Docker 替身 + loopback 测试服务跑的端到端用例，其中包含“`up` 在端口未发布时不得报告成功”。

## 私有配置与应用变更

默认读取 `.runtime/<实例名>/.env`。只有该文件不存在时，才兼容旧环境的 `script/dev/.env`（默认实例）或 `script/dev/.instances/<实例名>/.env`（旧隔离实例）。新安装由脚本生成 env，无需手工复制模板。Docker Compose 的同名进程环境变量可覆盖 env 文件，启动前清除与目标实例冲突的临时变量。

聊天 Key、模型和服务地址在实例 env 配置。对已经运行且已完成迁移的**普通实例**，只修改这些应用配置时可复用本地应用镜像。

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action upgrade -SkipBuild
```

该命令仍检查待应用迁移，并按配置更新应用容器。进程重启不会重新注入 env。Redis 用户、ACL、健康检查密码文件和后端密码必须一致。修改 env 中的基础服务端口或密码不能单独完成既有实例的配置变更，需按该服务的持久化与轮换流程操作。

本地持久目录为 `.runtime/<实例名>/ai-usage`。后端默认启用 `NORMAL_AI_USAGE` 并创建 `events.jsonl`，应用重建后继续使用原文件。MySQL、Redis、MinIO、RabbitMQ 和可选 Qdrant 使用 Docker 命名卷。

## 隔离实例

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init -Instance chenstack-test -PortBase 29100
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action status -Instance chenstack-test
```

实例名以小写字母开头，后接小写字母、数字或连字符，最长 48 个字符。非默认实例的首次 `init` 需要 `-PortBase`，可选范围为 `1024..65525`。脚本检查从基准端口起连续 11 个 TCP 端口，基础安装绑定前 9 个，启用 Qdrant 时再绑定最后两个。后续操作只需继续指定相同实例名。

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
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init -Instance chenstack-rag -PortBase 29200 -WithRag
```

`-WithRag` 用于全新实例，启用本地 `rag` Compose profile。脚本将后端 Qdrant 目标设为容器网络中的 `qdrant:6334`（本地无 TLS），并给空的 Qdrant Key 生成值。默认空的 Embedding Key 会让后端保持 `dev` 且 RAG 关闭。若初始化前已在所选 env 配置 Embedding Key，脚本会设为 `dev,rag` 并打开 RAG 开关。创建 Qdrant 不会导入任何知识或授权读取。既有实例不能靠重跑 `init` 或给 `up` 加 `-WithRag` 补建容器。

发布内置知识须单独准备受控环境、已审校主题、独立凭据、清单与完成回执。默认的本地 Qdrant 参数不能直接通过受控预检。第一次体验知识问答可以按[主文档的 Wiki 流程](../../README.md#54-准备-wikirag-和混合问答)发布手动页面。

## 数据库迁移

[基础 SQL](../../sql/chen_stack.sql)只在 MySQL 初始化空数据卷时导入。脚本按下表顺序检查标记，跳过已有项。

| 顺序 | 文件（`sql/` 下） | 标记 |
|---|---|---|
| 1 | `g2_rag_catalog.sql` | 表 `rag_catalog_state` |
| 2 | `g3_wiki_foundation.sql` | 表 `wiki_source` |
| 3 | `g3_wiki_compiler.sql` | 表 `wiki_compile_budget` |
| 4 | `g3_wiki_invalidation.sql` | 触发器 `wiki_article_changed` |
| 5 | `g3_wiki_product.sql` | 表 `wiki_page` |
| 6 | `g3_ai_knowledge_management.sql` | 菜单 `9300` |
| 7 | `g3_ai_knowledge_configuration.sql` | 表 `ai_runtime_config` |

出现部分迁移失败时先核对现场和标记。部署脚本不会删除卷，也不会自动执行 `sql/*_rollback.sql`。

