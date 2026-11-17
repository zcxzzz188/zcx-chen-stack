package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.config.KnowledgeDocProperties;
import com.zcx.chenstack.config.RagConfig;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 统一知识文档通道的装配。
 *
 * <p>默认关闭；只有 {@code chen.knowledge.enabled=true} 时才注册 bean。
 * 复用 RAG 的 Embedding 配置（同一模型与同一签名口径），但使用独立 Qdrant 集合与独立私有桶。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KnowledgeDocProperties.class)
public class KnowledgeDocConfiguration {

    /** 文档通道专用的 Qdrant 客户端；与平台帮助通道共用连接参数、各自持有连接。 */
    @Bean(destroyMethod = "close")
    public QdrantClient knowledgeDocQdrantClient(RagProperties rag) {
        var builder = QdrantGrpcClient
                .newBuilder(rag.getQdrantHost(), rag.getQdrantGrpcPort(), rag.isQdrantTls(), false)
                .withTimeout(Duration.ofSeconds(rag.getQdrantTimeoutSeconds()));
        if (!rag.getQdrantApiKey().isBlank()) {
            builder.withApiKey(rag.getQdrantApiKey());
        }
        return new QdrantClient(builder.build());
    }

    /**
     * 文档通道 Embedding。
     *
     * <p>注意：这与平台帮助通道是两个独立实例，因此“每进程每日上限”各自计数。
     * 单任务预算仍由 {@code knowledge_index_task} 的数据库账本强制，不依赖此计数器。</p>
     */
    @Bean
    public EmbeddingModel knowledgeDocEmbeddingModel(RagProperties rag,
                                                     ObjectProvider<ReleaseProviderBudget> budget) {
        rag.validate();
        ReleaseProviderBudget shared = budget.getIfAvailable();
        if (shared == null) {
            throw new IllegalStateException("KNOWLEDGE_EMBEDDING_BUDGET_REQUIRED");
        }
        return RagConfig.embedding(rag, shared);
    }

    /** 文档索引器：独立集合，复用同一 Embedding 模型口径。 */
    @Bean
    public KnowledgeDocIndexer knowledgeDocIndexer(KnowledgeDocProperties properties, RagProperties rag,
                                                   @org.springframework.beans.factory.annotation.Qualifier("knowledgeDocEmbeddingModel")
                                                   EmbeddingModel embedding,
                                                   QdrantClient knowledgeDocQdrantClient) {
        properties.validate();
        rag.validate();
        return new KnowledgeDocIndexer(properties, rag, embedding, knowledgeDocQdrantClient);
    }

    /**
     * 文档知识提炼模型：复用应用唯一的 Spring AI DeepSeek ChatModel 与费用/超时传输链。
     * 不注册新的 ChatModel，也不绕过 ReleaseProviderBudget。
     */
    @Bean
    public KnowledgeExtractionModel knowledgeExtractionModel(OpenAiChatModel chatModel,
            @Value("${spring.ai.openai.chat.options.model:deepseek-flash}") String modelId) {
        return new SpringAiKnowledgeExtractionModel(chatModel, modelId);
    }

    @Bean
    public KnowledgeCandidateExtractor knowledgeCandidateExtractor(KnowledgeExtractionModel model,
                                                                   KnowledgeDocProperties properties) {
        properties.validate();
        return new KnowledgeCandidateExtractor(model, properties.getAnalysisMaxCandidates(),
                properties.getAnalysisMaxInputChars(), properties.getAnalysisMaxOutputTokens());
    }

    /**
     * 启动时确保私有 Bucket 存在。
     *
     * <p>只在显式开启的文档通道下执行，且只创建空桶，不设置任何桶策略，
     * 因此不会把知识原件暴露为匿名可读。失败只告警，不阻断应用启动。</p>
     */
    @Bean
    public org.springframework.boot.ApplicationRunner knowledgeDocBucketBootstrap(
            KnowledgeDocProperties properties, PrivateObjectStore objects) {
        return args -> {
            if (!properties.isEnabled() || !properties.isVerifyBucketOnStartup()) {
                return;
            }
            try {
                objects.createBucketIfAbsent();
            } catch (RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(KnowledgeDocConfiguration.class)
                        .warn("知识私有存储桶初始化失败：{}", unavailable.getMessage());
            }
        };
    }
}
