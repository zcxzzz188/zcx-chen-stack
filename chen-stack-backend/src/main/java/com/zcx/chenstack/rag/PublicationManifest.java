package com.zcx.chenstack.rag;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将发布范围绑定到构建身份、原始资源字节和逐主题正文，不授予发布权限。 */
public record PublicationManifest(int schemaVersion, String gitCommit, String artifactSha256,
                                  String environment, String collection, String embeddingSignature,
                                  Map<String, String> resourceSha256, long expectedGeneration,
                                  String expectedSnapshotSha256, Operation operation, String revokeSourceId,
                                  boolean initializeEmptyCatalog, boolean createCollection, List<Topic> topics) {
    public static final String PLATFORM_HELP_RESOURCE = "knowledge/platform-help.json";
    public static final String CAPABILITIES_RESOURCE = "knowledge/chenstack-product-capabilities.json";
    public static final Set<String> RESOURCE_PATHS = Set.of(PLATFORM_HELP_RESOURCE, CAPABILITIES_RESOURCE);
    private static final Set<String> USER_SOURCE_IDS = Set.of(
            "help-web-entry", "help-register", "help-login", "help-register-password", "help-password-reset",
            "help-article-publish", "help-article-edit", "help-article-manage", "help-search", "help-comment",
            "help-favorite", "help-profile", "help-notifications", "help-ai-features", "help-column-create",
            "help-column-articles", "help-column-manage", "help-column-view", "help-article-draft",
            "help-article-visibility", "help-article-recycle", "help-comment-manage", "help-like", "help-follow",
            "help-favorite-manage", "help-password-change", "help-email-change", "help-email-settings",
            "help-private-message", "help-private-revoke", "help-private-conversation", "help-creator-dashboard",
            "help-upload", "help-ai-tools", "help-about-contact", "help-discover", "help-session-logout",
            "help-ai-quota", "help-search-limits");
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public PublicationManifest {
        resourceSha256 = resourceSha256 == null ? null : Map.copyOf(resourceSha256);
        topics = topics == null ? null : List.copyOf(topics);
    }

    public enum Operation { PUBLISH, RECOVER, REVOKE }

    public record Topic(String sourceId, String version, String textSha256) { }

    /** build/artifact 必须来自运行包；generation/snapshot 必须来自实际持久目录。 */
    public record RuntimeIdentity(String buildGitCommit, String artifactSha256, String environment,
                                  String collection, String embeddingSignature, long generation,
                                  String snapshotSha256) { }

    public static PublicationManifest parse(byte[] bytes) {
        return readJson(bytes, PublicationManifest.class, 65_536);
    }

    /** 内容前置校验；不证明数据库代际，执行器仍须核对实际目录或已持久化的恢复检查点。 */
    public List<KnowledgeDocument> validateResources(RuntimeIdentity runtime, Map<String, byte[]> resources) {
        require(schemaVersion == 1 && validHash(artifactSha256) && gitCommit != null
                && gitCommit.matches("[0-9a-f]{40}") && validEnvironment(environment)
                && collection != null && collection.matches("chen_[a-z0-9_]{1,90}") && validHash(embeddingSignature)
                && expectedGeneration >= 0 && operation != null, "PUBLICATION_MANIFEST_INVALID");
        require(validHash(expectedSnapshotSha256)
                        || initializeEmptyCatalog && expectedGeneration == 0 && "EMPTY".equals(expectedSnapshotSha256),
                "PUBLICATION_SNAPSHOT_INVALID");
        require(!initializeEmptyCatalog || expectedGeneration == 0 && "EMPTY".equals(expectedSnapshotSha256),
                "PUBLICATION_SNAPSHOT_INVALID");
        require(runtime != null && gitCommit.equals(runtime.buildGitCommit())
                && artifactSha256.equals(runtime.artifactSha256()) && environment.equals(runtime.environment())
                && collection.equals(runtime.collection()) && embeddingSignature.equals(runtime.embeddingSignature()),
                "PUBLICATION_IDENTITY_MISMATCH");
        require(resourceSha256 != null && resourceSha256.keySet().equals(RESOURCE_PATHS)
                && resources != null && resources.keySet().equals(RESOURCE_PATHS), "PUBLICATION_RESOURCES_INVALID");
        for (String path : RESOURCE_PATHS) {
            byte[] bytes = resources.get(path);
            // 资源只接受原始字节哈希；不隐式更改 CRLF/LF、空格或 JSON 编码。
            require(bytes != null && bytes.length > 0 && bytes.length <= 2_000_000
                    && validHash(resourceSha256.get(path)) && sha256(bytes).equals(resourceSha256.get(path)),
                    "PUBLICATION_RESOURCE_MISMATCH");
        }
        KnowledgeDocument[] documents = readJson(resources.get(PLATFORM_HELP_RESOURCE), KnowledgeDocument[].class, 2_000_000);
        require(documents.length == 52, "PUBLICATION_DRAFTS_INVALID");
        Map<String, KnowledgeDocument> users = new LinkedHashMap<>();
        Set<String> allIds = new java.util.HashSet<>();
        for (KnowledgeDocument document : documents) {
            require(document != null && document.sourceId() != null && allIds.add(document.sourceId())
                    && "PLATFORM_HELP".equals(document.sourceType())
                    && "NOT_PUBLISHED".equals(document.visibility())
                    && "CONTENT_REVIEW_REQUIRED".equals(document.reviewStatus()), "PUBLICATION_DRAFTS_INVALID");
            if (!document.sourceId().startsWith("help-admin-") && !"help-history".equals(document.sourceId())) {
                require(document.text() != null && !document.text().isBlank() && safeText(document.version(), 40),
                        "PUBLICATION_DRAFTS_INVALID");
                users.put(document.sourceId(), document);
            }
        }
        require(allIds.contains("help-history") && users.keySet().equals(USER_SOURCE_IDS), "PUBLICATION_DRAFTS_INVALID");
        require(topics != null && topics.size() == USER_SOURCE_IDS.size(), "PUBLICATION_TOPICS_INVALID");
        Set<String> seen = new java.util.HashSet<>();
        for (Topic topic : topics) {
            require(topic != null && topic.sourceId() != null && seen.add(topic.sourceId())
                    && users.containsKey(topic.sourceId()), "PUBLICATION_TOPICS_INVALID");
            KnowledgeDocument document = users.get(topic.sourceId());
            require(document.version().equals(topic.version()) && validHash(topic.textSha256())
                    && KnowledgeCatalog.hash(KnowledgeCatalog.normalize(document.text())).equals(topic.textSha256()),
                    "PUBLICATION_TOPIC_MISMATCH");
        }
        require(seen.equals(USER_SOURCE_IDS), "PUBLICATION_TOPICS_INVALID");
        require(operation == Operation.REVOKE
                        ? USER_SOURCE_IDS.contains(revokeSourceId == null ? "" : revokeSourceId)
                          && !initializeEmptyCatalog && !createCollection
                        : revokeSourceId == null,
                "PUBLICATION_OPERATION_INVALID");
        return List.copyOf(users.values());
    }

    /** 仅对首次执行使用；恢复须由执行器核对实际持久检查点，而非复制清单值充当运行状态。 */
    public List<KnowledgeDocument> validate(RuntimeIdentity runtime, Map<String, byte[]> resources) {
        List<KnowledgeDocument> documents = validateResources(runtime, resources);
        require(expectedGeneration == runtime.generation() && expectedSnapshotSha256.equals(runtime.snapshotSha256()),
                "PUBLICATION_CATALOG_STATE_MISMATCH");
        return documents;
    }

    public static Set<String> userSourceIds() {
        return USER_SOURCE_IDS;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", exception);
        }
    }

    static <T> T readJson(byte[] bytes, Class<T> type, int maximumBytes) {
        require(bytes != null && bytes.length > 0 && bytes.length <= maximumBytes, "PUBLICATION_JSON_INVALID");
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            T value = JSON.readValue(utf8, type);
            require(value != null, "PUBLICATION_JSON_INVALID");
            return value;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            // 不把审批正文、资源内容或原始 Jackson 异常带到日志。
            throw new SecurityException("PUBLICATION_JSON_INVALID");
        }
    }

    static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    static boolean validEnvironment(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,80}");
    }

    static boolean safeText(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new SecurityException(code);
    }
}
