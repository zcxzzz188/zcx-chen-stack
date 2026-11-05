# 辰栈一键部署（`script/deploy`）

本目录提供 **Windows 11 + PowerShell 7** 的一键部署与运维脚本，Linux/macOS 见文末。
脚本把四种场景拆开，避免「一条命令打天下」误伤已有数据。

## 1. 前置条件

| 依赖 | 版本 | 说明 |
|---|---|---|
| Docker Desktop | 4.x（Compose v2） | 必须已启动；Windows 建议开启 WSL2 后端 |
| PowerShell | 7.0+ | 不要使用 Windows PowerShell 5.1 |
| JDK | 21 | 仅首次构建后端 JAR 需要；也要有 `mvn`（Maven 3.9+） |
| Node.js | 22 与 npm | 仅首次构建两个前端 `dist/` 需要 |
| 网络 | 可访问 Docker Hub / Maven 中央仓库 / npm | 首次构建与拉取基础镜像需要 |

> 如果仓库里已经有 `chen-stack-backend/target/*.jar` 和两个前端的 `dist/`，
> 可以不装 JDK/Maven/Node，直接加 `-SkipBuild` 使用已有产物。

## 2. 四种场景

### A. 首次全新部署

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action init
```

脚本会依次完成：

1. 从 `script/dev/.env.example` 生成 `.runtime/dev/.env`（已有值保留）；
2. 为空密码生成随机强口令（MySQL / Redis / MinIO / RabbitMQ / JWT / Qdrant）；
3. 在 `.runtime/dev/` 生成 Redis 配置、连续计量账本和 RAG 控制目录。Redis ACL 使用 LF 换行并匹配 env 口令；
4. 缺少产物时构建后端 JAR 与两个前端 `dist/`，然后构建三个应用镜像；
5. `docker compose up -d` 启动 MySQL / Redis / MinIO / RabbitMQ，等待健康；
6. **按顺序执行幂等 SQL 迁移**（见第 4 节），已应用的跳过；
7. 为新库的初始管理员生成随机密码，再启动 backend / admin / user 并等待健康检查；
8. 打印访问入口。

首次登录信息写到 `.runtime/<实例名>/secrets/initial-admin.env`。登录后请修改密码并删除该文件。

`init` 要求项目容器、网络和数据卷尚未创建。已有实例使用 `up` 或对应升级流程。
可选的聊天 API Key 填入生成的 env，已有数据部署的旧 env 路径仍可读取。

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

* 追加 `compose --profile rag`，启动内置 Qdrant（`chen-stack-qdrant`，宿主 gRPC 默认 16334）。
* 在实例 env 配置独立的 `CHEN_RAG_EMBEDDING_API_KEY`，启用 `dev,rag` 和 `CHEN_RAG_ENABLED=true` 后，按 [受控发布流程](../release/README.md) 准备知识及回执。未配置 Embedding 凭据时，本地 Qdrant 可就绪，RAG 读取保持关闭。

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
pwsh -File script/deploy/ChenStack.ps1 -Action init -Instance chenstack-test -PortBase 29100
```

`-Instance` 会生成 `.runtime/<实例名>/override.yml`，隔离容器、镜像、数据卷和网络。
`-PortBase` 选择独立的连续 11 个 loopback 端口，使用相同 `-Instance` 管理该实例。

## 6. 历史验收记录（2026-11-04）

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

Linux/macOS 可使用 PowerShell 7 尝试同一入口。本轮端到端验收环境为 Windows 11 和 Docker Desktop。
