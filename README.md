# 辰栈 Chen Stack

> 一个基于 **Spring Boot 4 + Vue 3** 的 AI 辅助技术博客社区，内置站内智能助手「小辰」：
> 支持 **RAG 知识检索**、**LLM Wiki 知识问答** 与 **智能混合（Hybrid）** 三种知识模式，
> 普通用户只看到一个聊天入口，管理员在后台可视化配置知识策略与每日额度。

<p align="center">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-007396">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-4.1.2-6DB33F">
  <img alt="Spring AI" src="https://img.shields.io/badge/Spring%20AI-2.0.2-6DB33F">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3.5-42B883">
  <img alt="Docker" src="https://img.shields.io/badge/Docker-Compose-2496ED">
</p>

---

## 1. 项目简介

辰栈面向技术写作者，提供文章创作、专栏、评论、私信、审核、搜索等完整社区能力，
并在其中嵌入一个**统一的站内智能助手**：

- 用户说「你好」「谢谢」时，小辰自然回应，**不会**去检索知识库；
- 用户问平台规则、操作步骤时，小辰先取证据再回答，证据不足时提示未查到；
- 用户问「我有哪些草稿」这类实时个人数据时，小辰通过**服务端只读工具**查询本人数据，
  不经过向量库，也看不到别人的文章；
- 管理员可以切换小辰的知识模式、调整每位用户的每日额度，并查看真实生效状态与修改审计。

> RAG 与 Wiki 都是**检索增强**：它们为模型提供可信资料，不训练、不微调基础模型。

## 2. 主要功能

### 用户端（`chen-stack-user`，默认 7000）

| 模块 | 能力 |
|---|---|
| 首页 / 文章 | 文章列表、详情、分类专栏、标签、点赞、收藏、浏览历史 |
| 创作中心 | 写文章、存草稿、文章管理、专栏管理、评论管理 |
| 社区互动 | 评论与回复、关注与粉丝、站内私信（图片消息）、消息中心 |
| 账号 | 注册、登录、找回密码、个人资料、邮箱与安全设置 |
| 站内搜索 | 关键词搜索、热门搜索、内容发现 |
| **小辰助手** | 悬浮入口，SSE 流式回答；交互卡片与页面按钮由服务端可信协议下发 |

### 管理端（`chen-stack-admin`，默认 8000）

| 菜单 | 能力 |
|---|---|
| 控制台 | 用户/文章/访问统计、趋势图、快捷入口 |
| 文章 / 专栏 / 评论 / 图片审核 | 审核队列、批量操作、驳回原因 |
| 标签、用户、角色、菜单、权限 | RBAC 与动态菜单路由 |
| 通知、日志 | 系统通知、登录日志、操作日志、访客日志 |
| **AI 知识管理** | 原始知识（RAG）、整理知识（Wiki）、知识来源、智能整理、检索验证 |
| **AI 配置** | 三档知识模式、每日额度、最近修改记录 |

### 智能助手能力

- **三档知识模式**（管理员配置，普通用户无感）：
  - `RAG 知识检索`：只使用已审校的原始知识目录；
  - `Wiki 知识问答`：从当前已发布且来源有效的整理知识中检索；
  - `智能混合`：按问题在两者之间按需选择，简单交流不检索。
- **按需检索**：混合模式先用一次轻量范围分类判断是否需要知识；需要时先读最相关的一侧，
  证据不足才补另一侧，不做「每轮都双查」。
- **来源与版本可追溯**：后端记录实际知识模式和来源，用户可以查看回答引用的资料。
- **实时个人数据走工具**：只有「查询本人可编辑文章」这一个只读业务工具，参数不含用户 ID/文章 ID，
  归属与 RBAC 全部在服务端判定。
- **额度与限流**：每位用户每日默认 100 次（北京时间自然日恢复），三档模式共用；
  智能混合内部的多次模型调用单独计入 Provider 用量，**不重复扣**用户次数。
- **安全边界**：后台入口、他人数据、审核操作等请求在进入回答模型前即被拒绝；
  导航按钮只来自白名单；模型不能执行 SQL、不能构造任意 URL、不能根据聊天中的自称身份提权。

## 3. 技术栈

### 后端

| 类别 | 选型 |
|---|---|
| 运行时 | Java 21 |
| 框架 | Spring Boot 4.1.2（WebMVC、Security、Validation、Actuator、AMQP、Mail、Thymeleaf） |
| AI | Spring AI 2.0.2（OpenAI 兼容协议，默认对接 DeepSeek）、`spring-ai-retry`、OpenAI Java SDK |
| 持久层 | MyBatis-Plus 3.5.17、MySQL 8.0 |
| 缓存 / 队列 | Redis 7（含 ACL）、RabbitMQ 3（管理插件） |
| 对象存储 | MinIO（RELEASE.2025-04-08） |
| 向量库 | Qdrant v1.13.6（仅 RAG 需要；gRPC，可开 TLS） |
| 其它 | Lombok、Hutool、Fastjson、easy-captcha、MinIO SDK 8.3.6、阿里云内容安全 |

### 前端

| 端 | 选型 |
|---|---|
| 两端通用 | Vue 3.5、Vite+ 0.1.15（构建核心 Vite 8.0.3）、Pinia 3、Element Plus 2、Axios、Vue Router 4 |
| 管理端 | Element Plus 组件体系 + 自研管理卡片/表格/分页组件、AntV G2 图表、xlsx 导出 |
| 用户端 | Element Plus、markdown-it、DOMPurify、SSE 流式渲染 |

### 部署

Docker Compose（MySQL / Redis / MinIO / RabbitMQ / 后端 / 管理端 / 用户端，可选 Qdrant），
基础镜像：`eclipse-temurin:21-alpine`、`nginx:alpine`、`mysql:8.0`、`redis:7-alpine`、
`minio/minio:RELEASE.2025-04-08T15-41-24Z`、`rabbitmq:3-management-alpine`、`qdrant/qdrant:v1.13.6`。

## 4. 项目结构

```
zcx-chen-stack/
├─ chen-stack-backend/                 # Spring Boot 后端
│  ├─ src/main/java/com/zcx/chenstack/
│  │  ├─ controller/  service/  mapper/  domain/  config/  security/
│  │  ├─ rag/                          # RAG 知识目录、检索、发布与运行配置
│  │  ├─ wiki/                         # LLM Wiki 来源、编译、发布与阅读
│  │  └─ websocket/  redis/  rabbitmq/
│  ├─ src/main/resources/knowledge/    # 待审校的 RAG 知识资源
│  ├─ src/test/java/                   # 后端回归测试
│  └─ Dockerfile
├─ chen-stack-frontend/
│  ├─ chen-stack-admin/                # 管理端（Vue 3）
│  │  ├─ src/views/ai/                 # 原始知识 / 检索验证 / AI 配置
│  │  ├─ src/views/wiki/               # 整理知识 / 知识来源 / 智能整理
│  │  └─ Dockerfile
│  └─ chen-stack-user/                 # 用户端（Vue 3）
│     ├─ src/components/CustomerService/  # 小辰聊天、卡片、SSE 协议
│     └─ Dockerfile
├─ script/
│  ├─ deploy/                          # 本地部署、启动、升级与回滚，doctor 诊断与端口恢复
│  │  └─ tests/                        # 隔离故障注入验收（假 Docker/HTTP，不接触正式实例）
│  ├─ dev/                             # docker-compose、.env.example、Redis 安全配置模板
│  ├─ release/                         # 正式受控发布（构建、清单、发布操作）
│  └─ prod/                            # 生产编排模板与备份脚本
├─ sql/                                # MySQL 初始表结构与增量迁移
├─ AGENTS.md                           # 协作规则
└─ README.md
```

## 5. 快速开始

以下命令在 **Windows 11 的 PowerShell 7** 执行。从 GitHub 全新克隆只需要源码，初始化会创建独立的本地数据和凭据。

### 5.1 准备环境

- **先启动 Docker Desktop**（使用 Linux 容器），等托盘图标显示 Engine 已运行后再执行任何部署命令。脚本会读取 Docker Engine 版本；Engine 未启动时 `up`、`status`、`doctor` 都会直接失败并提示。
- Git，以及支持 `docker compose config --format json` 的 Compose 插件。可用下面的命令确认环境：
  ```powershell
  docker version --format '{{.Server.Version}}'
  docker compose version --short
  ```
- 仅**首次初始化或升级构建**需要 JDK 21、Maven 3.9+、Node.js 22.12+ 和 npm。后端 JAR 和两个前端 `dist/` 先在宿主机生成，再打包进 Docker 镜像。日常 `up`、`stop`、`status`、`doctor` 不读取这些工具链。
- 预留默认端口：5000（后端）、7000（用户端）、8000（管理端）等。如果本机已有辰栈或端口冲突，直接选用下方的隔离实例，不要修改业务源码或切换正式端口。

### 5.2 克隆并初始化

```powershell
git clone https://github.com/zcxzzz188/zcx-chen-stack.git
if ($LASTEXITCODE -ne 0) { throw '克隆失败' }
Set-Location zcx-chen-stack
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init
```

`init` 从模板生成 `.runtime/dev/.env` 和随机凭据，配置 Redis ACL，构建应用，启动 MySQL、Redis、MinIO 和 RabbitMQ，再导入基础库、执行七项迁移并启动后端和两个前端。默认的 `dev` 项目要求容器、网络和数据卷均为新资源。全新克隆应保留默认构建步骤。

> **`init` 只用于全新实例。** 它要求目标项目的容器、网络和数据卷都不存在，任一已存在就会直接拒绝。已经在运行、已有数据、或由受控发布流程管理（正式 G3、R2 前端等）的实例，日常启动使用 `up`，不要用 `init`“修复”。初始化与恢复已有数据的区别见[部署手册](script/deploy/README.md#首次初始化与已有数据)。

如需并行部署，从仓库根目录改用下面的命令。此例用户端为 **29108**，管理端为 **29107**，后端为 **29106**，请使用脚本输出的 `127.0.0.1` 地址。后续命令继续传入 `-Instance chenstack-test`。完整端口映射及构建选项见[部署手册](script/deploy/README.md)。

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init -Instance chenstack-test -PortBase 29100
```

### 5.3 登录与日常启停

| 服务 | 默认宿主地址或端口 |
|---|---|
| 用户端 | http://127.0.0.1:7000 |
| 管理端 | http://127.0.0.1:8000 |
| 后端 API | http://127.0.0.1:5000 |
| MySQL / Redis | `127.0.0.1:3308` / `127.0.0.1:6379` |
| MinIO API / 控制台 | `127.0.0.1:9000` / http://127.0.0.1:9001 |
| RabbitMQ AMQP / 管理台 | `127.0.0.1:5672` / http://127.0.0.1:15672 |
| Qdrant（仅 `-WithRag`） | `127.0.0.1:16333` HTTP / `127.0.0.1:16334` gRPC |

管理端账号为 `chenstack`。在本机打开 `.runtime/<实例名>/secrets/initial-admin.env` 获取随机密码。首次登录后修改密码，再删除初始凭据文件。管理端和博客浏览可以先用，用户注册和找回密码需要配置邮件。

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action status
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action stop
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action up
```

`stop` 保留容器、配置和数据卷，`up` 启动已有容器并检查就绪。改过环境变量时，需要按[部署手册](script/deploy/README.md)重新创建应用容器。`restart` 仅重启三个应用容器，`upgrade` 和 `rollback` 只用于普通实例。

`up` 不只看 Docker 是否 `Running`。它会在容器内健康之后继续核对：实际端口是否发布、`127.0.0.1` 的 `/actuator/health` 是否 `UP`、用户端与管理端是否能打开页面。任何一项不满足都会打印诊断、以非 0 退出，**不会**报告“启动成功”。想单独做一次完整检查时使用 `doctor`：

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor
```

### 5.4 准备 Wiki、RAG 和混合问答

全新实例没有已发布知识。最快的体验路径是手动发布 Wiki 页面，编辑和审核无需模型或 Embedding。

1. 准备一篇自己有权使用的公开、已发布、审核通过的文章或专栏。全新站点可以先配置邮件并注册普通账号，发布一篇文章，再在管理端确认审核状态。
2. 进入 **AI知识管理 → 知识来源**，选择材料，点 **检查并授权**，核对当前版本并确认授权。
3. 在 **整理知识** 中新增知识，填写标题、摘要和正文，选择已授权来源，创建待审版本。打开版本后 **提交审核 → 核查来源与内容 → 审核通过 → 正式发布**。
4. 配置聊天 Key 后，在 **AI知识管理 → AI配置** 选择 **Wiki 知识问答**，用普通账号在用户端向小辰提问。默认关闭的公开 Wiki 阅读入口不影响小辰使用已发布页面。

`-WithRag` 用于在**全新实例**启动本地 Qdrant。它不会自动建立可检索的知识。RAG 首次发布需要独立 Embedding Key、匹配的服务地址和模型、Qdrant 连接与独立 Key，以及与实际制品匹配的发布清单和完成回执。内置的 [`knowledge/`](chen-stack-backend/src/main/resources/knowledge/) 是审校材料。正式发布见[受控发布手册](script/release/README.md)。

普通 Compose 的部署默认值是 `hybrid`。要体验两路混合检索，先让 RAG 和 Wiki 都有有效的已发布知识，再在 AI 配置中选择 **智能混合**。运行时会按问题选择知识路径。仅使用 Wiki 的实例可直接选择 Wiki 模式。

### 5.5 部署健康检查与安全恢复

Docker 显示容器 `Running`、容器内 `/actuator/health` 返回 `UP`，都不代表宿主机能访问服务。2026-11-10 的真实故障就是这样：容器与 Spring Boot 都正常，`HostConfig.PortBindings` 也配置了 `127.0.0.1:5000 -> 5000/tcp`，但 `NetworkSettings.Ports` 是空的，`127.0.0.1:5000` 无法访问，登录验证码加载失败，而 `up` 仍报告成功。现在脚本把这类问题当作失败处理。

只读诊断（不会启动、停止、重启或删除任何东西）：

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor
```

`doctor` 会检查 Docker Engine、实例容器与已停止的历史容器、实际端口发布（`HostConfig` 与 `NetworkSettings` 分开看）、Windows 监听进程与端口排除范围、容器网络互通、后端内部与宿主机健康、用户端与管理端访问、前端产物内嵌的 API 端口，以及后端最近日志中的关键错误。输出末尾给出固定摘要，并自动复制到剪贴板（回读校验）：

```text
CHEN_STACK_DOCTOR=PASS/FAIL
BACKEND_INTERNAL_HEALTH=UP/DOWN
BACKEND_HOST_ACCESS=PASS/FAIL
PORT_5000_PUBLISHED=YES/NO
FRONTEND_USER=PASS/FAIL
FRONTEND_ADMIN=PASS/FAIL
ROOT_CAUSE=...
RECOMMENDED_ACTION=...
```

诊断失败时 `doctor` 以非 0 退出，`ROOT_CAUSE` 会区分：容器未启动、Java 后端启动失败、Docker 端口发布失败、Windows 端口冲突、API HTTP 请求失败、前端 Nginx 不可访问、前端请求目标与发布端口不一致。诊断只读，不会修改正式环境。

端口发布异常的安全恢复：

```powershell
# 默认只诊断，打印将要执行的命令
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports

# 人工确认后执行：只对同一个后端容器执行 docker restart --timeout 30（旧别名 --time）
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports -Apply
```

`repair-ports` 只在“后端容器运行、容器内健康 `UP`、`HostConfig` 有本机端口绑定、`NetworkSettings` 没有实际发布、宿主机 HTTP 不可达、端口没有被其他进程占用”时才会重启后端，且最多重启 2 次（`-MaxAttempts 1..3`）。重启后重新核对镜像身份、挂载、容器状态、端口发布与宿主机 HTTP 健康；镜像身份变化或多次失败会立即停止并保留现场。它**不会**执行 `docker compose down -v`、`docker system prune`、数据库或卷操作，也不会重建容器、更换镜像或改写受控发布清单。

受控发布实例（带 `chen.release` 标签、挂载 `/release` 或启用 RAG 恢复开关，例如正式 G3）默认拒绝执行恢复。确认现场后需要显式追加 `-AllowReleaseRestart`；它仍然只重启同一个容器，不重建、不更换镜像，因此发布清单绑定的运行制品不变。

| `ROOT_CAUSE` | 含义与首选处理 |
|---|---|
| `DOCKER_ENGINE_UNAVAILABLE` | Docker Desktop 未启动或 Engine 不可用。启动 Docker Desktop 后重试。 |
| `CONTAINERS_NOT_CREATED` | 实例尚未初始化。确认是全新环境后再 `init`。 |
| `BACKEND_NOT_RUNNING` / `INFRASTRUCTURE_NOT_RUNNING` | 容器未运行。执行 `up`，仍失败时看 `docker ps -a`。 |
| `BACKEND_INTERNAL_HEALTH_DOWN` | Java 后端或依赖启动失败。先看后端日志与 MySQL/Redis 状态，重启容器不能修复这类问题。 |
| `DOCKER_PORT_PUBLISH_MISSING` | 容器健康但 Docker 未完成端口发布。用 `repair-ports`。 |
| `WINDOWS_PORT_CONFLICT` | 端口被其他进程占用。先释放端口，再重新诊断。 |
| `PORT_CONFIG_MISMATCH` | 容器内的端口配置与当前 env 不一致，需要按部署手册重建应用容器（受控实例按发布流程）。 |
| `BACKEND_HOST_HTTP_FAILED` | 端口已发布但宿主机仍请求失败，按提示重启后端并复核。 |
| `FRONTEND_NGINX_UNREACHABLE` | 前端容器或 Nginx 异常，查看前端容器日志后重启应用容器。 |
| `FRONTEND_REQUEST_TARGET_MISMATCH` | 前端产物内嵌的 API 端口与实际发布端口不一致，需要重新构建前端。 |

## 6. 配置与凭据

常用配置见 [`.env.example`](script/dev/.env.example)。一键部署读取 `.runtime/<实例名>/.env`。填写模型 Key 时，在实例的私有 env 中设置 `SPRING_AI_OPENAI_API_KEY`，并核对 `SPRING_AI_OPENAI_BASE_URL` 和 `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`。在普通实例运行下列命令，复用已有应用镜像，并由 Compose 应用修改后的环境配置。

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action upgrade -SkipBuild
```

先启动目标实例的基础服务。隔离实例附加相同的 `-Instance`。受控发布实例按[受控发布手册](script/release/README.md)操作。勿通过只改 env 轮换现有数据库、Redis 或 MinIO 凭据。

| 配置 | 本地部署默认行为 |
|---|---|
| `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`MINIO_SECRET_KEY`、`RABBITMQ_DEFAULT_PASS`、`CHEN_STACK_JWT_SECRET` | 初始化为空的项生成随机值 |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | 后端通过命名 ACL 用户认证，Redis 禁用默认用户，配置和健康检查密码文件在实例的 `redis/` |
| `NORMAL_AI_USAGE` | 默认启用，后端在 `ai-usage/events.jsonl` 初始化并追加 Provider 用量记录 |
| `AI_KNOWLEDGE_MODE` / `CHEN_STACK_AI_DAILY_LIMIT` | 本地默认 `hybrid` / 每人每日 100 次，数据库中的在线配置优先 |
| `CHEN_WIKI_ENABLED` / `CHEN_WIKI_PRODUCT_ENABLED` | 本地默认开启，初始化执行所需迁移 |
| `CHEN_WIKI_COMPILER_ENABLED` / `WIKI_PUBLIC_PORTAL_ENABLED` | 默认关闭，分别控制智能整理和公开 Wiki 阅读 |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 注册、重置密码等邮件功能。默认 SMTP 主机是 `smtp.qq.com`，使用邮箱及其 SMTP 授权码 |
| `ALIYUN_ACCESS_KEY_ID` / `ALIYUN_ACCESS_KEY_SECRET` | 可选图片内容安全服务 |

## 7. 数据库与运行数据

MySQL 仅在初始化新数据卷时导入 [`sql/chen_stack.sql`](sql/chen_stack.sql)。部署脚本的 `init`、`upgrade`、`migrate` 按[部署手册中的固定顺序](script/deploy/README.md#数据库迁移)检查并应用增量 SQL。脚本根据表、触发器或菜单标记跳过已应用项。

MySQL、Redis（AOF）、MinIO、RabbitMQ 和可选 Qdrant 使用 Docker 命名卷。AI 用量文件和 Redis 配置保存在 `.runtime/<实例名>/`。应用镜像回滚不回滚 SQL 或知识数据。升级前备份数据和运行配置，保留原卷和凭据。

## 8. 开发与测试

从仓库根目录执行后端测试和打包。

```powershell
mvn --file chen-stack-backend/pom.xml test
mvn --file chen-stack-backend/pom.xml -DskipTests package
```

两个前端使用各自的 `package-lock.json`。以下命令从仓库根目录连续执行。

```powershell
npm --prefix ./chen-stack-frontend/chen-stack-admin ci
npm --prefix ./chen-stack-frontend/chen-stack-admin run test:run
npm --prefix ./chen-stack-frontend/chen-stack-user ci
npm --prefix ./chen-stack-frontend/chen-stack-user run test:run
```

在两个终端中分别启动开发服务器。示例假定默认 Docker 后端已经运行。先停止同端口的 Docker 前端，保留后端和基础服务。前端的 `VITE_BACKEND_SERVER` 是构建或开发服务器启动时读取的公开 API 地址。

```powershell
# 终端一，仓库根目录
$env:VITE_BACKEND_SERVER = 'http://127.0.0.1:5000'
npm --prefix ./chen-stack-frontend/chen-stack-admin run dev
```

```powershell
# 终端二，仓库根目录
$env:VITE_BACKEND_SERVER = 'http://127.0.0.1:5000'
npm --prefix ./chen-stack-frontend/chen-stack-user run dev
```

Vite 开发端口为 8000 和 7000。自定义端口时需要同步配置后端的 `chen-stack.allow-origins`。直接运行后端 JVM 时，要显式注入依赖地址和凭据，Spring 不会自动加载仓库里的 `.env`。

部署脚本本身有独立的隔离验收，不需要 Docker 或正式实例参与：

```powershell
pwsh -NoProfile -File ./script/deploy/tests/Run-DeployTests.ps1
```

它依次运行静态安全检查、诊断/恢复的故障注入单元测试，以及用临时实例和 Docker 替身跑的命令行级端到端测试，覆盖端口未发布、端口被占用、Engine 未启动、前端不可访问、历史容器、受控发布边界、重试上限与幂等。仅使用只读检查和临时 `dsh-test-*` 实例，不会启动或重启任何正式容器。

## 9. 安全与常见问题

一键部署的 Docker 端口只绑定本机。保护 `.runtime/` 下的 env、初始管理员文件、Redis ACL、账本和发布授权。它们受 Git 忽略。旧普通部署的 env 兼容路径见[部署手册](script/deploy/README.md)，受控实例应保留完整 Compose、凭据和挂载。查看配置时使用 `docker compose config --quiet`，避免展开秘密。

| 现象 | 检查与处理 |
|---|---|
| `init` 报已有容器、卷或网络 | 已初始化的实例用 `up`。另起一套时指定新实例名与空闲端口。部分初始化失败时先保留资源并排查。 |
| **容器 `Running` 但页面打不开、API 或验证码请求失败** | 不是普通“服务没起”。先运行 `-Action doctor`，看 `PORT_5000_PUBLISHED`、`BACKEND_HOST_ACCESS` 和 `ROOT_CAUSE`；若为 `DOCKER_PORT_PUBLISH_MISSING`，按[5.5 节](#55-部署健康检查与安全恢复)用 `repair-ports` 处理。不要靠反复 `docker compose up -d` 或删除容器“修复”。 |
| 数据库未就绪或后端启动失败 | 后端内部健康 `DOWN` 时看 `docker logs --tail 200 chen-stack-backend` 与 MySQL 日志，确认基础服务容器状态；`up` 会等待 MySQL/Redis/MinIO/RabbitMQ 就绪，超时会明确报错。 |
| 端口被占用 | `doctor` 会给出占用进程。用 `Get-NetTCPConnection -State Listen -LocalPort 5000 \| Select-Object LocalAddress,LocalPort,OwningProcess` 复核，释放端口后再诊断；不要为此修改业务端口。 |
| 前端能打开但请求全部失败 | 多在端口发布或前端产物内嵌的 API 地址。看 `FRONTEND_REQUEST_TARGET` 与 `BACKEND_HOST_ACCESS`，必要时重新构建前端。 |
| Docker Desktop 未启动 | `docker version` 失败。启动 Docker Desktop 并等待 Engine 就绪，再执行 `up` 或 `doctor`；脚本不会自行拉起 Desktop。 |
| 浏览器提示请求或跨域失败 | 确认访问脚本输出的地址、API 端口和后端来源白名单。前端构建后更改 API 地址需要重新打包。 |
| Redis 不健康 | 检查实例 `redis/` 下的配置、LF 格式 ACL 和健康检查密码文件，确认命名用户与应用 env 一致。 |
| 小辰提示模型服务不可用 | 核对 Key、API 地址、模型权限与 Provider 额度，并确认 backend 使用了新环境。 |
| 知识未命中或索引未就绪 | 检查在线生效模式、Wiki 来源和发布状态，或 RAG 的 Qdrant、Embedding、目录及发布回执。 |
| `upgrade` 被拒绝或启动出现 `PUBLICATION_*` | 核对受控实例的运行 JAR、清单和发布回执，按[发布手册](script/release/README.md)处理。 |

### 9.1 5000 端口映射异常的处理步骤

适用于：容器在运行、容器内 `/actuator/health` 是 `UP`，但 Windows 打不开 `http://127.0.0.1:5000`（登录验证码、用户端 API、管理端 API 同时失败）。

1. 运行诊断并保留输出（摘要会自动进剪贴板）：
   ```powershell
   pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor
   ```
   关键行：`PORT_5000_PUBLISHED=NO`、`BACKEND_HOST_ACCESS=FAIL`、`ROOT_CAUSE=DOCKER_PORT_PUBLISH_MISSING`。诊断只读，可以先在正式环境执行。
2. 区分“Docker 没发布”和“端口被别人占用”：`ROOT_CAUSE=WINDOWS_PORT_CONFLICT` 说明宿主机端口被其他进程占用，先处理占用进程：
   ```powershell
   Get-NetTCPConnection -State Listen -LocalPort 5000 | Select-Object LocalAddress,LocalPort,OwningProcess
   netsh int ipv4 show excludedportrange protocol=tcp
   ```
3. 确认是 `DOCKER_PORT_PUBLISH_MISSING` 后，先只诊断、不动作：
   ```powershell
   pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports
   ```
4. 人工确认后执行受控重启（脚本内部等价于 `docker restart --timeout 30 chen-stack-backend`，但会先核对镜像、挂载与重试上限，并在重启后重新验证端口发布与宿主机 HTTP）：
   ```powershell
   pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports -Apply
   ```
5. 复核结果：`CHEN_STACK_REPAIR=SUCCESS` 后再跑一次 `doctor`，确认 `CHEN_STACK_DOCTOR=PASS`、`PORT_5000_PUBLISHED=YES`、`BACKEND_HOST_ACCESS=PASS`、`FRONTEND_USER=PASS`、`FRONTEND_ADMIN=PASS`。

如果 `repair-ports` 判定为 `REFUSED` 或 `FAILED`，不要继续反复重启：按输出里的 `NEXT_STEP` 处理，保留现场并核对容器日志、端口占用与 Windows 排除端口范围。直接原因可能是 Docker 端口代理、Windows 端口保留或宿主网络栈状态，但具体触发点需要现场证据确认，脚本不会替你下结论。

## 10. 文档索引

- [本地部署和日常运维](script/deploy/README.md)
- [部署诊断与安全恢复测试](script/deploy/tests/Run-DeployTests.ps1)
- [受控 RAG 发布与恢复](script/release/README.md)
- [AI 持续使用与计量](script/prod/README-AI-USAGE.md)
- [仓库协作规则](AGENTS.md)

## 11. 使用说明

本项目用于学习与技术实践。仓库目前没有单独的 `LICENSE` 文件。
