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
| **AI 知识管理** | 知识库（唯一知识新增入口）、知识来源、智能整理、Wiki页面、检索验证 |
| **AI 配置** | 三档知识模式、每日额度、最近修改记录 |

### 智能助手能力

- **三档知识模式**（管理员配置，普通用户无感）：
  - `RAG 知识检索`：只使用已审校的知识库内容；
  - `Wiki 知识问答`：从当前已发布且来源有效的 Wiki 页面中检索；
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

Docker Compose（正式实例包含 MySQL / Redis / MinIO / RabbitMQ / Qdrant / 后端 / 管理端 / 用户端），
基础镜像：`eclipse-temurin:21-alpine`、`nginx:alpine`、`mysql:8.0`、`redis:7-alpine`、
`minio/minio:RELEASE.2025-04-08T15-41-24Z`、`rabbitmq:3-management-alpine`、`qdrant/qdrant:v1.13.6`。

正式架构中，用户端和管理端由 Nginx 提供页面，通过 HTTP API 与后端交互。用户端通过 SSE 接收客服事件。后端连接 MySQL、Redis、RabbitMQ 和 MinIO。RAG 使用独立 Embedding 服务和 Qdrant，Wiki 从 MySQL 读取已发布页面及来源，DeepSeek 根据检索证据生成回答。

## 4. 项目结构

```
zcx-chen-stack/
├─ chen-stack-backend/                 # Spring Boot 后端
│  ├─ src/main/java/com/zcx/chenstack/
│  │  ├─ controller/  service/  mapper/  domain/  config/  security/
│  │  ├─ knowledge/                    # 文档、知识处理与统一知识库
│  │  ├─ rag/                          # RAG 知识目录、检索、发布与运行配置
│  │  ├─ wiki/                         # LLM Wiki 来源、编译、发布与阅读
│  │  └─ websocket/  redis/  rabbitmq/
│  ├─ src/main/resources/knowledge/    # 待审校的 RAG 知识资源
│  ├─ src/test/java/                   # 后端回归测试
│  └─ Dockerfile
├─ chen-stack-frontend/
│  ├─ chen-stack-admin/                # 管理端（Vue 3）
│  │  ├─ src/views/ai/                 # 知识库 / 检索验证 / AI 配置
│  │  ├─ src/views/wiki/               # Wiki页面 / 知识来源 / 智能整理
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

以下命令均在 **Windows 11 的 PowerShell 7** 中执行。已有正式数据时，从项目根目录进入 5.3 节。新环境从 5.2 节开始。

### 5.1 准备环境

- 启用 WSL 2，安装 Docker Desktop，使用 WSL 2 和 Linux 容器。开机后通过开始菜单打开 Docker Desktop，等 Engine 就绪。若已配置登录时启动 Docker Desktop，可直接检查 Engine。
- 安装 Git、PowerShell 7 和支持 `docker compose config --format json` 的 Compose 插件。
- 首次构建应用时安装 JDK 21、Maven 3.9+、Node.js 22.12+ 和 npm。已有正式容器的日常启停复用本地镜像。

```powershell
wsl --list --verbose
docker version --format '{{.Server.Version}}'
docker compose version
```

### 5.2 首次部署（空数据卷）

首次部署使用[当前数据库基线](sql/bootstrap/chen_stack_baseline.sql)和[本地 Compose](script/dev/docker-compose.yml)。基线建立 33 张表和 15 个触发器，预置角色、权限和菜单。业务数据、聊天历史、文档与向量从空状态开始，脚本为新实例生成本地服务凭据，并创建下列两个默认账号。MySQL 只在新数据卷中导入基线，SQL 也会检查目标数据库为空。

```powershell
git clone https://github.com/zcxzzz188/zcx-chen-stack.git
if ($LASTEXITCODE -ne 0) { throw '克隆失败' }
Set-Location zcx-chen-stack
New-Item -ItemType Directory -Path .runtime/chenstack-release -Force | Out-Null
Copy-Item ./script/dev/.env.example .runtime/chenstack-release/.env
notepad .runtime/chenstack-release/.env
```

在私有 env 填写 `SPRING_AI_OPENAI_API_KEY` 和 `CHEN_RAG_EMBEDDING_API_KEY`，核对第 6 节的模型与服务地址，保存后执行：

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance chenstack-release -PortBase 29220 -Action init
if ($LASTEXITCODE -ne 0) { throw '首次部署失败' }
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance chenstack-release -Action status
(Invoke-RestMethod http://127.0.0.1:29226/actuator/health/readiness -TimeoutSec 10).status
(Invoke-WebRequest http://127.0.0.1:29228 -TimeoutSec 10).StatusCode
(Invoke-WebRequest http://127.0.0.1:29227 -TimeoutSec 10).StatusCode
```

初始化从当前源码构建后端和两个前端，创建实例专用 Qdrant TLS 证书，然后启动八个服务。看到后端 `UP` 和两个前端的 `200` 后，打开 [用户端](http://127.0.0.1:29228) 或 [管理端](http://127.0.0.1:29227)。普通账号注册及找回密码需要 SMTP。

| 管理端角色 | 用户名 | 初始密码 |
|---|---|---|
| 辰栈系统最高管理员 | `chenstack` | `123456` |
| 内容管理员 | `test` | `123456` |

默认账号和密码仅供本地学习演示，不得直接用于公网正式服务。安装器使用 Spring Security BCrypt 为两个账号分别生成密码摘要。已有数据库的日常启动会沿用当前账号和密码。

示例使用 29220–29230 端口段，可与既有正式实例并存。按[隔离实例说明](script/deploy/README.md#隔离实例)选择其他空闲端口时，以脚本打印的地址为准。日常使用同一 `-Instance` 执行 `up`、`status` 或 `stop`，保留原数据。

本轮在 Windows 11 验证了八服务启动、两个默认账号登录和权限隔离、MD/TXT 上传、三种知识检索和重启后的数据恢复。普通账号注册已用隔离 SMTP 实测。找回密码尚未实测。

### 5.3 已有正式数据的日常启动与开机恢复

正式入口为 `script/deploy/ChenStack.ps1 -Instance prod`。正式 Compose 为本机私有的 `.runtime/prod/compose.json`，项目名 `chen-stack-prod`。它引用既有本地镜像、外部网络和数据卷。历史基础服务可能保留 `dev` 或无 Compose 项目标签，正式入口按固定容器名和实际挂载管理它们。保留运行目录、现有镜像、挂载和凭据。

Docker Engine 可连接后，从仓库根目录执行：

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance prod -Action check
if ($LASTEXITCODE -ne 0) { throw '正式配置检查失败' }
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance prod -Action up
if ($LASTEXITCODE -ne 0) { throw '正式服务启动失败' }
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance prod -Action status
```

当前八个正式容器的重启策略均为 `unless-stopped`。Docker Desktop 启动后，Engine 会恢复关机前正在运行的容器。主动停止过的容器用上面的 `up` 恢复。该命令启动既有容器，缺失的容器使用正式 Compose 和本机已有镜像创建，并等待后端 readiness。

| 服务 | 访问或检查方式 |
|---|---|
| 后端 API | http://127.0.0.1:5000 |
| 用户端 | http://127.0.0.1:7000 |
| 管理端 | http://127.0.0.1:8000 |
| MySQL / Redis | 社区及 AI 数据 / 缓存与额度等在线状态 |
| RabbitMQ / MinIO | 异步消息 / 社区业务对象存储 |
| Qdrant | 通过内部网络提供向量集合，使用实例内 TLS 和独立密钥 |

```powershell
(Invoke-RestMethod http://127.0.0.1:5000/actuator/health/readiness -TimeoutSec 10).status
(Invoke-WebRequest http://127.0.0.1:7000 -TimeoutSec 10).StatusCode
(Invoke-WebRequest http://127.0.0.1:8000 -TimeoutSec 10).StatusCode
docker ps -a --filter 'name=^/(chen-stack-(backend|user|admin|mysql|redis|minio|rabbitmq)|chen-qdrant-release)$' --format 'table {{.Names}}	{{.Status}}'
```

应看到 readiness `UP`、两个前端均为 HTTP 200、八个正式容器正在运行。MySQL、Redis、RabbitMQ、MinIO 应为 `healthy`。Qdrant 向量链路可在管理端用 RAG 检索确认。

正式应用停止命令如下。它停止后端、用户端和管理端，保留五个基础服务与全部数据。

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance prod -Action stop
if ($LASTEXITCODE -ne 0) { throw '正式应用停止失败' }
```

需要演练八个容器全部停止时，在维护窗口先执行应用停止命令，再执行以下命令。确认八个容器均已停止后，按本节的 `check → up → status` 恢复。

```powershell
docker stop chen-qdrant-release chen-stack-rabbitmq chen-stack-minio chen-stack-redis chen-stack-mysql
if ($LASTEXITCODE -ne 0) { throw '正式基础服务停止失败' }
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance prod -Action status
```

### 5.4 G5/G6 知识库与客服

在管理端 **AI 知识管理 → 知识文档** 上传 Markdown 或文本，查看上传原件、整理母版和文档处理状态。系统按文档生成知识，分别推进 RAG 的分块、向量入库与 Wiki 的处理。管理端可分别查看 RAG 知识与 Wiki 页面及其来源，并在 **检索验证** 选择 RAG、Wiki 或 Hybrid。**AI 配置** 可读取或调整实际生效的知识模式、回复风格、检索条数和用户额度。用户端「小辰」提供带来源的流式回答、站内导航及会话恢复。

### 5.5 服务未就绪时

```powershell
pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Instance prod -Action status
docker logs --tail 100 chen-stack-backend
docker logs --tail 100 chen-stack-mysql
```

| 现象 | 检查方式 |
|---|---|
| Engine 不可连接 | 打开 Docker Desktop，确认 WSL 2 和 Linux Engine 就绪，重试 `docker version`。 |
| 正式 `check` 失败 | 确认 `.runtime/prod/compose.json`、外部网络、挂载与本机镜像仍可用。缺失时按[正式实例运维手册](script/deploy/README.md)核对。 |
| 后端 readiness 超时 | 先看依赖容器健康与后端日志，检查 5000 端口和当前挂载。 |
| 前端打不开或验证码不加载 | 查看相应前端容器日志，再确认宿主机 5000、7000、8000 的实际连通性及前端 API 地址。 |
| 模型不可用、RAG 没有证据 | 查看 AI 配置、DeepSeek 状态、独立 Embedding 配置、Qdrant 集合和知识处理状态。 |

普通开发实例的 `doctor` 和 `repair-ports` 用法见[部署手册](script/deploy/README.md)。正式实例使用本节的诊断命令。

## 6. 配置与凭据

开发模板为 [`script/dev/.env.example`](script/dev/.env.example)，正式配置字段可参考 [`script/prod/.env.example`](script/prod/.env.example)。正式 Windows 实例的应用环境由私有 Compose 提供。模型凭据留在私有运行配置中，复制示例时填入自己的值。

| 用途 | 配置键 |
|---|---|
| DeepSeek 聊天 | `SPRING_AI_OPENAI_API_KEY`、`SPRING_AI_OPENAI_BASE_URL`、`SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` |
| 独立 Embedding | `CHEN_RAG_EMBEDDING_API_KEY`、`CHEN_RAG_EMBEDDING_BASE_URL`、`CHEN_RAG_EMBEDDING_MODEL` |
| 向量集合 | `CHEN_RAG_QDRANT_HOST`、`CHEN_RAG_QDRANT_GRPC_PORT`、`CHEN_RAG_QDRANT_TLS`、`CHEN_RAG_QDRANT_API_KEY`、`CHEN_RAG_QDRANT_COLLECTION` |

正式 AI 在线配置保存在 `.runtime/prod/ai/settings.yml`。进程重启沿用已有容器环境。修改正式应用的环境变量应纳入部署流程，使容器加载新配置。Redis ACL、MySQL 与 MinIO 凭据应与各自服务的持久化配置一致。

## 7. 数据库与运行数据

现有正式 MySQL 的 `chen_stack` 库有 **33 张表、15 个触发器**，其中 AI 业务表有 **8 张**。它们覆盖知识文档、RAG 知识与分块、Wiki 页面与来源及版本、客服会话与消息。

MySQL、Redis、MinIO、RabbitMQ 和 Qdrant 通过命名卷保存数据。正式 AI 设置、知识原件与整理文件、用量记录在 `.runtime/prod/` 对应挂载中。现存 Redis 和 Qdrant 还使用历史路径，详见[部署手册](script/deploy/README.md)。日常恢复保留原卷与文件，原地使用 `-Instance prod -Action up`。备份应包含卷数据与私有配置，保留在独立的备份目录并在隔离环境验证恢复。升级前核对镜像与数据库版本，应用镜像回滚不会回滚数据。

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

## 9. 运行安全

保留 `.runtime/` 中的私有 Compose、env、Redis ACL、TLS 私钥和 AI 配置。查看 Compose 校验结果时使用 `config --quiet`。停止和恢复时保留正式数据卷，禁止对正式实例执行 `down -v` 或卷清理。空卷初始化和退役迁移属于单独的部署流程。

## 10. 文档索引

- [本地部署和日常运维](script/deploy/README.md)
- [部署诊断与安全恢复测试](script/deploy/tests/Run-DeployTests.ps1)
- [受控 RAG 发布与恢复](script/release/README.md)
- [AI 持续使用与计量](script/prod/README-AI-USAGE.md)
- [仓库协作规则](AGENTS.md)

## 11. 使用说明

本项目用于学习与技术实践。仓库目前没有单独的 `LICENSE` 文件。
