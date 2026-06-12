# 辰栈

> AI 辅助技术博客管理系统

## 项目概述

辰栈（zcx-chen-stack）是一套面向技术内容社区的前后端分离博客管理系统，由用户端、管理端和 Spring Boot 后端服务组成。

系统围绕技术内容的生产、审核、发布、互动和运营管理展开，覆盖文章创作、专栏管理、评论回复、点赞收藏、用户关注、消息通知、实时私信和 AI 辅助创作等业务场景。同时提供独立的管理后台，用于处理内容审核、用户管理、角色权限、动态菜单和系统日志等运营工作。

后端基于 Spring Boot、Spring Security 和 MyBatis-Plus 构建，使用 MySQL 存储业务数据、Redis 管理缓存和调用状态、RabbitMQ 执行异步通知、MinIO 存储业务图片，并通过 WebSocket 支持私信和在线状态通信。

系统不仅实现了完整的博客业务流程，还针对权限缓存一致性、分页查询 N+1、动态路由同步、跨存储删除一致性、逻辑删除数据膨胀、AI 接口滥用和审核状态同步等实际开发问题进行了处理。

## 系统组成

| 组成 | 说明 | 项目目录 |
| --- | --- | --- |
| 用户端 | 面向普通用户的内容浏览、创作与互动平台 | `chen-stack-frontend/chen-stack-user` |
| 管理端 | 面向管理员的内容审核与系统管理平台 | `chen-stack-frontend/chen-stack-admin` |
| 后端服务 | 提供认证、业务接口、缓存、消息、存储和定时任务 | `chen-stack-backend` |

## 功能特性

### 用户端

#### 账号与个人中心

- 用户注册、登录和退出
- 邮箱验证码校验
- JWT 登录状态管理
- 个人资料编辑
- 头像上传与审核状态展示
- 邮件通知开关配置
- 用户主页与关注关系展示

#### 文章与专栏

- 文章浏览和关键词搜索
- 文章创作、编辑与发布
- 草稿和审核状态管理
- 文章封面及正文图片上传
- 专栏创建、编辑和删除
- 文章加入或移出专栏
- 专栏及所属文章管理

#### 社区互动

- 文章评论与评论回复
- 文章和评论点赞
- 文章收藏
- 收藏夹创建与管理
- 用户关注
- 浏览历史记录

#### 消息与私信

- 系统通知和互动通知
- 消息已读及未读数量管理
- WebSocket 实时私信
- 私信图片发送与审核
- 私信消息撤回
- 用户在线状态展示

#### AI 辅助功能

- 文章摘要生成
- 标题建议
- 标签推荐
- 评论回复建议
- 智能客服
- AI 调用次数与额度限制

### 管理端

#### 内容审核

- 文章查询、审核、批量审核和批量删除
- 专栏查询、审核、批量审核和批量删除
- 评论查询、审核、批量审核和批量删除
- 图片查询、审核、批量审核和批量删除
- 审核状态及拒绝原因管理

#### 用户与权限

- 用户查询和状态管理
- 用户角色分配
- 菜单新增、编辑、启停和删除
- 角色新增、编辑和删除
- 角色菜单分配
- 权限新增、编辑和删除
- 角色权限分配
- 动态菜单与动态路由管理

#### 运营与日志

- 系统消息和通知管理
- 登录日志查询与删除
- 操作日志查询与删除
- 访客日志查询与删除
- 首页数据统计
- 图表数据展示
- 表格数据导出

### 后端支撑能力

- Spring Security 认证和接口权限控制
- 菜单、角色、权限组成的 RBAC 权限模型
- Redis 缓存、限流和临时状态管理
- RabbitMQ 异步通知和邮件任务
- WebSocket 私信及在线状态通信
- MinIO 图片对象存储
- 文章、专栏、评论和图片审核
- Spring AI 能力接入
- 定时任务调度
- 逻辑删除数据定期物理清理
- Actuator 服务健康检查

## 项目亮点

### 1. 权限列表缓存与缓存一致性

**问题**

权限列表同时依赖权限表和菜单表。列表、分页和搜索接口如果分别查询数据库，会产生重复查询；菜单被修改后，如果权限缓存没有及时失效，还可能返回已经过期的菜单信息。

**解决方案**

- 采用 Cache Aside 思路，将完整权限列表缓存到 Redis
- 缓存 key 为 `chen_stack:Permission:All`，默认有效期为 10 分钟
- 列表、分页、条件搜索和搜索分页统一复用同一份完整缓存
- 菜单新增、修改和删除后主动删除权限缓存
- 缓存内容类型异常或反序列化失败时，删除异常缓存并重新查询数据库
- Redis 读取异常时降级查询数据库，避免缓存故障直接影响权限列表接口

**实现效果**

权限相关查询统一使用一套缓存入口，同时兼顾了查询效率、菜单变更后的缓存一致性和 Redis 异常时的业务降级。

### 2. 用户角色查询 N+1 优化

**问题**

管理端用户列表需要展示每个用户的角色。如果先查询用户分页，再为当前页每个用户单独查询角色，用户数量增加后会形成典型的 N+1 查询问题。

**解决方案**

- 在用户分页结果返回前统一执行 `fillUserRoleCodes(...)`
- 一次批量查询当前页用户对应的 `sys_user_role`
- 再一次批量查询关联的 `sys_role`
- 在内存中组装用户与角色的对应关系
- 将角色编码统一回填到 `SysUserVo.roleCode`
- 前端直接使用返回的角色编码展示角色和判断操作权限

**实现效果**

将当前页原本可能产生的逐用户查询，收敛为固定的批量查询，减少了数据库访问次数，也让角色分配后的列表状态能够直接更新。

### 3. 动态菜单与路由实时同步

**问题**

管理端菜单由后端权限数据动态生成。菜单启停、编辑、删除或角色权限发生变化后，如果前端只更新侧边栏而没有同步更新动态路由，可能出现菜单已经消失但页面仍可停留、旧路由残留或新菜单必须重新登录才能显示的问题。

**解决方案**

- 菜单配置变化后重新请求当前用户菜单
- 刷新菜单前先移除上一轮注册的动态路由
- 根据最新菜单数据重新注册动态路由
- 侧边栏与路由统一使用同一轮菜单数据生成
- 当前页面对应菜单失效时，自动跳转到 `/home`
- 角色调整后同步刷新当前登录用户的菜单权限

**实现效果**

菜单、侧边栏和动态路由能够在当前会话中同步更新，减少了旧路由残留和权限变化后必须重新登录的问题。

### 4. 业务删除与对象存储一致性处理

**问题**

文章和用户删除不仅涉及主表，还可能涉及评论、收藏、历史记录、专栏关系、Redis 缓存和 MinIO 图片。数据库事务可以回滚，但对象存储文件删除无法随数据库事务自动回滚，直接同时删除容易产生数据和文件不一致。

**解决方案**

- 删除主业务数据时同步清理关联关系和相关缓存
- 删除前收集文章封面、正文图片、头像、专栏封面和私信图片地址
- 删除文件前检查图片是否仍被其他文章、专栏、用户或私信引用
- 数据库事务成功提交后，再通过 `afterCommit` 执行 MinIO 文件清理
- MinIO 清理异常只记录日志，不反向回滚已经提交的数据库事务
- 用户删除后同步清理对应的 Redis 用户状态及会话数据

**实现效果**

避免了数据库事务回滚后文件已经被提前删除的问题，并降低了共享图片被误删、数据库残留无效关系和对象存储长期堆积无引用文件的风险。

### 5. 逻辑删除数据定期物理清理

**问题**

逻辑删除可以保留数据恢复能力，但被删除的数据仍然占用表和索引空间。系统长期运行后，逻辑删除数据不断积累，会增加数据库存储和查询维护成本。

**解决方案**

- 为 15 张逻辑删除表增加 `deleted_time`
- 使用数据库触发器记录逻辑删除时间
- 数据恢复时由触发器自动清空 `deleted_time`
- 新数据库初始化脚本直接包含最终表结构
- 为已有数据库提供 `sql/add_deleted_time_for_logic_delete.sql` 升级脚本
- 定时任务每天 `03:30` 按 `Asia/Shanghai` 时区执行
- 默认保留逻辑删除数据 7 天
- 每次按 500 条分批执行物理删除
- 按 `deleted_time` 和 `id` 排序，优先清理最早删除的数据
- 只操作代码中定义的固定表白名单
- 不自动删除 `deleted_time IS NULL` 的旧历史数据
- 支持通过环境变量关闭任务或调整保留天数、批量大小和 Cron 表达式

**实现效果**

逻辑删除数据具备了可追踪、可配置和可分批执行的清理机制，同时避免一次性删除大量数据形成长事务，并对旧历史数据采取保守保护策略。

### 6. AI 调用限流、额度与内容去重

**问题**

AI 接口通常存在调用成本和频率限制。文章摘要、标题生成和智能客服等功能如果没有调用边界，可能因为短时间高频点击、重复内容提交或接口被滥用而产生不必要的调用。

**解决方案**

- 通过 Spring AI 统一接入 AI 服务
- 在 AI 接口增加限流注解
- 使用 Redis 记录用户每日 AI 使用次数
- 对不同 AI 功能设置调用额度
- 使用 Redis 保存内容去重状态
- 对短时间内提交的相同内容进行重复调用拦截
- 将摘要、标题、标签、回复建议和客服等能力划分为不同业务入口

**实现效果**

AI 功能具备了接口频率限制、每日额度管理和重复内容控制，减少了无效请求，并为第三方 AI 服务的调用成本提供了业务边界。

### 7. 内容与图片审核状态同步

**问题**

文章、专栏、评论、图片和私信消息分别存储在不同业务表中。当图片审核被拒绝或图片被删除后，如果只更新图片表，私信消息或其他业务页面仍可能继续展示已经失效的图片。

**解决方案**

- 为文章、专栏、评论和图片建立统一审核状态
- 私信图片上传后复用现有图片审核流程
- 图片审核结果同步更新对应私信图片消息
- 图片审核拒绝后撤回相关私信图片消息
- 图片删除时同步处理消息记录中的图片展示状态
- 管理端提供单条审核和批量审核
- 用户端根据审核结果控制内容可见范围

**实现效果**

审核结果能够在图片记录、私信消息和前台展示之间同步，减少了审核拒绝后内容仍然可见或不同页面状态不一致的问题。

## 后端技术栈

| 技术 | 版本 | 项目用途 |
| --- | --- | --- |
| Java | 21 | 后端主要开发语言 |
| Spring Boot | 3.4.0 | REST API、配置管理、任务调度和健康检查 |
| Spring Security | Spring Boot 依赖管理 | 登录认证、权限控制和访问隔离 |
| MyBatis-Plus | 3.5.14 | 数据访问、分页查询和逻辑删除 |
| MySQL | 8.0 | 主业务数据库 |
| Redis | 7 | 权限缓存、登录状态、限流和 AI 调用限制 |
| RabbitMQ | 3 | 异步通知和邮件任务 |
| MinIO Java SDK | 8.3.6 | 业务图片对象存储 |
| Spring WebSocket | Spring Boot 依赖管理 | 私信及在线状态实时通信 |
| Spring AI | 1.0.0-M5 | AI 能力接入 |
| 阿里云内容安全 | 2.0.6 | 图片内容审核 |
| Lombok | 1.18.38 | 简化实体类和配置类代码 |
| Hutool | 5.8.38 | 常用工具和数据处理 |
| Fastjson | 2.0.50 | 部分 JSON 数据处理 |
| EasyCaptcha | 1.6.2 | 登录验证码生成 |

## 前端技术栈

### 用户端（chen-stack-user）

| 技术 | 版本 | 项目用途 |
| --- | --- | --- |
| Vue | 3.5.13 | 用户端页面开发 |
| vite-plus | 0.1.15 | 本地开发与生产构建 |
| Element Plus | 2.10.2 | 表单、弹窗和列表组件 |
| Pinia | 3.0.1 | 用户及页面状态管理 |
| Vue Router | 4.5.0 | 用户端路由管理 |
| Axios | 1.10.0 | HTTP 接口请求 |
| AiEditor | 1.4.0 | 文章富文本编辑 |

### 管理端（chen-stack-admin）

| 技术 | 版本 | 项目用途 |
| --- | --- | --- |
| Vue | 3.5.13 | 管理端页面开发 |
| vite-plus | 0.1.15 | 本地开发与生产构建 |
| Element Plus | 2.10.5 | 表格、表单、弹窗和交互组件 |
| Pinia | 3.0.1 | 登录态、菜单和页面状态管理 |
| Vue Router | 4.5.0 | 静态路由和动态路由管理 |
| Axios | 1.11.0 | HTTP 接口请求 |
| @antv/g2 | 5.4.8 | 管理端统计图表 |
| XLSX | 0.18.5 | 表格数据导出与处理 |

## 项目结构

```text
zcx-chen-stack/
├── chen-stack-backend/
│   ├── src/main/java/com/zcx/chenstack/
│   │   ├── aspect/                 # 限流、日志等切面
│   │   ├── config/                 # 系统和业务配置
│   │   ├── controller/             # REST API 控制器
│   │   ├── domain/                 # entity、dto、vo、enums、constants
│   │   ├── exception/              # 业务异常和全局异常处理
│   │   ├── mapper/                 # MyBatis-Plus Mapper
│   │   ├── minio/                  # MinIO 配置
│   │   ├── rabbitmq/               # 消息队列配置与监听
│   │   ├── redis/                  # Redis 缓存组件
│   │   ├── security/               # 登录认证和权限控制
│   │   ├── service/                # 业务服务
│   │   ├── task/                   # 定时任务
│   │   ├── utils/                  # 通用工具类
│   │   └── websocket/              # WebSocket 通信
│   ├── src/main/resources/
│   │   ├── mapper/                 # MyBatis XML
│   │   └── application.yaml        # 后端配置
│   ├── Dockerfile
│   └── pom.xml
├── chen-stack-frontend/
│   ├── chen-stack-admin/           # 管理端
│   │   ├── src/
│   │   ├── Dockerfile
│   │   └── package.json
│   └── chen-stack-user/            # 用户端
│       ├── src/
│       ├── Dockerfile
│       └── package.json
├── script/
│   └── dev/
│       ├── .env.example
│       ├── docker-compose.yml          # 完整环境编排
│       ├── docker-compose-service.yml  # MySQL、Redis、RabbitMQ、MinIO
│       ├── docker-compose-apps.yml     # 后端、用户端、管理端
│       ├── start.bat
│       └── start.sh
├── sql/
│   ├── chen_stack.sql
│   └── add_deleted_time_for_logic_delete.sql
├── Jenkinsfile
├── nginx_default.conf
└── README.md
```

## 快速部署

### 1. 环境要求

从全新克隆的仓库开始部署，需要准备：

- Git
- Docker
- Docker Compose
- JDK 21
- Maven
- Node.js
- npm

当前后端 Dockerfile 会复制本地 `target` 目录中的 JAR，两个前端 Dockerfile 会复制本地 `dist` 目录中的构建产物。因此首次部署前，需要先在宿主机完成后端和前端构建。

### 2. 克隆项目

```bash
git clone https://github.com/zcxzzz188/zcx-chen-stack.git
cd zcx-chen-stack
```

### 3. 创建环境变量文件

Linux 或 macOS：

```bash
cp script/dev/.env.example script/dev/.env
```

Windows CMD：

```cmd
copy script\dev\.env.example script\dev\.env
```

打开 `script/dev/.env`，根据本地环境填写或核对：

- MySQL 配置
- Redis 配置
- RabbitMQ 配置
- MinIO 配置
- 邮件配置
- AI 服务配置
- 内容审核配置
- 前后端访问地址

真实 `.env` 包含运行凭据，不应提交到 Git 仓库。

### 4. 构建后端

Linux 或 macOS：

```bash
cd chen-stack-backend
mvn -DskipTests package
cd ..
```

Windows CMD：

```cmd
cd chen-stack-backend
mvn.cmd -DskipTests package
cd ..
```

构建完成后应生成：

```text
chen-stack-backend/target/zcx-chen-stack-backend-1.0-SNAPSHOT.jar
```

### 5. 构建用户端

Linux 或 macOS：

```bash
cd chen-stack-frontend/chen-stack-user
npm install
npm run build
cd ../..
```

Windows CMD：

```cmd
cd chen-stack-frontend\chen-stack-user
npm install
npm run build
cd ..\..
```

### 6. 构建管理端

Linux 或 macOS：

```bash
cd chen-stack-frontend/chen-stack-admin
npm install
npm run build
cd ../..
```

Windows CMD：

```cmd
cd chen-stack-frontend\chen-stack-admin
npm install
npm run build
cd ..\..
```

### 7. 完整启动

在仓库根目录执行：

```bash
docker compose --env-file script/dev/.env -f script/dev/docker-compose.yml up -d --build
```

完整编排会启动：

- MySQL
- Redis
- RabbitMQ
- MinIO
- Spring Boot 后端
- 用户端
- 管理端

全新 MySQL 数据卷会自动执行 `sql/chen_stack.sql` 完成数据库初始化。

### 8. 拆分启动

需要单独管理基础服务和应用服务时，可以先启动基础服务：

```bash
docker compose --env-file script/dev/.env -f script/dev/docker-compose-service.yml up -d
```

再启动应用服务：

```bash
docker compose --env-file script/dev/.env -f script/dev/docker-compose-apps.yml up -d --build
```

`docker-compose-apps.yml` 使用外部网络 `chen-stack-network`，该网络由 `docker-compose-service.yml` 创建，因此拆分启动时必须先启动基础服务。

### 9. 数据库脚本说明

| 脚本 | 用途 |
| --- | --- |
| `sql/chen_stack.sql` | 全新数据库初始化 |
| `sql/add_deleted_time_for_logic_delete.sql` | 已有旧数据库升级逻辑删除清理结构 |

全新部署只需要执行 `sql/chen_stack.sql`。使用完整 Docker Compose 并创建全新 MySQL 数据卷时，该脚本会自动执行。

### 10. 服务地址

实际端口以 `script/dev/.env` 为准。按照当前示例配置，常用地址如下：

| 服务 | 地址 |
| --- | --- |
| 后端 API | `http://localhost:5000` |
| 用户端 | `http://localhost:7000` |
| 管理端 | `http://localhost:8000` |
| MySQL | `localhost:3308` |
| Redis | `localhost:6379` |
| MinIO API | `http://localhost:9000` |
| MinIO 控制台 | `http://localhost:9001` |
| RabbitMQ | `localhost:5672` |
| RabbitMQ 管理界面 | `http://localhost:15672` |

### 11. 查看运行状态

```bash
docker compose --env-file script/dev/.env -f script/dev/docker-compose.yml ps
```

查看后端日志：

```bash
docker logs -f chen-stack-backend
```

查看用户端日志：

```bash
docker logs -f chen-stack-user
```

查看管理端日志：

```bash
docker logs -f chen-stack-admin
```

拆分启动时也可以分别查看：

```bash
docker compose --env-file script/dev/.env -f script/dev/docker-compose-service.yml ps
docker compose --env-file script/dev/.env -f script/dev/docker-compose-apps.yml ps
```

### 12. 停止服务

完整启动方式：

```bash
docker compose --env-file script/dev/.env -f script/dev/docker-compose.yml down
```

拆分启动方式：

```bash
docker compose --env-file script/dev/.env -f script/dev/docker-compose-apps.yml down
docker compose --env-file script/dev/.env -f script/dev/docker-compose-service.yml down
```

默认不会删除 MySQL、Redis、RabbitMQ 和 MinIO 的持久化数据卷。需要删除数据卷时，应确认数据不再需要后再执行带有 `-v` 的命令。

## 构建与验证

| 验证项 | 当前结果 |
| --- | --- |
| 后端 Maven 打包 | 通过 |
| 用户端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| Docker 镜像构建 | 通过 |
| Docker 容器启动 | 通过 |
| 后端健康检查 | HTTP 200 |
| 权限 Redis 缓存验证 | 通过 |
| 逻辑删除触发器回滚测试 | 通过 |
| 物理清理 SQL 回滚测试 | 通过 |

以上结果来自当前本地开发环境验证，不代表已经完成生产环境压力测试或完整自动化测试。