# 辰栈一键部署（`script/deploy`）

本目录提供 **Windows 11 + PowerShell 7** 的一键部署与运维脚本，Linux/macOS 见文末。
脚本把四种场景拆开，避免「一条命令打天下」误伤已有数据。

## 1. 前置条件

| 依赖 | 版本 | 说明 |
|---|---|---|
| Docker Desktop | 4.x（Compose v2） | 必须已启动；Windows 建议开启 WSL2 后端 |
| PowerShell | 7.0+ | 不要使用 Windows PowerShell 5.1 |
| JDK | 21 | 仅首次构建后端 JAR 需要；也要有 `mvn`（Maven 3.9+） |
| Node.js | 20+ | 仅首次构建两个前端 `dist/` 需要 |
| 网络 | 可访问 Docker Hub / Maven 中央仓库 / npm | 首次构建与拉取基础镜像需要 |

> 如果仓库里已经有 `chen-stack-backend/target/*.jar` 和两个前端的 `dist/`，
> 可以不装 JDK/Maven/Node，直接加 `-SkipBuild` 使用已有产物。

## 2. 四种场景

### A. 首次全新部署

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action init
```

脚本会依次完成：

1. 从 `script/dev/.env.example` 生成 `script/dev/.env`（**已存在则不覆盖**）；
2. 为空密码生成随机强口令（MySQL / Redis / MinIO / RabbitMQ / JWT / Qdrant）；
3. 生成 `script/dev/redis-secure/{redis.conf,users.acl,redis-app.secret}`（LF 换行，ACL 与 `.env` 口令一致）；
4. 缺少产物时构建后端 JAR 与两个前端 `dist/`，然后构建三个应用镜像；
5. `docker compose up -d` 启动 MySQL / Redis / MinIO / RabbitMQ，等待健康；
6. **按顺序执行幂等 SQL 迁移**（见第 4 节），已应用的跳过；
7. 启动 backend / admin / user，等待 `/actuator/health` 为 `UP` 与两个前端可访问；
8. 打印访问入口。

首次登录账号来自 `sql/chen_stack.sql` 的演示数据，**登录后请立即修改密码**。

### B. 已有数据环境升级

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action upgrade
```

1. 把当前三个应用镜像打上 `:previous` 标签（供回滚）；
2. 重新构建产物与镜像；
3. 再次执行幂等迁移；
4. 只重建三个应用容器，基础服务与数据卷不动；
5. 等待健康检查。

> **受控发布实例会被拒绝升级。** 如果检测到容器带 `chen.release.*` 标签、挂载了 `/release`，
> 或设置了 `CHEN_RAG_RELEASE_RECOVERY_ENABLED=true`，脚本会直接退出并提示改用
> `script/release` 的受控发布流程。原因：正式 RAG 读取权限由「发布清单 + 已完成回执」授予，
> 清单绑定运行 JAR 的 SHA256，用普通 Compose 重建会让运行制品与清单不一致，后端会 fail-closed 拒绝启动。

### C. 电脑重启后的正常启动

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action up
```

* 容器都带 `restart: unless-stopped`，Docker Desktop 随开机启动时会自动拉起；
  若没有自动拉起，执行本命令即可。
* `up` 只做 `docker start`，**不重建、不迁移、不重新构建**，因此不会改变运行制品身份。
* 常用替代：`-Action stop`（停止但保留容器与卷）、`-Action restart`（只重启三个应用容器）。

### D. 需要回滚

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action rollback
```

把三个应用镜像标签指回 `:previous` 并重建应用容器；基础服务与数据卷不变。
只能回滚到「上一次 upgrade 之前」的镜像；更早的历史需要从备份或 Git 重新构建。

### 其它

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action status    # 容器、健康、默认知识模式
pwsh -File script/deploy/ChenStack.ps1 -Action migrate   # 只跑幂等迁移
```

## 3. 可选：本地启用 RAG

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action init -WithRag
```

* 追加 `compose --profile rag`，启动内置 Qdrant（`chen-stack-qdrant`，gRPC 16334）；
* 把 `SPRING_PROFILES_ACTIVE` 改成 `dev,rag`、`CHEN_RAG_ENABLED=true`、`CHEN_RAG_QDRANT_HOST=qdrant`；
* **仍然需要**：在 `.env` 里填 `CHEN_RAG_EMBEDDING_API_KEY`，并完成一次受控知识发布。
  没有发布回执时，管理端会把 RAG 显示为「知识索引尚未就绪」，智能混合会自动只用 Wiki 一侧——这是**预期的 fail-closed**，不是 bug。

不想启用 RAG 时，最快的知识体验路径是 **Wiki**（纯数据库，无需 Embedding）：
管理端 →「AI知识管理 → 整理知识」→ 手动新建 → 审核 → 发布，小辰即可引用该知识回答。

## 4. 数据库迁移

| 顺序 | 文件 | 幂等标记 |
|---|---|---|
| 1 | `sql/g2_rag_catalog.sql` | 表 `rag_catalog_state` |
| 2 | `sql/g3_wiki_foundation.sql` | 表 `wiki_source` |
| 3 | `sql/g3_wiki_compiler.sql` | 表 `wiki_compile_budget` |
| 4 | `sql/g3_wiki_invalidation.sql` | 触发器 `wiki_article_changed` |
| 5 | `sql/g3_wiki_product.sql` | 表 `wiki_page` |
| 6 | `sql/g3_ai_knowledge_management.sql` | 菜单 `9300` |
| 7 | `sql/g3_ai_knowledge_configuration.sql` | 表 `ai_runtime_config` |

基础表结构由 `sql/chen_stack.sql` 在 MySQL 首次初始化时自动导入（挂载到 `/docker-entrypoint-initdb.d/`）。
`sql/*_rollback.sql` 只用于明确回滚，脚本不会自动执行。

## 5. 隔离实例（并行验证）

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action init -Instance chenstack-test -SkipBuild
```

`-Instance` 会生成 `script/dev/.instances/<实例名>/override.yml`：
容器名、数据卷名、网络名与三个应用镜像名都会加实例后缀，因此**不会影响默认实例**。
隔离实例的配置与 Redis 密钥都放在该目录下（已被 `.gitignore` 忽略）。

> 端口仍来自 `.env`：并行运行前请把该实例 `.env` 里的 `MYSQL_PORT`、`BACKEND_PORT` 等改成未占用端口。

## 6. 已验证范围（2026-11-04）

在隔离实例 `chenstack-verify`（独立端口 15000/18000/17000/13308/26379）上实测：

| 场景 | 结果 |
|---|---|
| 首次全新部署 `init`（含 7 个迁移、健康检查、真实管理员登录、AI 配置读取、真实 DeepSeek 对话、额度接口） | PASS |
| 停止后 `up` 恢复（数据卷持久化，改前的管理员密码与菜单迁移保持） | PASS |
| `upgrade`（镜像重建 + 容器重建 + 数据保留 + `:previous` 标签） | PASS |
| `rollback`（镜像指回 `:previous` + 健康检查） | PASS |
| 受控发布实例上执行 `upgrade` | 按预期拒绝，未触碰任何容器 |
| 生产实例上执行 `status` / `up` | 只读，容器未重建 |

未验证：Linux/macOS 原生命令、需要联网首次构建的全新机器、`-WithRag` 的完整知识发布链路
（需要额外的 Embedding 凭据与付费调用）。

## 7. Linux / macOS

脚本为 PowerShell 编写。Linux/macOS 上可用 `pwsh`（PowerShell 7 跨平台）执行同一命令；
也可直接用 Docker Compose 手工等价操作，但需要自行完成第 2 节的 A 步 1~6：

```bash
cp script/dev/.env.example script/dev/.env   # 然后手工填密码
# 生成 redis-secure/{redis.conf,users.acl,redis-app.secret}（必须 LF 换行）
docker compose -p dev --project-directory script/dev -f script/dev/docker-compose.yml up -d --no-build mysql redis minio rabbitmq
# 依次导入 sql/g2_rag_catalog.sql、g3_wiki_*.sql、g3_ai_knowledge_*.sql
docker compose -p dev --project-directory script/dev -f script/dev/docker-compose.yml up -d --no-build backend frontend-admin frontend-user
```

该手工路径未在本轮实测，标记为 **NOT_VERIFIED**。
