package com.zcx.chenstack.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 统一知识文档通道配置。
 *
 * <p>与 RAG 平台帮助配置完全分离：默认关闭，需显式开启；使用独立 Qdrant 集合与独立私有
 * MinIO Bucket，避免与公开图片桶混用。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chen.knowledge")
public class KnowledgeDocProperties {
    /** 总开关；关闭时不注册任何 bean、不迁移、不访问对象存储。 */
    private boolean enabled = false;
    /** 文档向量集合；必须匹配 chen_[a-z0-9_]。 */
    private String collection = "chen_knowledge_docs_v1";
    /** 私有原件 Bucket；不得与 minio.bucketName 相同。 */
    private String bucket = "chen-knowledge-private";
    /** 是否允许启动时检查（不创建）私有 Bucket。 */
    private boolean verifyBucketOnStartup = true;
    /** 下载流的最大字节，防代理层大响应。 */
    private int maxFileBytes = 2 * 1024 * 1024;
    /** 单篇文档允许的最大 Embedding 调用次数。 */
    private int maxEmbeddingRequests = 64;
    /** 检索返回的来源数。 */
    private int topK = 4;
    /** 检索候选块数。 */
    private int chunkCandidateLimit = 32;
    /** 相似度阈值，下限 0.65 与旧通道一致。 */
    private double similarityThreshold = 0.65;
    /** 证据上下文预算（UTF-8 字节），与旧通道口径一致。 */
    private int contextByteBudget = 6000;
    /** 允许的扩展名（小写、不带点）。 */
    private Set<String> allowedExtensions = Set.of("md", "markdown", "txt");
    /** AI 分析时送入模型的最大正文字符数；默认与分析上限一致。 */
    private int analysisMaxInputChars = 20000;
    /** AI 分析单次调用的最大输出 token。 */
    private int analysisMaxOutputTokens = 6144;
    /** 单次分析最多保留的候选知识条数。 */
    private int analysisMaxCandidates = 8;

    public void validate() {
        if (!enabled) {
            return;
        }
        if (collection == null || !collection.matches("chen_[a-z0-9_]{1,90}")) {
            throw new IllegalArgumentException("knowledge.collection 必须匹配 chen_[a-z0-9_]{1,90}");
        }
        if (bucket == null || bucket.isBlank() || bucket.contains("/") || bucket.contains(".")
                || bucket.length() > 63 || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("knowledge.bucket 必须是合法的私有存储桶名");
        }
        if (maxFileBytes < 1024 || maxFileBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("knowledge.max-file-bytes 需在 1KiB~8MiB 之间");
        }
        if (maxEmbeddingRequests < 1 || maxEmbeddingRequests > 1000) {
            throw new IllegalArgumentException("knowledge.max-embedding-requests 需在 1~1000 之间");
        }
        if (topK < 1 || topK > 10 || chunkCandidateLimit < topK || chunkCandidateLimit > 64) {
            throw new IllegalArgumentException("knowledge.top-k / chunk-candidate-limit 无效");
        }
        if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0.65 || similarityThreshold > 1) {
            throw new IllegalArgumentException("knowledge.similarity-threshold 不得低于 0.65");
        }
        if (contextByteBudget < 256 || contextByteBudget > 8000) {
            throw new IllegalArgumentException("knowledge.context-byte-budget 需在 256~8000 之间");
        }
        if (allowedExtensions == null || allowedExtensions.isEmpty()
                || allowedExtensions.stream().anyMatch(ext -> ext == null || !ext.matches("[a-z0-9]{2,10}"))) {
            throw new IllegalArgumentException("knowledge.allowed-extensions 无效");
        }
        if (analysisMaxInputChars < 1000 || analysisMaxInputChars > KnowledgeChunkerLimit.MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("knowledge.analysis-max-input-chars 需在 1000~20000 之间");
        }
        if (analysisMaxOutputTokens < 512 || analysisMaxOutputTokens > 8192) {
            throw new IllegalArgumentException("knowledge.analysis-max-output-tokens 需在 512~8192 之间");
        }
        if (analysisMaxCandidates < 1 || analysisMaxCandidates > 12) {
            throw new IllegalArgumentException("knowledge.analysis-max-candidates 需在 1~12 之间");
        }
    }

    /** 避免配置类反向依赖业务包，仅声明与解析上限一致的本地常量。 */
    private static final class KnowledgeChunkerLimit {
        private static final int MAX_DOCUMENT_CHARS = 20000;
    }

    /** 该扩展名是否被允许（小写比较）。 */
    public boolean allowsExtension(String extension) {
        return extension != null && allowedExtensions.contains(extension.toLowerCase(java.util.Locale.ROOT));
    }
}
