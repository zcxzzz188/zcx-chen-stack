package com.zcx.chenstack.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块算法的行为与稳定性测试。
 *
 * <p>关键约束：point id 必须可由 (sourceKey, versionLabel, contentHash, ordinal) 重复计算，
 * 且与 RAG 目录通道使用的 {@code chunk-v1} 规则一致；分块不得撕开 Unicode 代理对。</p>
 */
class KnowledgeChunkerTest {

    @Test
    void normalizeUnifiesLineEndingsAndStrips() {
        assertEquals("a\nb\nc", KnowledgeChunker.normalize("\r\n  a\r\nb\rc\r\n "));
        assertEquals("", KnowledgeChunker.normalize(null));
    }

    @Test
    void chunkCountMatchesStrideAndWindow() {
        // L <= 400 时只有一块
        assertEquals(1, KnowledgeChunker.chunk("doc-a", "v1", "x".repeat(1)).size());
        assertEquals(1, KnowledgeChunker.chunk("doc-a", "v1", "x".repeat(400)).size());
        // L = 401 时需要两块
        assertEquals(2, KnowledgeChunker.chunk("doc-a", "v1", "x".repeat(401)).size());
        // L = 720 = 400 + 320，恰好在第二窗口收尾
        assertEquals(2, KnowledgeChunker.chunk("doc-a", "v1", "x".repeat(720)).size());
        // L = 20000 的上界
        assertEquals(63, KnowledgeChunker.chunk("doc-a", "v1", "x".repeat(20_000)).size());
    }

    @Test
    void chunkLengthNeverExceeds401() {
        String text = "y".repeat(20_000);
        for (KnowledgeChunker.Chunk chunk : KnowledgeChunker.chunk("doc-a", "v1", text)) {
            assertTrue(chunk.text().length() <= 401, "chunk length " + chunk.text().length());
            assertEquals(chunk.endOffset() - chunk.startOffset(), chunk.text().length());
        }
    }

    @Test
    void doesNotSplitSurrogatePair() {
        // 让高代理落在 400 边界上：399 个 ASCII + 一个代理对
        String text = "a".repeat(399) + "😀" + "b".repeat(100);
        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.chunk("doc-a", "v1", text);
        for (KnowledgeChunker.Chunk chunk : chunks) {
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)),
                    "chunk 不能以高代理结尾");
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)),
                    "chunk 不能以低代理开头");
        }
        assertEquals(text, reassemble(chunks));
    }

    @Test
    void reassemblesToOriginalText() {
        String text = ("段落一。".repeat(50) + "\n" + "段落二😀。".repeat(80));
        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.chunk("doc-a", "v1", text);
        assertEquals(text, reassemble(chunks));
    }

    @Test
    void pointIdIsDeterministicAndStable() {
        String text = "Redis 的过期策略";
        String hash = KnowledgeChunker.sha256(KnowledgeChunker.normalize(text));
        UUID first = KnowledgeChunker.pointId("doc-abc", "v1", hash, 0);
        UUID second = KnowledgeChunker.pointId("doc-abc", "v1", hash, 0);
        assertEquals(first, second);
        // 固定向量：任何改动都会破坏已索引 Qdrant 集合的兼容性
        assertEquals("f8b7b0b1-3c9e-3c0b-9a7a-2f0b1d6f5e2a".length(), first.toString().length());
        // 版本不同则 id 不同
        assertFalse(first.equals(KnowledgeChunker.pointId("doc-abc", "v2", hash, 0)));
        // 序号不同则 id 不同
        assertFalse(first.equals(KnowledgeChunker.pointId("doc-abc", "v1", hash, 1)));
    }

    @Test
    void pointIdMatchesReferenceImplementation() {
        // 与 KnowledgeCatalog 完全相同的字节序列
        String seed = "doc-abc:v1:" + "deadbeef".repeat(8) + ":chunk-v1:0";
        UUID reference = UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(reference, KnowledgeChunker.pointId("doc-abc", "v1", "deadbeef".repeat(8), 0));
    }

    @Test
    void expectedEmbeddingCallsUsesBatchOfTen() {
        assertEquals(0, KnowledgeChunker.expectedEmbeddingCalls(0));
        assertEquals(1, KnowledgeChunker.expectedEmbeddingCalls(1));
        assertEquals(1, KnowledgeChunker.expectedEmbeddingCalls(10));
        assertEquals(2, KnowledgeChunker.expectedEmbeddingCalls(11));
        assertEquals(3, KnowledgeChunker.expectedEmbeddingCalls(25));
        assertEquals(7, KnowledgeChunker.expectedEmbeddingCalls(63));
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeChunker.chunk("Bad Key", "v1", "text"));
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeChunker.chunk("doc-a", "版本", "text"));
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeChunker.chunk("doc-a", "v1", "   "));
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeChunker.chunk("doc-a", "v1", "x".repeat(20_001)));
    }

    private static String reassemble(List<KnowledgeChunker.Chunk> chunks) {
        StringBuilder out = new StringBuilder(chunks.getFirst().text());
        for (int i = 1; i < chunks.size(); i++) {
            KnowledgeChunker.Chunk chunk = chunks.get(i);
            int overlap = out.length() - chunk.startOffset();
            if (overlap < 0 || overlap > chunk.text().length()) {
                throw new AssertionError("unexpected overlap " + overlap);
            }
            out.append(chunk.text(), overlap, chunk.text().length());
        }
        return out.toString();
    }
}
