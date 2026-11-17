package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.config.KnowledgeDocProperties;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 文档通道的检索服务。
 *
 * <p>安全模型与平台帮助通道一致：向量只用于定位，正文与状态一律以本地权威快照为准。
 * 每次检索都先读取目录代次，代次变化即重建快照，从而保证更新/撤销/权限变化立即生效。</p>
 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@Service
public class KnowledgeDocRetrievalService {
    private final KnowledgeDocProperties properties;
    private final KnowledgeDocRegistry registry;
    private final Optional<KnowledgeDocIndexer> indexer;

    public KnowledgeDocRetrievalService(KnowledgeDocProperties properties, KnowledgeDocRegistry registry,
                                        org.springframework.beans.factory.ObjectProvider<KnowledgeDocIndexer> indexer) {
        this.properties = properties;
        this.registry = registry;
        this.indexer = Optional.ofNullable(indexer.getIfAvailable());
    }

    /** 单条命中：来源文档 + 版本 + 完整正文片段。 */
    public record Hit(String sourceKey, String title, String versionLabel, String text, double score) {
    }

    /** 检索结果；revision 用于调用方判断是否在生成期间已失效。 */
    public record Evidence(long revision, List<Hit> hits, String context) {
        public boolean empty() {
            return hits.isEmpty();
        }
    }

    public boolean available() {
        return properties.isEnabled() && indexer.isPresent();
    }

    /** 目录中已发布的文档标题，供提示词与按标题读取使用。 */
    public List<String> titles() {
        return registry.current().titles();
    }

    /** 语义检索；命中后按来源整篇释放，受上下文预算约束。 */
    public Evidence retrieve(String query) {
        KnowledgeDocCatalog catalog = registry.current();
        if (query == null || query.isBlank() || query.length() > 1000 || catalog.isEmpty() || indexer.isEmpty()) {
            return new Evidence(catalog.revision(), List.of(), "");
        }
        List<Document> candidates = indexer.get().search(query,
                properties.getChunkCandidateLimit(), properties.getSimilarityThreshold());
        Map<String, Double> scoreBySource = new LinkedHashMap<>();
        for (Document candidate : candidates) {
            if (candidate == null || candidate.getScore() == null || !Double.isFinite(candidate.getScore())) {
                continue;
            }
            KnowledgeDocCatalog.Chunk chunk = catalog.verified(candidate.getId(), candidate.getMetadata());
            if (chunk == null) {
                continue;
            }
            KnowledgeDocCatalog.Doc doc = catalog.documentByVersionId(chunk.versionId());
            if (doc == null) {
                continue;
            }
            scoreBySource.merge(doc.sourceKey(), candidate.getScore(), Math::max);
        }
        List<String> ordered = scoreBySource.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
        List<Hit> hits = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (String sourceKey : ordered) {
            if (hits.size() >= properties.getTopK()) {
                break;
            }
            KnowledgeDocCatalog.Doc doc = catalog.document(sourceKey);
            List<KnowledgeDocCatalog.Chunk> chunks = catalog.chunksOf(sourceKey);
            if (doc == null || chunks.isEmpty()) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (KnowledgeDocCatalog.Chunk chunk : chunks) {
                text.append(chunk.text()).append('\n');
            }
            String body = text.toString().strip();
            String block = renderBlock(doc, body);
            if (context.length() > 0
                    && context.toString().getBytes(StandardCharsets.UTF_8).length
                    + block.getBytes(StandardCharsets.UTF_8).length > properties.getContextByteBudget()) {
                continue;
            }
            context.append(block).append('\n');
            hits.add(new Hit(sourceKey, doc.title(), doc.versionLabel(), body, scoreBySource.get(sourceKey)));
        }
        return new Evidence(catalog.revision(), List.copyOf(hits), context.toString().strip());
    }

    /** 按精确标题读取整篇；标题必须唯一。 */
    public Evidence readByTitle(String title) {
        KnowledgeDocCatalog catalog = registry.current();
        if (title == null || title.isBlank() || title.length() > 160 || indexer.isEmpty()) {
            return new Evidence(catalog.revision(), List.of(), "");
        }
        List<KnowledgeDocCatalog.Doc> matches = catalog.sourceKeys().stream()
                .map(catalog::document)
                .filter(doc -> doc != null && title.equals(doc.title()))
                .toList();
        if (matches.size() != 1) {
            return new Evidence(catalog.revision(), List.of(), "");
        }
        KnowledgeDocCatalog.Doc doc = matches.getFirst();
        List<KnowledgeDocCatalog.Chunk> expected = catalog.chunksOf(doc.sourceKey());
        if (expected.isEmpty()) {
            return new Evidence(catalog.revision(), List.of(), "");
        }
        Set<String> expectedIds = new LinkedHashSet<>();
        expected.forEach(chunk -> expectedIds.add(chunk.pointId()));
        List<Document> indexed = indexer.get().readChunks(List.copyOf(expectedIds));
        Set<String> verified = new LinkedHashSet<>();
        for (Document candidate : indexed) {
            KnowledgeDocCatalog.Chunk chunk = catalog.verified(candidate.getId(), candidate.getMetadata());
            if (chunk == null || !expectedIds.contains(candidate.getId())) {
                throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档索引与目录不一致");
            }
            verified.add(candidate.getId());
        }
        if (!verified.equals(expectedIds)) {
            throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档索引缺少分块");
        }
        StringBuilder text = new StringBuilder();
        expected.forEach(chunk -> text.append(chunk.text()).append('\n'));
        String body = text.toString().strip();
        Hit hit = new Hit(doc.sourceKey(), doc.title(), doc.versionLabel(), body, 1.0d);
        return new Evidence(catalog.revision(), List.of(hit), renderBlock(doc, body));
    }

    /** 调用方在生成结束后复核：代次变化即说明知识已失效。 */
    public boolean stillCurrent(long revision) {
        return registry.current().revision() == revision;
    }

    private static String renderBlock(KnowledgeDocCatalog.Doc doc, String body) {
        return "【知识文档】" + doc.title() + "（版本 " + doc.versionLabel() + "，来源 " + doc.sourceKey() + "）\n"
                + body;
    }
}
