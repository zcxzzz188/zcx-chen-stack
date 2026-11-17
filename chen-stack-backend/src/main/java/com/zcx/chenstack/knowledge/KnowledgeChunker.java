package com.zcx.chenstack.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 统一知识文档的分块算法。
 *
 * <p>必须与 {@code com.zcx.chenstack.rag.KnowledgeCatalog} 的构造器逐字符等价：
 * 步长 320、窗口 400、代理对边界校正、UUIDv3 point id。任何改动都会改变已索引向量库的
 * point id，因此算法与 {@code chunk-v1} 版本串必须一起冻结。</p>
 *
 * <p>本类不依赖 Spring，也不访问数据库与网络，可在单测中作为参考实现。</p>
 */
public final class KnowledgeChunker {
    /** 分块算法版本；写入 knowledge_document_version.chunker_version 与 point id。 */
    public static final String VERSION = "chunk-v1";
    /** 起始偏移步长。 */
    static final int STRIDE = 320;
    /** 单块最大 UTF-16 窗口。 */
    static final int WINDOW = 400;
    /** 单块最大 UTF-16 长度：actualStart 可能前移一位，故为 WINDOW + 1。 */
    public static final int MAX_CHUNK_CHARS = WINDOW + 1;
    /** 单篇文档规范化后最大 UTF-16 长度，与数据库 CHECK 一致。 */
    public static final int MAX_DOCUMENT_CHARS = 20_000;
    /** 单批 Embedding 最多条数，与 GuardedEmbeddingModel 的上限一致。 */
    public static final int EMBEDDING_BATCH = 10;

    private KnowledgeChunker() {
    }

    /** 与 RAG 目录一致的规范化：CRLF/CR 统一为 LF，并去除首尾空白。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    /** 文档正文哈希；与旧目录 content_sha256 的口径一致。 */
    public static String contentHash(String rawText) {
        return sha256(normalize(rawText));
    }

    /** point id 的可重复计算：同一 (sourceKey, versionLabel, hash, ordinal) 必得同一 UUID。 */
    public static UUID pointId(String sourceKey, String versionLabel, String contentSha256, int ordinal) {
        String seed = sourceKey + ":" + versionLabel + ":" + contentSha256 + ":" + VERSION + ":" + ordinal;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** 每批最多 {@value #EMBEDDING_BATCH} 条，所需 Embedding 调用次数。 */
    public static int expectedEmbeddingCalls(int chunkCount) {
        if (chunkCount <= 0) {
            return 0;
        }
        return (chunkCount + EMBEDDING_BATCH - 1) / EMBEDDING_BATCH;
    }

    /**
     * 对已规范化的正文分块。调用方必须先 {@link #normalize} 并校验长度上限。
     *
     * @throws IllegalArgumentException 正文为空、超长或含非法参数时
     */
    public static List<Chunk> chunk(String sourceKey, String versionLabel, String normalizedText) {
        if (sourceKey == null || !sourceKey.matches("^(help|doc)-[a-z0-9_-]{1,64}$")) {
            throw new IllegalArgumentException("KNOWLEDGE_SOURCE_KEY_INVALID");
        }
        if (versionLabel == null || !versionLabel.matches("^[A-Za-z0-9._-]{1,40}$")) {
            throw new IllegalArgumentException("KNOWLEDGE_VERSION_LABEL_INVALID");
        }
        if (normalizedText == null || normalizedText.isBlank()) {
            throw new IllegalArgumentException("KNOWLEDGE_BODY_EMPTY");
        }
        if (normalizedText.length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("KNOWLEDGE_BODY_TOO_LONG");
        }
        String hash = sha256(normalizedText);
        List<Chunk> chunks = new ArrayList<>();
        int ordinal = 0;
        for (int start = 0; start < normalizedText.length(); start += STRIDE) {
            int end = Math.min(start + WINDOW, normalizedText.length());
            // UTF-16 分块不得拆开 Unicode 代理对
            if (end < normalizedText.length() && Character.isHighSurrogate(normalizedText.charAt(end - 1))) {
                end--;
            }
            int actualStart = start > 0 && Character.isLowSurrogate(normalizedText.charAt(start)) ? start - 1 : start;
            String text = normalizedText.substring(actualStart, end);
            chunks.add(new Chunk(pointId(sourceKey, versionLabel, hash, ordinal), ordinal, actualStart, end, text));
            ordinal++;
            if (end == normalizedText.length()) {
                break;
            }
        }
        return List.copyOf(chunks);
    }

    /** 单个分块。 */
    public record Chunk(UUID pointId, int ordinal, int startOffset, int endOffset, String text) {
        public Chunk {
            if (text == null || text.isEmpty() || text.length() > MAX_CHUNK_CHARS) {
                throw new IllegalArgumentException("KNOWLEDGE_CHUNK_SIZE_INVALID");
            }
            if (endOffset - startOffset != text.length()) {
                throw new IllegalArgumentException("KNOWLEDGE_CHUNK_OFFSET_INVALID");
            }
        }

        public String pointIdText() {
            return pointId.toString();
        }

        public String chunkSha256() {
            return sha256(text);
        }
    }
}
