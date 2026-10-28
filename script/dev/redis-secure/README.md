# Redis 安全配置

基础 Compose 从只读文件加载 Redis 配置、ACL 和健康检查凭据，启动参数不传密码。

提供以下两个非秘密路径：

| Compose 变量 | 内容 | 缺省路径 |
|---|---|---|
| `REDIS_SECURE_CONFIG_DIRECTORY` | 受限目录，含 `redis.conf`、`users.acl` | 本目录 |
| `REDIS_HEALTH_PASSWORD_FILE` | 与应用 ACL 密码一致的受限秘密文件 | 本目录的 `redis-app.secret` |

建议使用仓库外、仅当前用户和系统管理员可访问的存储。文件以只读方式挂载，并设置 `create_host_path: false`。启动前再次检查配置、ACL 和秘密文件均为非空常规文件，覆盖平台绑定行为差异；缺失或空文件会使容器启动失败。健康检查也验证秘密文件类型及非空。

文件检查通过后，启动命令交回 Redis 镜像自带的 entrypoint，由它完成原有的数据目录权限处理和用户降权，不让 Redis 服务进程改为以 root 运行。

`redis.conf.example` 是非秘密模板。`users.acl.example` 只关闭默认用户，不提供可登录账号；实际 ACL 必须由受控配置提供，与 `REDIS_USERNAME` 保持一致，并包含强密码的 Redis 哈希。不要使用免密码用户，也不要把真实 ACL、密码文件或 `.env` 加入 Git。

后端仍由受限环境中的 `REDIS_USERNAME`、`REDIS_PASSWORD` 连接，密码必须显式提供。路径变量只选择文件位置，不替代密码配置；不要打印完整 Compose 渲染结果。

已经完成安全轮换的正式环境继续使用原项目、完整 Compose/env-file 链和已批准的 credential overlay。该 overlay 显式绑定现行受限文件，基础 Compose 的缺省路径不会覆盖它；本次源码修复不要求重建现有服务。应用回滚也必须保留现行凭据和完整安全绑定，不得直接恢复旧凭据快照。

这里的 `.gitignore` 保护默认位置的实际配置和秘密文件，示例及本说明可以提交。它不代替文件系统访问权限。
