package com.zcx.chenstack.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.rag.*;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RagProperties.class, AiKnowledgeProperties.class})
public class RagConfig {
    @Bean
    public ProductRagAnswerService productRagAnswerService(RagKnowledgeService knowledge,
            org.springframework.ai.openai.OpenAiChatModel model, org.springframework.ai.chat.memory.ChatMemory memory,
            ReleaseProviderBudget budget, AiKnowledgeProperties properties,
            org.springframework.beans.factory.ObjectProvider<com.zcx.chenstack.wiki.WikiPublicationService> wiki,
            @Value("${chen.rag.max-model-rounds:6}") int maxModelRounds) {
        return new ProductRagAnswerService(knowledge, org.springframework.ai.chat.client.ChatClient.create(model), memory,
                budget, properties, "wiki".equals(properties.knowledgeMode()) ? wiki.getIfAvailable() : null, maxModelRounds);
    }

    @Bean
    public KnowledgeCatalog knowledgeCatalog(ObjectMapper mapper, RagProperties properties,
            javax.sql.DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<RagPublicationConfiguration.Prepared> publication) throws java.io.IOException {
        var approved = publication.getIfAvailable();
        if (approved != null) {
            try (var input = new ClassPathResource("knowledge/platform-help.json").getInputStream()) {
                List<KnowledgeDocument> drafts = mapper.readValue(input, new TypeReference<List<KnowledgeDocument>>() {});
                return RagPublicationExecutor.openOrInitialize(dataSource, drafts, approved.manifest(), approved.grant(), approved.operationHash());
            }
        }
        if (properties.isEnabled()) {
            JdbcCatalogStore store = JdbcCatalogStore.open(dataSource);
            try { return KnowledgeCatalog.restore(store); }
            catch (RuntimeException error) { store.close(); throw error; }
        }
        try (var input = new ClassPathResource("knowledge/platform-help.json").getInputStream()) {
            return new KnowledgeCatalog(mapper.readValue(input, new TypeReference<List<KnowledgeDocument>>() {}));
        }
    }

    @Bean(destroyMethod = "close")
    public CatalogLifecycle catalogLifecycle(KnowledgeCatalog catalog) {
        return catalog.lifecycle();
    }

    @Bean(destroyMethod = "close")
    public RagKnowledgeService ragKnowledgeService(RagProperties properties, CatalogLifecycle lifecycle,
                                                   ReleaseProviderBudget budget) {
        GuardedEmbeddingModel embedding = null;
        QdrantKnowledgeIndex index = null;
        if (properties.isEnabled() && !properties.getEmbeddingApiKey().isBlank()) {
            embedding = embedding(properties, budget);
            index = new QdrantKnowledgeIndex(properties, embedding);
        }
        return new RagKnowledgeService(properties, lifecycle, index, embedding);
    }

    /** 构造模型不发请求；不注册另一套 ChatModel，也不解除 Main 的自动配置排除。 */
    public static GuardedEmbeddingModel embedding(RagProperties properties, ReleaseProviderBudget budget) {
        properties.validate();
        Duration timeout = Duration.ofSeconds(properties.getEmbeddingTimeoutSeconds());
        var clientOptions = com.openai.core.ClientOptions.builder().baseUrl(properties.getEmbeddingBaseUrl())
                .apiKey(properties.getEmbeddingApiKey()).maxRetries(0)
                .httpClient(budget.httpClient(ReleaseProviderBudget.Kind.EMBEDDING, timeout,
                        properties.getEmbeddingBaseUrl().replaceAll("/+$", "") + "/embeddings")).build();
        var options = OpenAiEmbeddingOptions.builder().model(properties.getEmbeddingModel()).dimensions(1024)
                .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT).maxRetries(0).build();
        return new GuardedEmbeddingModel(OpenAiEmbeddingModel.builder()
                .openAiClient(new com.openai.client.OpenAIClientImpl(clientOptions)).metadataMode(MetadataMode.NONE)
                .options(options).build(), properties);
    }
}
