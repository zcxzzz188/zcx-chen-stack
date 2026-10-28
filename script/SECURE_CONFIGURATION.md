# 安全部署配置

Compose 和后端不提供 MySQL、MinIO、Redis 密码或 JWT 签名密钥的可用默认值。必须从受限的外部环境文件显式提供；`.env.example` 中的空值不能用于启动。账号名称、签名算法、端口和数据卷沿用现有部署。

基础及拆分 Compose 的 Redis 均读取受限 `redis.conf`、`users.acl` 和只读密码文件。基础文件的模板见 `dev/redis-secure/README.md`；拆分部署还须显式提供 `REDIS_SECURE_CONFIG_DIRECTORY`、`REDIS_HEALTH_PASSWORD_FILE` 和 `REDIS_USERNAME`。不要重新引入 `--requirepass`、`redis-cli -a` 或密码命令行参数。

生产环境沿用其完整 Compose 文件链及有效的凭据覆盖层。新的受限凭据环境文件应作为最后一个 `--env-file`，覆盖层应保持原镜像、挂载、端口和网络。不要覆盖正式 `.env`，也不要将渲染后的完整配置写入普通日志。缺少秘密时应让验证失败。

数据库备份脚本支持显式的 `CHEN_CREDENTIAL_ENV_FILE`，在原 `.env` 后读取受限轮换凭据文件，且不修改原文件。人工入口和定时任务均须设置同一路径；不要继续用旧 `.env` 中已失效的密码。脚本通过标准输入将密码传给容器内客户端环境，不将其放入命令参数。生产备份和回退材料应放在受限外部目录。

移除当前源码中的默认值不能撤销 Git 历史中的秘密。历史值必须按实际消费者受控轮换；MySQL 账号变更后不可仅修改容器环境变量代替真实数据库密码变更。JWT 换钥会使既有登录态失效，需要重新登录。

MySQL 重启会使 RAG 目录启动时持有的连接和会话锁失效；后端 health=UP 不能替代目录就绪验证。数据库稳定后，使用原运行 JAR、现行新凭据和既有只读恢复配置恢复后端，再验证已认证的 `/ai/customer-service/status` HTTP/业务码均为 200、`ragAvailability=CONFIGURED`，并核对来源状态和完整索引指纹。禁止绕过目录 ownership 校验、开启发布或重新授权旧快照；仓库配置提交与运行 JAR 身份应分别记录，不因 Git 更新而改写恢复 manifest。

Qdrant 网络等待可用 `CHEN_RAG_QDRANTTIMEOUTSECONDS` 配置 `chen.rag.qdrant-timeout-seconds`，现有代码允许 1–30 秒，本轮验证为 15 秒。网络等待调整不改变 TLS、原 CA、主机名、权限及完整索引校验；当前和回滚入口必须使用一致的有效设置。
