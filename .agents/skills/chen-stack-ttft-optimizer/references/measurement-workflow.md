# 测量与验收

## 可归因基线

- 分场景建立不含个人信息的固定输入：FAQ、RAG、Wiki、Hybrid、页面导航。记录场景、知识版本/索引快照、模式、模型、温度、有效 prompt/tool 配置、缓存状态与运行时间。
- 先预热 JVM、连接池和 HTTP 连接，再串行运行；每次用新的 chatId，避免历史消息累积改变 prompt。基线与候选必须使用同一组请求和数据状态。若数据或配置变化，另开一组对照。
- 采集原始每轮数据。P50/P95 使用同一统计口径；小样本的 P95 只作近似，不把单次最快值当成结论。记录失败、重试、取消和超时，不从统计中静默删除慢请求。
- 依赖调用次数、DeepSeek Token 必须来自同一请求的 trace/模型响应 usage。用户可见 SSE 不含这些数据时，从 Spring AI observation、provider usage 或受控测试 trace 取得；缺少证据时明确标为 unavailable。

## 时序口径

给每个测试请求一个仅用于 trace 的 request id，并记录单调时钟下的起止时间。至少拆分：

1. HTTP 到达与认证/鉴权完成；
2. 意图/模式分类；
3. Embedding 请求；
4. Qdrant 请求；
5. RAG 与 Wiki 的目录/正文检索；
6. 每次 Tool Calling 的模型等待与工具执行；
7. prompt 组装；
8. DeepSeek 请求发出、首个非空答案正文、请求结束；
9. 服务端首个答案正文事件及客户端 SSE 首正文；
10. SSE 正常结束及完整响应耗时。

“首个模型 token”不能用工具调用参数、空 delta 或推理内容冒充。DeepSeek 首个答案文本、应用生成的正文 chunk、SSE 首正文分开记录。若当前 provider observation 只给整次调用耗时，用 trace 中首个 answer delta 补齐，不新增重复的整请求计时器。

## Spring AI 观测

先检查当前 Spring AI 2.0.2 和 Spring Boot Actuator 配置及实际导出的 observation。优先复用 ChatClient/ChatModel、EmbeddingModel 与 VectorStore 的 observations。辰栈的 Qdrant 或 Wiki 若走自有 gRPC/JDBC 路径，不假设它自动进入 Spring AI span；仅对缺失阶段加低基数 Observation。模式、组件类型可作低基数标签；requestId、traceId 只放 trace，不放 metric 标签。禁止记录 prompt、回答、凭据、用户 ID 或个人资料。

## 优化顺序与正确性闸门

1. 依据调用图标出依赖边，判断每步是否真正串行，以及后一步是否消费前一步结果。
2. 先验证能否删除重复分类/路由/总结请求；Hybrid 和导航的三次模型请求优先排查。工具调用后的必要续答、知识取证、权限检查和来源验证不能仅因耗时而删除。
3. 只有在结果互不依赖、资源预算允许且真实时间线显示可重叠时才并发。不要用更多 DeepSeek 请求掩盖串行链路。
4. 每轮只改变一组可归因因素。记录代码差异、测试命令、模型调用数、输入/输出 Token、TTFT、P50、P95 和完整耗时。无稳定收益即回退本轮。
5. 用相同场景验证命中内容、版本、引用 URL/标题、RBAC、拒绝越权、工具副作用、完整回答及 SSE 终态。速度收益不能抵消证据、权限或 G6 功能回归。

## 输出

将基线与候选原始样本和报告写入 `.work/`，报告至少包含：环境/数据指纹、匿名场景、样本量、配置、逐阶段时序、调用/Token 证据、P50/P95/完整耗时、正确性检查、变更组、失败样本、结论与未验证项。禁止在报告写入真实 prompt、Authorization、API Key、用户标识或聊天内容。

## 方法来源

参考 [performance-profiler](https://github.com/JayRHa/agent-skills/tree/main/skills/performance-profiler) 的测量驱动原则。它是第三方内容；只采纳可独立核验的方法，不运行或复制其脚本。Spring AI 的版本化观测说明见 [Spring AI 2.0 Observability](https://docs.spring.io/spring-ai/reference/2.0/observability/index.html)。
