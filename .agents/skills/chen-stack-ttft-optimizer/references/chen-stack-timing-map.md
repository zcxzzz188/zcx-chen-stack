# 辰栈客服时序定位

此图是定位入口，不是固定实现承诺。先核对当前分支源码与调用关系；工作区中的客服代码可能尚未提交，不能依据旧索引或旧行号下结论。

## 起点与依赖

- HTTP/SSE 入口：`chen-stack-backend/src/main/java/com/zcx/chenstack/controller/AiController.java`
- 客服编排、模式分支、tool loop、prompt、流式回答和记忆：`chen-stack-backend/src/main/java/com/zcx/chenstack/rag/ProductRagAnswerService.java`
- Spring AI client/model 配置：`chen-stack-backend/src/main/java/com/zcx/chenstack/config/AiConfig.java`、`RagConfig.java`
- Embedding 限流/保护：`chen-stack-backend/src/main/java/com/zcx/chenstack/rag/GuardedEmbeddingModel.java`
- Qdrant 索引访问：`chen-stack-backend/src/main/java/com/zcx/chenstack/rag/QdrantKnowledgeIndex.java`
- MySQL 目录和知识快照：`chen-stack-backend/src/main/java/com/zcx/chenstack/rag/JdbcCatalogStore.java` 及当前实现的 `KnowledgeCatalog`。
- Wiki 检索/正文读取：从当前 `WikiViews`、Wiki 查询和发布实现沿实际调用边追踪，不把目录查询与正文取证合并计时。
- 客服 SSE 协议与客户端首正文：`chen-stack-frontend/chen-stack-user/src/components/CustomerService/customerServiceProtocol.js`、`CustomerServiceChat.vue`、`src/api/ai.js`。
- 回归测试：优先沿 `ProductRagAnswerServiceTest`、`AiControllerSecurityTest` 和当前客服 SSE/Vitest 测试追踪；先检查现有工作区差异再运行或改写。

## 逐段取证

正式客服事件接口记录 `CUSTOMER_SERVICE_TIMING`，用服务端生成的 `request_id` 关联阶段。每行 `start_us` 和 `end_us` 都以本次 JWT 认证开始为原点。`ROUTE` 可包含分类阶段，`RAG_RETRIEVAL` 可包含 `EMBEDDING` 与 `QDRANT_SEARCH`，不要把父阶段和子阶段相加。

- `AUTH` 在 JWT 校验边界结束。`SSE_FIRST_TEXT` 在控制器映射首个非空 `TEXT` 事件时记录，不包括客户端网络和渲染时间。
- `MODEL_REQUEST` 记录 Spring AI 流调用的开始到结束，`PROVIDER_FIRST_TEXT` 单独记录每轮收到的首个非空模型正文。同一 `model_round` 属于同一次逻辑流调用。HTTP 实际发送次数仍以 Provider 账本为准。
- `STREAM_COMPLETE` 输出有效模式、实际分支、终止状态、已发起的模型流数和模型报告的 Token 合计。某轮缺失用量时合计为 `null`。零模型流记录 0 Token。认证被拒绝时不记录 SSE 首正文。
- 当前正式知识库 Hybrid 先检索 Wiki，再在 Wiki 无命中时执行 RAG。Qdrant 回调会记录实际完成时间，用同一请求 ID 关联同步检索。

1. 从 controller 跟到服务实现，确认认证在 Spring Security filter 中完成的范围、SSE 编码方式、客户端判定正文的事件类型。
2. 在编排服务中标出每次 ChatClient/ChatModel 调用。把模式/意图判定、工具调用请求、工具执行后续答分别标记；相邻的模型请求不能只根据源代码相邻就认定多余。
3. 对每条知识路径确认 Embedding → Qdrant、MySQL catalog、Wiki list/read 的实际依赖。混合检索只有在两侧无需对方结果且语义/一致性合同允许时才能并行。
4. 对缺乏自动观测的自有 Qdrant gRPC、JDBC 和 SSE 序列化边界，增加窄范围 Observation 或测试计时；优先不改公开接口和持久化模型。
5. 对照 controller SSE 事件和前端 parser，确认 `首 Token` 指答案正文，`SSE 首正文` 指客户端可见正文。工具卡片、来源列表、状态、错误事件不算正文。

## 安全与版本

当前仓库基线使用 Spring Boot 4.1.x、Spring AI 2.0.x；以 `chen-stack-backend/pom.xml` 和运行 profile 为准。开发/生产模型可能通过 OpenAI-compatible 配置连接 DeepSeek，也可能在新版本直接使用 Spring AI DeepSeek client；不要仅凭 provider URL 或环境变量名推断模型能力、参数支持或 token 计量格式。

Spring AI prompt/completion observation 默认不应为压测打开正文日志。不要在 span/metric 上加入 message、userId、chatId 等高基数或敏感字段。
