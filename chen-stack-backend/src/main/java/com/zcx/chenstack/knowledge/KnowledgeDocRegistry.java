package com.zcx.chenstack.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档目录快照的持有者。
 *
 * <p>每次读取都先比对 {@code knowledge_catalog_state.revision}；只有代次变化才重建快照。
 * 这样“撤销/更新/权限变化”都能可靠失效，不依赖行数或最大主键。</p>
 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@Component
public class KnowledgeDocRegistry {
    private final KnowledgeDocStore store;
    private final Object lock = new Object();
    private volatile KnowledgeDocCatalog cached = KnowledgeDocCatalog.empty();
    private volatile long cachedRevision = Long.MIN_VALUE;

    public KnowledgeDocRegistry(KnowledgeDocStore store) {
        this.store = store;
    }

    /** 当前快照；必要时按代次重建。数据库不可用时返回缓存并标记为陈旧（由调用方判断）。 */
    public KnowledgeDocCatalog current() {
        long revision = store.revision();
        KnowledgeDocCatalog snapshot = cached;
        if (revision == cachedRevision && snapshot.revision() == revision) {
            return snapshot;
        }
        synchronized (lock) {
            if (revision == cachedRevision && cached.revision() == revision) {
                return cached;
            }
            KnowledgeDocCatalog rebuilt = load(revision);
            cached = rebuilt;
            cachedRevision = revision;
            return rebuilt;
        }
    }

    /** 强制重建，用于发布/撤销后的即时失效。 */
    public KnowledgeDocCatalog refresh() {
        synchronized (lock) {
            long revision = store.revision();
            KnowledgeDocCatalog rebuilt = load(revision);
            cached = rebuilt;
            cachedRevision = revision;
            return rebuilt;
        }
    }

    private KnowledgeDocCatalog load(long revision) {
        List<KnowledgeDocStore.PublishedVersionRow> versions = store.listPublishedVersions();
        List<Long> versionIds = versions.stream().map(KnowledgeDocStore.PublishedVersionRow::versionId).toList();
        List<KnowledgeDocStore.ChunkRow> chunks = store.listChunksForVersions(versionIds);
        return KnowledgeDocCatalog.of(revision, versions, chunks);
    }
}
