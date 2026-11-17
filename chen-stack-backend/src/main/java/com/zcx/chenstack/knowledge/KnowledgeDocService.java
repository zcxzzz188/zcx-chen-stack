package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.config.KnowledgeDocProperties;
import com.zcx.chenstack.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一知识文档用例层。
 *
 * <p>三个生命周期严格分离：</p>
 * <ol>
 *   <li>导入/解析：写 {@code knowledge_import_task}，不产生文档；</li>
 *   <li>审核/发布：写 {@code knowledge_document} 与版本、分块，切换可见性并递增目录代次；</li>
 *   <li>索引：写 {@code knowledge_index_task} 并操作 Qdrant，失败不影响已发布的业务事实。</li>
 * </ol>
 *
 * <p>本类不读写旧 RAG 目录，也不依赖 Wiki；Wiki 通过独立适配器读取同一批文档版本。</p>
 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@Service
public class KnowledgeDocService {
    private final KnowledgeDocProperties properties;
    private final RagProperties ragProperties;
    private final KnowledgeDocStore store;
    private final KnowledgeDocRegistry registry;
    private final PrivateObjectStore objects;
    private final Optional<KnowledgeDocIndexer> indexer;
    private final TransactionTemplate transaction;
    /** 单实例内串行化“导入任务 → 来源文档”的补建，避免同一幂等键并发产生两份来源。 */
    private final Object sourceDocumentLock = new Object();

    public KnowledgeDocService(KnowledgeDocProperties properties, RagProperties ragProperties,
                               KnowledgeDocStore store, KnowledgeDocRegistry registry,
                               PrivateObjectStore objects,
                               org.springframework.beans.factory.ObjectProvider<KnowledgeDocIndexer> indexer,
                               PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.ragProperties = ragProperties;
        this.store = store;
        this.registry = registry;
        this.objects = objects;
        this.indexer = Optional.ofNullable(indexer.getIfAvailable());
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(30);
    }

    // ------------------------------------------------------------------ 导入

    /** 上传并解析；相同幂等键与相同载荷返回既有任务，载荷不同则冲突。 */
    public KnowledgeDocStore.ImportRow upload(String requestId, String filename, byte[] bytes,
                                              String fallbackTitle, Integer actorId) {
        requireEnabled();
        if (actorId == null || actorId <= 0) {
            throw KnowledgeDocException.denied();
        }
        if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,72}")) {
            throw KnowledgeDocException.bad("IMPORT_REQUEST_ID_INVALID", "幂等键格式非法");
        }
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse(filename, bytes, fallbackTitle);
        String extension = KnowledgeTextParser.extensionOf(filename);
        if (!properties.allowsExtension(extension)) {
            throw KnowledgeDocException.bad("IMPORT_EXTENSION_INVALID", "仅支持 " + properties.getAllowedExtensions());
        }
        if (bytes.length > properties.getMaxFileBytes()) {
            throw KnowledgeDocException.bad("IMPORT_TOO_LARGE", "文件超过大小限制");
        }
        String fileSha = PrivateObjectStore.sha256(bytes);
        String objectKey = PrivateObjectStore.objectKey(fileSha, extension);
        String fingerprint = fingerprint(fileSha, parsed.title(), parsed.contentType(), actorId);

        Optional<KnowledgeDocStore.ImportRow> existing = store.findImport(requestId);
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) {
                throw KnowledgeDocException.conflict("IMPORT_IDEMPOTENCY_CONFLICT",
                        "同一幂等键提交了不同内容");
            }
            ensureSourceDocument(existing.get(), parsed, actorId);
            return store.findImport(requestId).orElse(existing.get());
        }

        if (!objects.exists(objectKey)) {
            objects.put(objectKey, bytes, parsed.contentType());
        }
        KnowledgeDocStore.ImportRow row = new KnowledgeDocStore.ImportRow(requestId, fingerprint, filename,
                parsed.contentType(), bytes.length, fileSha, objects.bucket(), objectKey, parsed.bomPresent(),
                "PARSED", parsed.parserVersion(), parsed.normalizedText().length(),
                KnowledgeChunker.sha256(parsed.normalizedText()), null, null, null, actorId, null, null, Instant.now());
        KnowledgeDocStore.ImportRow stored = store.insertImportIfAbsent(row);
        if (!stored.requestFingerprint().equals(fingerprint)) {
            throw KnowledgeDocException.conflict("IMPORT_IDEMPOTENCY_CONFLICT", "同一幂等键提交了不同内容");
        }
        // 上传成功即建立“原始文件 → 文档 → 首版正文”的来源链；仍未审核、不会进入 RAG。
        ensureSourceDocument(stored, parsed, actorId);
        KnowledgeDocStore.ImportRow fresh = store.findImport(requestId).orElse(stored);
        if (!"PARSED".equals(fresh.status()) && !"RECEIVED".equals(fresh.status())) {
            return fresh;
        }
        store.audit(requestId, fresh.documentId(), fresh.versionId(), requestId, actorId,
                KnowledgeDocStore.ACTOR_USER, null, "IMPORT_PARSED", "IMPORT", null,
                fresh.parsedTextSha256(), null,
                Map.of("fileSha256", fileSha, "chars", fresh.parsedChars(), "bom", fresh.bomPresent()));
        return store.findImport(requestId).orElse(fresh);
    }

    /** 来源文档与版本：供 AI 候选知识、草稿和发布链追溯原始文件。 */
    public record SourceDocument(long documentId, long versionId) {
    }

    /**
     * 确保导入任务拥有稳定的来源文档与首版正文。
     *
     * <p>只在首次上传或识别到旧任务缺少来源时创建；重复调用返回同一对 ID，不重复建文档。
     * 文档保持 DRAFT/INTERNAL，因此上传不等于审核通过，也不会进入 RAG 或 Wiki。</p>
     */
    public SourceDocument ensureSourceDocument(String requestId, Integer actorId) {
        requireEnabled();
        KnowledgeDocStore.ImportRow task = requireImport(requestId);
        byte[] raw = readRaw(task.objectKey());
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse(task.originalFilename(), raw, null);
        return ensureSourceDocument(task, parsed, actorId == null ? 0 : actorId);
    }

    private SourceDocument ensureSourceDocument(KnowledgeDocStore.ImportRow task,
                                                KnowledgeTextParser.Parsed parsed, Integer actorId) {
        if (actorId == null || actorId <= 0) {
            throw KnowledgeDocException.denied();
        }
        int actor = actorId;
        synchronized (sourceDocumentLock) {
            SourceDocument ready = existingSource(task);
            if (ready != null) {
                return ready;
            }
            SourceDocument created = transaction.execute(status -> {
                KnowledgeDocStore.ImportRow current = store.findImport(task.requestId()).orElse(task);
                SourceDocument existing = existingSource(current);
                if (existing != null) {
                    return existing;
                }
                String documentKind = "MARKDOWN".equals(parsed.rawFormat())
                        ? "UPLOADED_MARKDOWN" : "UPLOADED_TEXT";
                long documentId = store.insertDocument(newSourceKey(), documentKind, "DOCUMENT", null,
                        task.requestId(), parsed.title(), null, "INTERNAL", actor,
                        KnowledgeDocStore.ACTOR_USER);
                String sha = KnowledgeChunker.contentHash(parsed.normalizedText());
                long versionId = store.insertVersion(documentId, 1, "v1", parsed.rawText(),
                        parsed.normalizedText(), parsed.rawFormat(), sha, task.fileSha256(),
                        parsed.normalizedText().length(), parsed.parserVersion(), "DRAFT", actor,
                        KnowledgeDocStore.ACTOR_USER);
                store.attachImportDocument(task.requestId(), documentId, versionId);
                store.audit(task.requestId(), documentId, versionId, task.requestId(), actor,
                        KnowledgeDocStore.ACTOR_USER, null, "CREATE", "DOCUMENT", null, sha,
                        "IMPORT_SOURCE_DOCUMENT",
                        Map.of("title", parsed.title(), "filename", task.originalFilename()));
                return new SourceDocument(documentId, versionId);
            });
            if (created == null) {
                throw KnowledgeDocException.unavailable("IMPORT_SOURCE_UNAVAILABLE", "无法建立原始文档来源");
            }
            return created;
        }
    }

    private SourceDocument existingSource(KnowledgeDocStore.ImportRow task) {
        if (task.documentId() == null || task.versionId() == null) {
            return null;
        }
        if (store.findDocument(task.documentId()).isEmpty() || store.findVersion(task.versionId()).isEmpty()) {
            return null;
        }
        return new SourceDocument(task.documentId(), task.versionId());
    }

    /** 审核通过：把来源文档的首版正文标记为已审核并生成分块；此时仍未进入 RAG。 */
    public KnowledgeDocStore.DocRow approve(String requestId, Integer actorId) {
        requireEnabled();
        KnowledgeDocStore.ImportRow task = requireImport(requestId);
        if (!"PARSED".equals(task.status())) {
            throw KnowledgeDocException.conflict("IMPORT_STATE_INVALID", "仅已解析的导入任务可以审核");
        }
        // 对象存储读取放在事务外，避免长事务持有网络 IO
        byte[] raw = readRaw(task.objectKey());
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse(task.originalFilename(), raw, null);
        SourceDocument source = ensureSourceDocument(task, parsed, actorId);
        Long docId = transaction.execute(status -> {
            KnowledgeDocStore.DocRow document = store.findDocument(source.documentId())
                    .orElseThrow(KnowledgeDocException::missing);
            if (!"DRAFT".equals(document.reviewStatus())) {
                throw KnowledgeDocException.conflict("DOCUMENT_STATE_INVALID", "来源文档已不在草稿状态");
            }
            KnowledgeDocStore.VersionRow version = store.findVersion(source.versionId())
                    .orElseThrow(KnowledgeDocException::missing);
            String sha = KnowledgeChunker.contentHash(parsed.normalizedText());
            if (store.countChunks(version.id()) == 0) {
                List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.chunk(document.sourceKey(),
                        version.versionLabel(), parsed.normalizedText());
                store.insertChunks(version.id(), chunks, null);
            }
            store.updateVisibility(document.id(), "PUBLIC");
            store.approveVersion(version.id(), actorId, sha);
            store.updateReviewStatus(document.id(), "APPROVED");
            store.attachImportDocument(requestId, document.id(), version.id());
            store.updateImportStatus(requestId, "APPROVED", null, actorId);
            store.audit(requestId, document.id(), version.id(), requestId, actorId,
                    KnowledgeDocStore.ACTOR_USER, null, "APPROVE", "DOCUMENT", null, sha, null,
                    Map.of("title", parsed.title(), "chunks", store.countChunks(version.id())));
            return document.id();
        });
        registry.refresh();
        return store.findDocument(docId).orElseThrow(KnowledgeDocException::missing);
    }

    // ------------------------------------------------------------------ 发布与索引

    /** 发布结果：文档发布成功与否、索引任务、索引错误分别报告，绝不把索引失败说成成功。 */
    public record PublishResult(KnowledgeDocStore.DocRow document, KnowledgeDocStore.IndexTaskRow indexTask,
                                String indexErrorCode) {
        public boolean indexed() {
            return indexTask != null && "COMPLETED".equals(indexTask.status());
        }
    }

    /** 发布：切换当前版本并尝试索引；索引失败不回滚发布，但必须如实记录失败。 */
    public PublishResult publish(long documentId, Integer actorId) {
        requireEnabled();
        KnowledgeDocStore.DocRow doc = requireDocument(documentId);
        if (!"APPROVED".equals(doc.reviewStatus())) {
            throw KnowledgeDocException.conflict("DOCUMENT_NOT_APPROVED", "文档尚未审核通过");
        }
        KnowledgeDocStore.VersionRow version = latestDraftVersion(documentId);
        transaction.executeWithoutResult(status -> {
            store.demoteCurrent(documentId);
            store.promoteCurrent(version.id());
            store.markPublished(documentId, version.id(), version.versionNo(), version.normalizedSha256());
            store.demoteDraft(documentId);
            if (doc.originRef() != null && doc.originRef().startsWith("imp-")) {
                store.updateImportStatus(doc.originRef(), "PUBLISHED", null, actorId);
            }
            store.audit(null, documentId, version.id(), doc.originRef(), actorId, KnowledgeDocStore.ACTOR_USER,
                    null, "PUBLISH", "DOCUMENT", doc.contentSha256(), version.normalizedSha256(), null,
                    Map.of("version", version.versionLabel()));
        });
        registry.refresh();
        KnowledgeDocStore.IndexTaskRow task = null;
        String errorCode = null;
        if (indexer.isPresent()) {
            try {
                task = index(documentId, actorId);
            } catch (RuntimeException failure) {
                errorCode = failure instanceof KnowledgeDocException docError
                        ? docError.code() : "INDEX_FAILED";
            }
        } else {
            errorCode = "INDEX_UNAVAILABLE";
        }
        return new PublishResult(requireDocument(documentId), task, errorCode);
    }

    /** 撤销：立刻从目录移除并清理向量；历史版本与审计保留。 */
    public KnowledgeDocStore.DocRow revoke(long documentId, Integer actorId) {
        requireEnabled();
        KnowledgeDocStore.DocRow doc = requireDocument(documentId);
        transaction.executeWithoutResult(status -> {
            store.markRevoked(documentId);
            store.audit(null, documentId, doc.currentVersionId(), doc.originRef(), actorId,
                    KnowledgeDocStore.ACTOR_USER, null, "REVOKE", "DOCUMENT", doc.contentSha256(), null, "MANUAL_REVOKE",
                    Map.of("previousPublishStatus", doc.publishStatus()));
        });
        registry.refresh();
        indexer.ifPresent(value -> {
            try {
                value.deleteSource(doc.sourceKey());
            } catch (KnowledgeDocException ignored) {
                // 向量清理失败不回滚业务撤权；下次索引会再次尝试
            }
        });
        return requireDocument(documentId);
    }

    /** 下架：保留审核通过状态，仅从公开检索移除。 */
    public KnowledgeDocStore.DocRow withdraw(long documentId, Integer actorId) {
        requireEnabled();
        KnowledgeDocStore.DocRow doc = requireDocument(documentId);
        transaction.executeWithoutResult(status -> {
            store.markWithdrawn(documentId);
            store.audit(null, documentId, doc.currentVersionId(), doc.originRef(), actorId,
                    KnowledgeDocStore.ACTOR_USER, null, "WITHDRAW", "DOCUMENT", doc.contentSha256(), null, "MANUAL_WITHDRAW",
                    Map.of());
        });
        registry.refresh();
        indexer.ifPresent(value -> {
            try {
                value.deleteSource(doc.sourceKey());
            } catch (KnowledgeDocException ignored) {
                // 同上
            }
        });
        return requireDocument(documentId);
    }

    /** 为当前版本重建索引任务。 */
    public KnowledgeDocStore.IndexTaskRow index(long documentId, Integer actorId) {
        KnowledgeDocStore.DocRow doc = requireDocument(documentId);
        if (doc.currentVersionId() == null) {
            throw KnowledgeDocException.conflict("DOCUMENT_NOT_PUBLISHED", "文档尚未发布");
        }
        KnowledgeDocIndexer value = indexer.orElseThrow(() -> KnowledgeDocException.unavailable(
                "INDEX_UNAVAILABLE", "文档索引器未启用"));
        KnowledgeDocStore.VersionRow version = store.findVersion(doc.currentVersionId())
                .orElseThrow(KnowledgeDocException::missing);
        List<KnowledgeDocCatalog.Chunk> chunks = toChunks(doc, version);
        int expectedCalls = KnowledgeChunker.expectedEmbeddingCalls(chunks.size());
        int maxCalls = Math.max(expectedCalls, Math.min(properties.getMaxEmbeddingRequests(), 512));
        String taskId = "idx-" + UUID.randomUUID();
        store.insertIndexTask(taskId, doc.id(), version.id(), "INDEX_FULL", value.collection(),
                value.model(), value.signature(), chunks.size(), expectedCalls, maxCalls, actorId);
        long epoch = store.revision();
        store.audit(null, doc.id(), version.id(), null, actorId, KnowledgeDocStore.ACTOR_USER, null,
                "INDEX_PREPARED", "INDEX", null, null, null,
                Map.of("taskId", taskId, "chunks", chunks.size(), "expectedCalls", expectedCalls));
        boolean dispatched = false;
        try {
            value.verify(true);
            store.markIndexing(taskId, epoch);
            value.upsert(new KnowledgeDocCatalog.Doc(doc.id(), version.id(), doc.sourceKey(), doc.title(),
                            doc.summary(), version.versionLabel(), version.normalizedSha256(),
                            doc.sourceRefKind(), doc.sourceUrl() == null ? "" : doc.sourceUrl(), ""),
                    chunks,
                    () -> store.reserveEmbedding(taskId));
            dispatched = true;
            value.deleteStaleVersions(doc.sourceKey(), version.id());
            String fingerprint = value.fingerprint(chunks);
            store.completeIndexTask(taskId, value.signature(), epoch, fingerprint);
            store.audit(null, doc.id(), version.id(), null, actorId, KnowledgeDocStore.ACTOR_USER, null,
                    "INDEX_COMPLETE", "INDEX", null, fingerprint, null,
                    Map.of("taskId", taskId, "chunks", chunks.size()));
        } catch (RuntimeException failure) {
            String code = failure instanceof KnowledgeDocException docError
                    ? docError.code() : "INDEX_FAILED";
            // 是否“已经发出过请求”以数据库账本为准，不猜内存状态
            boolean sent = store.findIndexTask(taskId).map(task -> task.embeddingRequests() > 0).orElse(false);
            if (dispatched || sent) {
                store.markIndexUnknown(taskId, code);
                store.auditSystem("INDEX_UNKNOWN", "INDEX", code, Map.of("taskId", taskId));
            } else {
                store.failIndexTask(taskId, code);
                store.auditSystem("INDEX_FAILED", "INDEX", code, Map.of("taskId", taskId));
            }
            throw failure;
        }
        return store.findIndexTask(taskId).orElseThrow(KnowledgeDocException::missing);
    }

    // ------------------------------------------------------------------ 查询

    public List<KnowledgeDocStore.DocRow> documents(String query, int limit) {
        requireEnabled();
        return store.listDocuments(query, true, Math.min(Math.max(limit, 1), 200));
    }

    public KnowledgeDocStore.DocRow document(long id) {
        requireEnabled();
        return requireDocument(id);
    }

    public List<KnowledgeDocStore.VersionRow> versions(long documentId) {
        requireEnabled();
        requireDocument(documentId);
        return store.listVersions(documentId);
    }

    public List<KnowledgeDocStore.IndexTaskRow> indexTasks(long documentId) {
        requireEnabled();
        requireDocument(documentId);
        return store.listIndexTasks(documentId, 50);
    }

    public List<KnowledgeDocStore.ImportRow> imports(String status, int limit) {
        requireEnabled();
        return store.listImports(status, Math.min(Math.max(limit, 1), 200));
    }

    public KnowledgeDocStore.ImportRow importTask(String requestId) {
        requireEnabled();
        return requireImport(requestId);
    }

    public byte[] readRaw(String objectKey) {
        try (var input = objects.open(objectKey)) {
            return input.readAllBytes();
        } catch (java.io.IOException error) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_STORE_UNAVAILABLE", "知识原件读取失败");
        }
    }

    public List<Map<String, Object>> audit(long documentId) {
        requireEnabled();
        requireDocument(documentId);
        return store.recentAudit(documentId, 100);
    }

    public boolean indexAvailable() {
        return indexer.isPresent();
    }

    public String collection() {
        return properties.getCollection();
    }

    public String bucket() {
        return objects.bucket();
    }

    public void reject(String requestId, String reasonCode, Integer actorId) {
        requireEnabled();
        KnowledgeDocStore.ImportRow task = requireImport(requestId);
        if ("PUBLISHED".equals(task.status()) || "APPROVED".equals(task.status())) {
            throw KnowledgeDocException.conflict("IMPORT_STATE_INVALID", "已审核的任务不能驳回");
        }
        store.updateImportStatus(requestId, "REJECTED", reasonCode == null ? "MANUAL_REJECT" : reasonCode, actorId);
        store.audit(requestId, null, null, requestId, actorId, KnowledgeDocStore.ACTOR_USER, null,
                "REJECT", "IMPORT", null, null, reasonCode, Map.of());
    }

    /** 旧目录镜像写入，供回填使用；不触发索引、不改旧表。 */
    public void mirrorLegacy(String sourceKey, String title, String sourceUrl, String visibility,
                             String reviewStatus, String publishStatus, String legacyReviewStatus,
                             String contentSha256, Long legacyGeneration, boolean tombstone, boolean deleted,
                             String keywordsJson) {
        store.upsertMirrorDocument(sourceKey, title, sourceUrl, visibility, reviewStatus, publishStatus,
                legacyReviewStatus, contentSha256, legacyGeneration, tombstone, deleted, keywordsJson);
    }

    public KnowledgeDocStore store() {
        return store;
    }

    // ------------------------------------------------------------------ 内部

    private String fingerprint(String fileSha, String title, String contentType, int actorId) {
        return KnowledgeChunker.sha256(fileSha + "|" + title + "|" + contentType + "|" + actorId);
    }

    private static String newSourceKey() {
        return "doc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private KnowledgeDocStore.DocRow requireDocument(long id) {
        return store.findDocument(id).orElseThrow(KnowledgeDocException::missing);
    }

    private KnowledgeDocStore.ImportRow requireImport(String requestId) {
        return store.findImport(requestId).orElseThrow(KnowledgeDocException::missing);
    }

    private KnowledgeDocStore.VersionRow latestDraftVersion(long documentId) {
        List<KnowledgeDocStore.VersionRow> versions = store.listVersions(documentId);
        return versions.stream().filter(version -> "DRAFT".equals(version.state())).findFirst()
                .orElseGet(() -> versions.stream().filter(version -> "CURRENT".equals(version.state()))
                        .findFirst().orElseThrow(() -> KnowledgeDocException.conflict(
                                "DOCUMENT_NO_VERSION", "文档没有可发布版本")));
    }

    private List<KnowledgeDocCatalog.Chunk> toChunks(KnowledgeDocStore.DocRow doc,
                                                     KnowledgeDocStore.VersionRow version) {
        if (!version.bodyAvailable() || version.normalizedBody() == null) {
            throw KnowledgeDocException.conflict("DOCUMENT_BODY_UNAVAILABLE", "该版本没有可用正文，无法索引");
        }
        List<KnowledgeChunker.Chunk> computed = KnowledgeChunker.chunk(doc.sourceKey(), version.versionLabel(),
                version.normalizedBody());
        List<KnowledgeDocCatalog.Chunk> result = new ArrayList<>(computed.size());
        for (KnowledgeChunker.Chunk chunk : computed) {
            result.add(new KnowledgeDocCatalog.Chunk(chunk.pointIdText(), version.id(), chunk.ordinal(),
                    chunk.text(), chunk.chunkSha256(), chunk.startOffset(), chunk.endOffset()));
        }
        return List.copyOf(result);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_DISABLED", "统一知识文档通道未启用");
        }
    }

    /** 供诊断展示的摘要。 */
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("collection", properties.getCollection());
        result.put("bucket", objects.bucket());
        result.put("indexer", indexer.isPresent());
        result.put("revision", store.revision());
        result.put("embeddingModel", ragProperties.getEmbeddingModel());
        result.put("wikiSourceType", "KNOWLEDGE_DOC");
        return result;
    }
}
