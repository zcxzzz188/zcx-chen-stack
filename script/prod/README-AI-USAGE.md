# 正式 AI 持续使用

在原有部署 Compose 文件之后显式追加 `docker-compose-ai-usage.yml`，并将
`AI_USAGE_DIRECTORY` 设置为仓库之外、已创建且仅运维账户可访问的持久目录。
该 overlay 启用 `NORMAL_AI_USAGE=true`，将追加计量写入 `/ai-usage/events.jsonl`。
正常问答和编译不需要测试 grant、到期时间或每轮总调用上限；历史发布命令仍使用独立授权。

计量用途 `CHAT`、`COMPILATION`、`RETRIEVAL` 分开记录发送、HTTP 状态、异常和可取得的 Token。
Provider 未返回的费用与 Token 记为未知，不能当作零费用；提示词、正文和凭据不进入计量文件。
计量不可写或记录存在截断时阻止新增发送，不清空或覆盖文件。恢复时先保存和核对历史，禁止截断账本。

保留每日业务配额、速率限制、单次超时、并发保护、工具循环及单任务编译资源限制。
这些保护不依赖验收预算。管理员仍须授权来源并按原审核流程发布草稿。
旧账本、halt、失败任务及授权文件保留；正式持续模式不读取或复用它们。

独立 Wiki 实例使用 `AI_KNOWLEDGE_MODE=wiki`、`CHEN_WIKI_ENABLED=true`、
`CHEN_WIKI_PRODUCT_ENABLED=true`、`WIKI_PUBLIC_PORTAL_ENABLED=false`，关闭发布命令和恢复开关。
它可共享正式数据库的只读目录快照，不争用 RAG 写入所有权；来源、Wiki 发布和用户权限仍逐次从当前数据库核验。
若 canonical 目录内容更新，旧快照失败关闭，重启 Wiki backend 加载新快照后恢复。
独立实例使用单独的持久计量目录和 Redis，公开端口仅绑定 `127.0.0.1`。
运行文件与 Compose 放在受控部署目录，容器使用 `restart: unless-stopped`，无需依赖 `.work/`。

本 overlay 不执行迁移、索引更新、凭据轮换或 Wiki 发布。部署前保留当前镜像、配置和数据备份；升级后核对原 RAG 模式、来源代次与索引摘要。
