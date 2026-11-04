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
- 用户问平台规则、操作步骤时，小辰先取证据再回答，查不到就说明查不到，**不编造**功能、按钮或政策；
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

### 智能助手能力（G3）

- **三档知识模式**（管理员配置，普通用户无感）：
  - `RAG 知识检索`：只使用已审校的原始知识目录；
  - `Wiki 知识问答`：只使用当前已发布且来源有效的整理知识；
  - `智能混合（推荐）`：按问题在两者之间按需选择，简单交流不检索。
- **按需检索**：混合模式先用一次轻量范围分类判断是否需要知识；需要时先读最相关的一侧，
  证据不足才补另一侧，不做「每轮都双查」。
- **来源与版本可追溯**：后端记录真实知识模式、来源 ID/标题/版本；普通用户界面只展示标题级来源，
  不展示内部 ID、向量分数或 Wiki 技术版本。
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
| 其它 | Lombok、Hutool、fastjson2、easy-captcha、MinIO SDK 8.3.6、阿里云内容安全 |

### 前端

| 端 | 选型 |
|---|---|
| 两端通用 | Vue 3.5、Vite 8（`vite-plus` 工具链）、Pinia 3、Element Plus 2.10、Axios、Vue Router 4 |
| 管理端 | Element Plus 组件体系 + 自研管理卡片/表格/分页组件、AntV G2 图表、xlsx 导出 |
| 用户端 | Element Plus、markdown-it、DOMPurify、文件保存、SSE 流式渲染 |

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
│  │  ├─ websocket/ redis/ rabbitmq/ minio/ task/ aspect/
│  │  └─ resources/knowledge/          # 内置知识草稿（未发布，需审计后发布）
│  ├─ src/test/java/                   # 正式回归测试（337 个用例）
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
│  ├─ deploy/                          # ★ 一键部署与运维（init/up/upgrade/rollback）
│  ├─ dev/                             # docker-compose、.env.example、Redis 安全配置模板
│  ├─ release/                         # 正式受控发布（构建、清单、发布操作）
│  └─ prod/                            # 生产编排模板与备份脚本
├─ sql/                                # 基础表结构 + G2/G3 增量迁移（含回滚脚本）
├─ AGENTS.md                           # 协作规则
└─ README.md
```

## 5. 快速开始（Docker 一键部署）

> 详细说明见 [`script/deploy/README.md`](script/deploy/README.md)。

### 5.1 环境要求

- Windows 11 + Docker Desktop（Compose v2）+ PowerShell 7
- 首次构建需要 JDK 21（Maven 3.9+）与 Node.js 20+
- 若仓库已带构建产物（`chen-stack-backend/target/*.jar`、两个前端 `dist/`），可加 `-SkipBuild` 跳过工具链要求

### 5.2 一条命令

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action init
```

脚本会生成 `.env`（随机强口令）、Redis ACL 配置、构建镜像、执行全部 SQL 迁移、启动并等待健康检查。

### 5.3 之后常用

```powershell
pwsh -File script/deploy/ChenStack.ps1 -Action up        # 电脑重启后启动（不重建）
pwsh -File script/deploy/ChenStack.ps1 -Action status    # 查看容器、健康与默认知识模式
pwsh -File script/deploy/ChenStack.ps1 -Action upgrade   # 以现有数据升级（自动留 :previous 镜像）
pwsh -File script/deploy/ChenStack.ps1 -Action rollback  # 回滚到 upgrade 之前的应用镜像
pwsh -File script/deploy/ChenStack.ps1 -Action stop      # 停止容器（保留数据卷）
```

**四种场景不要混用**：

| 场景 | 命令 | 是否重建容器 | 是否迁移 |
|---|---|---|---|
| 首次全新部署 | `init` | 创建 | 是 |
| 已有数据升级 | `upgrade` | 仅三个应用容器 | 是（幂等） |
| 关机重启后启动 | `up` | 否（只 `docker start`） | 否 |
| 回滚 | `rollback` | 仅三个应用容器 | 否 |

### 5.4 访问地址

| 入口 | 默认地址 |
|---|---|
| 管理端 | http://127.0.0.1:8000 |
| 用户端 | http://127.0.0.1:7000 |
| 后端 API | http://127.0.0.1:5000 |
| MinIO 控制台 | http://127.0.0.1:9001 |
| RabbitMQ 管理 | http://127.0.0.1:15672 |

初始管理员账号来自 `sql/chen_stack.sql` 演示数据，**首次登录后请立即修改密码**。

### 5.5 知识准备（首次部署后）

全新环境的**知识库是空的**：小辰能自然对话、能查本人文章，但知识类问题会如实回答「没有查到」。
按需选择一条路径：

1. **Wiki（最快，不需要 Embedding）**：管理端 →「AI知识管理 → 整理知识」→ 手动新建 → 审核 → 发布。
2. **RAG（需要向量化）**：准备独立 Embedding 凭据与 Qdrant，按 `script/release/README.md`
   的受控发布流程导入 `src/main/resources/knowledge/` 下的草稿（会产生 Embedding 费用）。

内置的 `knowledge/*.json` 是**未发布草稿**（`NOT_PUBLISHED` / `CONTENT_REVIEW_REQUIRED`），
必须经过发布审计才能成为公开知识。

## 6. 环境变量

完整清单见 [`script/dev/.env.example`](script/dev/.env.example)（仅含占位符，不含任何真实密钥）。
一键部署脚本会为所有空密码生成随机值。

### 必须设置

| 变量 | 用途 |
|---|---|
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | MySQL root 与应用账号口令 |
| `REDIS_PASSWORD` | Redis ACL 用户口令（同时写入只读 ACL 文件） |
| `MINIO_SECRET_KEY` | MinIO 密钥 |
| `RABBITMQ_DEFAULT_PASS` | RabbitMQ 口令 |
| `CHEN_STACK_JWT_SECRET` | JWT 签名密钥（生产建议 48 字节以上随机值） |
| `SPRING_AI_OPENAI_API_KEY` | DeepSeek API Key（不填则小辰返回模型服务错误） |

### 常用可选项

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_KNOWLEDGE_MODE` | `hybrid` | 部署默认知识模式；管理员在线配置优先 |
| `CHEN_STACK_AI_DAILY_LIMIT` | `100` | 每位用户每日消息额度 |
| `CHEN_WIKI_ENABLED` / `CHEN_WIKI_PRODUCT_ENABLED` | `true` | Wiki 产品开关（需先执行迁移） |
| `WIKI_PUBLIC_PORTAL_ENABLED` | `false` | 是否开放公开 Wiki 阅读入口 |
| `SPRING_PROFILES_ACTIVE` | `dev` | 启用 RAG 时追加 `,rag` |
| `CHEN_RAG_ENABLED` | `false` | RAG 总开关（还需 Embedding 凭据与知识发布） |
| `CHEN_RAG_EMBEDDING_API_KEY` | 空 | 独立 Embedding 凭据，**不得复用**聊天 Key |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 占位 | 找回密码等邮件功能 |
| `ALIYUN_ACCESS_KEY_ID` / `_SECRET` | 空 | 阿里云图片内容安全（可选） |
| `CHEN_STACK_RATE_LIMIT_ENABLED` | `true` | 接口限流开关 |

### 端口

`BACKEND_PORT=5000`、`ADMIN_PORT=8000`、`USER_PORT=7000`、`MYSQL_PORT=3308`、
`REDIS_PORT=6379`、`MINIO_API_PORT=9000`、`MINIO_CONSOLE_PORT=9001`、
`RABBITMQ_PORT=5672`、`RABBITMQ_MANAGEMENT_PORT=15672`；
RAG 选配 `CHEN_RAG_QDRANT_GRPC_PORT`（默认 6334 / 本地 profile 16334）。

## 7. 数据库初始化

- 基础表结构：`sql/chen_stack.sql`，MySQL 容器**首次**初始化时自动导入；
- G3 增量迁移：`sql/g2_rag_catalog.sql`、`sql/g3_wiki_*.sql`、`sql/g3_ai_knowledge_*.sql`，
  由 `ChenStack.ps1 -Action init/upgrade/migrate` 按顺序**幂等**执行（以目标表/菜单为标记）；
- 回滚脚本：`sql/*_rollback.sql`，仅在明确回退时手工执行；
- 已有数据环境升级不会重建数据库、不会清空向量库、不会删除已发布 Wiki。

## 8. 开发说明

```powershell
# 后端（回归测试 337 个用例）
mvn --file chen-stack-backend/pom.xml test
mvn --file chen-stack-backend/pom.xml -DskipTests package

# 管理端 / 用户端
cd chen-stack-frontend/chen-stack-admin; npm install; npm run dev; npm run test
cd chen-stack-frontend/chen-stack-user;  npm install; npm run dev; npm run test
```

- 前端开发服务器需把 API 指向 `http://127.0.0.1:5000`；
- 后端本地运行需要 MySQL/Redis/MinIO/RabbitMQ 可达（可只启动基础服务容器）；
- 正式回归入口：`mvn test`（后端）与 `vp test run`（管理端）。临时实验与证据放在 `.work/`（不入库）。

## 9. 安全说明

- 仓库**不包含任何真实凭据**：`.env`、`script/dev/rag-tls/`、`script/dev/.instances/`、
  `script/dev/redis-secure/{redis.conf,users.acl,redis-app.secret}` 均已被 `.gitignore` 忽略。
- 生产环境请使用独立强口令，并通过 `script/release` 的受控发布流程部署；
  普通 Compose 重建会被脚本拒绝，避免运行制品与 RAG 发布清单不一致。
- RBAC 在后端回源校验，菜单、角色、权限与操作权限**不缓存信任**；聊天中的自称身份不参与授权。
- 小辰的导航按钮只来自白名单枚举，前端只执行白名单路由；不存在任意 URL 跳转。
- 知识来源撤权后立即失效：RAG 校验目录快照与来源授权，Wiki 每轮重新读取已发布正文。
- 内容安全：图片可接阿里云内容安全，文章/评论支持自动审核开关（默认关闭或仅文章开启）。

## 10. 常见问题

| 现象 | 原因与处理 |
|---|---|
| `init` 报「实例已存在容器」 | 已有部署，用 `-Action up` 或 `-Action upgrade`；确实要重建加 `-Force` |
| `upgrade` 被拒绝 | 该实例由受控发布流程管理（见第 9 节），需走 `script/release` |
| 后端启动失败、日志含 `PUBLICATION_*` | 运行 JAR 与 RAG 发布清单不匹配，属于 fail-closed；按受控发布流程重新生成清单 |
| RAG 显示「知识索引尚未就绪」 | 未完成知识发布或 Qdrant/Embedding 不可用；混合模式会自动只用 Wiki |
| 知识问题回答「没有查到」 | 知识库为空或未命中；先按 5.5 准备知识 |
| 小辰回复「模型服务暂时不可用」 | `SPRING_AI_OPENAI_API_KEY` 未配置或额度耗尽 |
| Redis 容器 unhealthy | `redis-secure` 文件缺失或口令与 `.env` 不一致；重新执行 `init` 会自愈生成 |
| 端口被占用 | 修改 `script/dev/.env` 中对应端口后 `-Action upgrade`（或重建该容器） |

## 11. 文档索引

| 文档 | 内容 |
|---|---|
| [`script/deploy/README.md`](script/deploy/README.md) | 一键部署四种场景、迁移清单、隔离实例、已验证范围 |
| [`script/release/README.md`](script/release/README.md) | 正式受控发布流程（构建、发布清单、RECOVER 回执） |
| [`script/prod/README-AI-USAGE.md`](script/prod/README-AI-USAGE.md) | Provider 用量账本与计量说明 |
| [`AGENTS.md`](AGENTS.md) | 仓库协作与数据安全规则 |

## 12. 许可证

本项目用于学习与技术实践。引用或二次开发请注明来源。
