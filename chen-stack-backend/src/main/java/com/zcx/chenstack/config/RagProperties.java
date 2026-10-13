package com.zcx.chenstack.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** RAG 与聊天 Provider 的配置及成本边界完全分离。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chen.rag")
public class RagProperties {
    private boolean enabled = false;
    private String embeddingApiKey = "";
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String embeddingModel = "text-embedding-v4";
    private int dimensions = 1024;
    private int embeddingTimeoutSeconds = 10;
    private int maxEmbeddingInputChars = 1000;
    private int maxEmbeddingRequestsPerDay = 1000;
    private String qdrantHost = "127.0.0.1";
    private int qdrantGrpcPort = 6334;
    private boolean qdrantTls = false;
    private String qdrantApiKey = "";
    private int qdrantTimeoutSeconds = 5;
    private String collection = "chen_platform_help_v1";
    private int topK = 4;
    private double similarityThreshold = 0.65;
    private int contextTokenBudget = 2400;
    private int chatTimeoutSeconds = 60;

    public void validate() {
        if (dimensions != 1024 || topK < 1 || topK > 10 || contextTokenBudget < 256
                || contextTokenBudget > 8000 || maxEmbeddingInputChars < 400 || maxEmbeddingInputChars > 1000
                || maxEmbeddingRequestsPerDay < 1 || maxEmbeddingRequestsPerDay > 100000
                || embeddingTimeoutSeconds < 1 || embeddingTimeoutSeconds > 60
                || qdrantTimeoutSeconds < 1 || qdrantTimeoutSeconds > 30
                || chatTimeoutSeconds < 1 || chatTimeoutSeconds > 120
                || !Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1
                || collection == null || !collection.matches("chen_[a-z0-9_]{1,90}")
                || embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("RAG配置无效：需要1024维向量及受限检索预算");
        }
        java.net.URI uri = java.net.URI.create(embeddingBaseUrl);
        boolean local = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("Embedding连接配置无效");
        }
        if (qdrantGrpcPort < 1 || qdrantGrpcPort > 65535 || qdrantHost == null || qdrantHost.isBlank()
                || (!("127.0.0.1".equals(qdrantHost) || "localhost".equals(qdrantHost))
                    && (!qdrantTls || qdrantApiKey == null || qdrantApiKey.isBlank()))) {
            throw new IllegalArgumentException("远程Qdrant必须配置TLS和独立凭据");
        }
    }
}
