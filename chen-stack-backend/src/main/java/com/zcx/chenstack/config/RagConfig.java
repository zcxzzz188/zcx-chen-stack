package com.zcx.chenstack.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.rag.*;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {
    @Bean
    public ProductRagAnswerService productRagAnswerService(RagKnowledgeService knowledge,
            org.springframework.ai.openai.OpenAiChatModel model, org.springframework.ai.chat.memory.ChatMemory memory) {
        return new ProductRagAnswerService(knowledge, org.springframework.ai.chat.client.ChatClient.create(model), memory);
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
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        var api = OpenAiApi.builder().baseUrl(properties.getEmbeddingBaseUrl()).embeddingsPath("/embeddings")
                .apiKey(properties.getEmbeddingApiKey()).restClientBuilder(RestClient.builder().requestFactory(factory)
                        .requestInterceptor(budget.restInterceptor(ReleaseProviderBudget.Kind.EMBEDDING)))
                .responseErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
                    @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                        return response.getStatusCode().isError();
                    }
                    @Override public void handleError(org.springframework.http.client.ClientHttpResponse response) {
                        throw new RagFailure(RagFailure.Code.EMBEDDING_UNAVAILABLE);
                    }
                }).build();
        var options = OpenAiEmbeddingOptions.builder().model(properties.getEmbeddingModel()).dimensions(1024).build();
        return new GuardedEmbeddingModel(new OpenAiEmbeddingModel(api, MetadataMode.NONE, options,
                RetryTemplate.builder().maxAttempts(1).build()), properties);
    }
}
