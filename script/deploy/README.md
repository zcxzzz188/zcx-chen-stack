# 辰栈本地部署与运维

以下命令在 **Windows 11 + PowerShell 7** 中从仓库根目录执行。启动 Docker Desktop，使用 Linux 容器和支持 `docker compose config --format json` 的 Compose 插件。首次正常构建需要 JDK 21、Maven 3.9+、Node.js 22.12+ 和 npm，以及镜像和依赖仓库的网络访问。Linux/macOS 有脚本分支，但此指南的部署步骤针对 Windows。

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

## 日常运维

| Action | 条件和实际行为 |
|---|---|
| `up` | 启动已有容器并检查基础设施和应用就绪，包括已有的 Qdrant。不会创建缺失容器，也不会应用新的 env。 |
| `stop` | 停止该实例正在运行的容器，保留卷、容器和私有配置。 |
| `restart` | 重启后端和两个前端，使用容器原有镜像、挂载和环境。 |
| `status`（默认） | 显示容器、镜像标识、后端健康和 env 中的部署默认知识模式。实际模式以管理端显示的数据库配置为准。 |
| `upgrade` | 要求普通实例及可用的 MySQL。先保存当前应用镜像到 `:previous`，再构建、检查迁移，并按 Compose 配置更新应用容器。基础服务及其端口不更新。 |
| `rollback` | 要求三个 `:previous` 镜像标签，按当前 Compose/env 恢复应用镜像。不会恢复旧配置或回滚数据库迁移。 |
| `migrate` | 只对已有、可用的 MySQL 执行缺失的增量迁移。不启动应用或构建镜像。 |

例如：

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action stop
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action up
```

普通实例的镜像升级前先备份数据和私有配置，确认新版数据库兼容性与回退方案。部署脚本会拒绝对带受控发布标记、挂载 `/release` 或启用 RAG 恢复的后端执行 `upgrade` 或 `rollback`。这类实例使用[受控发布手册](../release/README.md)，迁移也需纳入实际发布计划。

`status` 会探测运行后端的 HTTP 健康端点。只想核对容器状态时，可直接按实例查询 Docker。

```powershell
docker ps -a --filter 'label=com.docker.compose.project=dev'
```

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
