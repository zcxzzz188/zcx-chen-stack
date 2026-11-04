package com.zcx.chenstack.rag;

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 单实例权威拥有者。可信宿主显式提交事件；不监听文件，也不以索引删除代替撤权。 */
@lombok.extern.slf4j.Slf4j
public final class CatalogLifecycle implements AutoCloseable {
    private final String authorityId = UUID.randomUUID().toString();
    private KnowledgeCatalog current;
    private boolean available = true;
    private final JdbcCatalogStore store;
    private final boolean readOnly;
    private final Map<KnowledgeCatalog, Map<Thread, Integer>> releasing = new IdentityHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean publicationRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private Runnable ownerRecovery;
    private boolean recoveringOwner;
    private long retryOwnerAfter;

    CatalogLifecycle() { this(null, false); }

    CatalogLifecycle(JdbcCatalogStore store) { this(store, false); }

    CatalogLifecycle(boolean readOnly) { this(null, readOnly); }

    private CatalogLifecycle(JdbcCatalogStore store, boolean readOnly) {
        this.store = store;
        this.readOnly = readOnly;
    }

    synchronized void initialize(KnowledgeCatalog initial) {
        if (current != null) throw new IllegalStateException("CATALOG_ALREADY_INITIALIZED");
        current = initial;
    }

    public String authorityId() { return authorityId; }
    public synchronized void recoverOwnerWith(Runnable recovery) {
        if (store == null || readOnly) throw new IllegalStateException("CATALOG_RECOVERY_REQUIRES_DURABLE_OWNER");
        ownerRecovery = Objects.requireNonNull(recovery);
    }
    boolean durable() { return store != null; }
    synchronized boolean beginPublication() { requireMutable(); return publicationRunning.compareAndSet(false, true); }
    void endPublication() { publicationRunning.set(false); }
    JdbcCatalogStore store() {
        if (store == null) throw new IllegalStateException("RELEASE_REQUIRES_DURABLE_CATALOG");
        return store;
    }

    public synchronized KnowledgeCatalog snapshot() {
        if (!available || current == null) throw new Invalidated();
        if (store != null) {
            try { store.requireOwner(); }
            catch (JdbcCatalogStore.OwnerLost lost) {
                if (ownerRecovery == null || recoveringOwner || publicationRunning.get()
                        || retryOwnerAfter != 0 && System.nanoTime() - retryOwnerAfter < 0) throw lost;
                recoveringOwner = true;
                retryOwnerAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                try {
                    store.reconnectUnchanged();
                    ownerRecovery.run();
                    retryOwnerAfter = 0;
                    log.info("RAG目录只读会话恢复完成，状态=CATALOG_OWNER_RECOVERED，代次={}", current.epoch());
                } catch (RuntimeException rejected) {
                    store.disconnectOwner();
                    retryOwnerAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    log.warn("RAG目录只读会话恢复失败，状态=CATALOG_OWNER_RECOVERY_REJECTED，原因类型={}", rejected.getClass().getSimpleName());
                    throw new IllegalStateException("CATALOG_OWNER_RECOVERY_REJECTED", rejected);
                } finally { recoveringOwner = false; }
            }
        }
        return current;
    }

    public synchronized void requireCurrent(KnowledgeCatalog snapshot) {
        if (!available || snapshot == null || snapshot != current) throw new Invalidated();
        if (store != null) store.requireOwner();
    }

    /** 只读 Wiki 副本按代次刷新；仍完整验证持久快照，不接受单行内容代替权威。 */
    public void refreshReadOnlySnapshot(javax.sql.DataSource dataSource) {
        KnowledgeCatalog previous;
        synchronized (this) {
            if (!readOnly) return;
            snapshot();
            long generation;
            try (var connection = dataSource.getConnection(); var query = connection.createStatement()) {
                query.setQueryTimeout(3);
                try (var rows = query.executeQuery("SELECT generation FROM rag_catalog_state")) {
                    if (!rows.next()) throw new Invalidated();
                    generation = rows.getLong(1);
                    if (rows.next() || generation < current.epoch()) throw new Invalidated();
                }
            } catch (java.sql.SQLException error) { throw new IllegalStateException("CATALOG_READ_ONLY_REFRESH_FAILED", error); }
            if (generation == current.epoch()) return;
            JdbcCatalogStore.State recovered = JdbcCatalogStore.readOnlySnapshot(dataSource);
            if (recovered.generation() < generation) throw new Invalidated();
            KnowledgeCatalog next = new KnowledgeCatalog(recovered.documents(), this, recovered.generation());
            previous = current;
            current = next;
        }
        try { awaitReleased(previous.epoch()); }
        finally { previous.invalidated.tryEmitEmpty(); }
    }

    public long add(KnowledgeDocument source, long expectedEpoch) { return change(source.sourceId(), source, "ADD", expectedEpoch); }
    public long update(KnowledgeDocument source, long expectedEpoch) { return change(source.sourceId(), source, "UPDATE", expectedEpoch); }
    public long revoke(String sourceId, long expectedEpoch) { return change(sourceId, null, "REVOKE", expectedEpoch); }
    long revokeApproved(String sourceId, long expectedEpoch, String operationId) {
        return change(sourceId, null, "REVOKE", expectedEpoch, operationId);
    }
    public long delete(String sourceId, long expectedEpoch) { return change(sourceId, null, "DELETE", expectedEpoch); }
    public long markUnavailable(long expectedEpoch) { return change(null, null, "UNAVAILABLE", expectedEpoch); }

    /** One Catalog transaction for the exact approved set; the operation checkpoint commits with it. */
    long publish(List<KnowledgeDocument> approved, long expectedEpoch, String operationId) {
        requireMutable();
        KnowledgeCatalog previous;
        KnowledgeCatalog next;
        synchronized (this) {
            previous = snapshot();
            if (previous.epoch() != expectedEpoch) throw new Invalidated();
            Map<String, KnowledgeDocument> sources = new LinkedHashMap<>();
            previous.documents().forEach(source -> sources.put(source.sourceId(), source));
            for (KnowledgeDocument document : approved) {
                if (!sources.containsKey(document.sourceId()) || sources.get(document.sourceId()).reviewStatus().equals("REVOKED")) {
                    throw new IllegalStateException("RELEASE_SOURCE_REVOKED_OR_MISSING");
                }
                sources.put(document.sourceId(), document);
            }
            next = new KnowledgeCatalog(List.copyOf(sources.values()), this, Math.incrementExact(previous.epoch()));
            try { store().commit(approved.stream().map(KnowledgeDocument::sourceId).toList(), "PUBLISH", next, expectedEpoch, operationId); }
            catch (RuntimeException failure) { available = false; previous.invalidated.tryEmitEmpty(); throw failure; }
            current = next;
        }
        try { awaitReleased(previous.epoch()); }
        finally { previous.invalidated.tryEmitEmpty(); }
        return next.epoch();
    }

    @Override public void close() {
        KnowledgeCatalog previous;
        synchronized (this) { available = false; previous = current; }
        if (previous != null) previous.invalidated.tryEmitEmpty();
        // Shutdown invalidates this owner; it must not advance the durable approval generation.
        try { awaitReleased(Long.MAX_VALUE); }
        finally { if (store != null) store.close(); }
    }

    private long change(String id, KnowledgeDocument replacement, String event, Long expectedEpoch) {
        return change(id, replacement, event, expectedEpoch, null);
    }

    private long change(String id, KnowledgeDocument replacement, String event, Long expectedEpoch, String operationId) {
        requireMutable();
        KnowledgeCatalog previous;
        KnowledgeCatalog next;
        RuntimeException commitFailure = null;
        synchronized (this) {
            previous = snapshot();
            if (expectedEpoch != null && previous.epoch() != expectedEpoch) throw new Invalidated();
            Map<String, KnowledgeDocument> sources = new LinkedHashMap<>();
            previous.documents().forEach(source -> sources.put(source.sourceId(), source));
            if (!event.equals("UNAVAILABLE")) {
                if (id == null || !id.matches("[a-z0-9_-]{1,80}")) throw new IllegalArgumentException("SOURCE_ID_INVALID");
                if (event.equals("ADD") == sources.containsKey(id)) throw new IllegalArgumentException("SOURCE_STATE_INVALID");
                if (event.equals("DELETE")) sources.remove(id);
                else if (event.equals("REVOKE")) {
                    KnowledgeDocument s = sources.get(id);
                    sources.put(id, new KnowledgeDocument(id, s.title(), s.text(), s.version(), s.sourceType(),
                            s.sourceUrl(), s.visibility(), "REVOKED", s.updatedAt(), s.keywords()));
                } else sources.put(id, replacement);
            }
            next = new KnowledgeCatalog(List.copyOf(sources.values()), this, Math.incrementExact(previous.epoch()));
            if (store != null) {
                try {
                    if (operationId == null) store.commit(id, event, next, previous.epoch());
                    else store.commit(List.of(id), event, next, previous.epoch(), operationId);
                }
                catch (RuntimeException error) {
                    available = false;
                    commitFailure = error;
                }
            }
            if (commitFailure == null) {
                current = next;
                if (event.equals("UNAVAILABLE")) available = false;
            }
        }
        if (commitFailure != null) {
            previous.invalidated.tryEmitEmpty();
            throw commitFailure;
        }
        try {
            awaitReleased(previous.epoch());
        } finally {
            // 回调和上游取消均在生命周期锁外；旧流即使空闲也会得到失败信号。
            previous.invalidated.tryEmitEmpty();
        }
        return next.epoch();
    }

    private void requireMutable() {
        if (readOnly) throw new IllegalStateException("CATALOG_READ_ONLY");
    }

    private synchronized void awaitReleased(long throughEpoch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        // 当前线程只能从已进入的下游回调重入；这些字节已释放，不等待自己返回。
        while (releasing.entrySet().stream().anyMatch(entry -> entry.getKey().epoch() <= throughEpoch
                && entry.getValue().keySet().stream().anyMatch(thread -> thread != Thread.currentThread()))) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("REVOCATION_COMMITTED_ACK_PENDING");
            try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("REVOCATION_COMMITTED_ACK_PENDING");
            }
        }
    }

    /** 校验与释放登记原子化；撤销确认等待已登记释放结束，不在锁内调用下游或网络。 */
    void release(KnowledgeCatalog snapshot, Runnable emission) {
        Thread thread = Thread.currentThread();
        synchronized (this) {
            requireCurrent(snapshot);
            releasing.computeIfAbsent(snapshot, ignored -> new IdentityHashMap<>()).merge(thread, 1, Integer::sum);
        }
        try { emission.run(); }
        finally {
            synchronized (this) {
                Map<Thread, Integer> active = releasing.get(snapshot);
                if (active.get(thread) == 1) active.remove(thread);
                else active.put(thread, active.get(thread) - 1);
                if (active.isEmpty()) releasing.remove(snapshot);
                notifyAll();
            }
        }
    }

    /** 最外层直接转发onNext，无检查后排队窗口；已交给下游的字节不能撤回。 */
    Flux<String> guard(KnowledgeCatalog snapshot, Flux<String> source) {
        return Flux.defer(() -> {
            requireCurrent(snapshot);
            // 完成通知触发takeUntilOther取消上游；最外层完成检查把失效转换成错误。
            Flux<String> cancellable = source.takeUntilOther(snapshot.invalidated.asMono());
            return new Flux<String>() {
                @Override public void subscribe(CoreSubscriber<? super String> actual) {
                    cancellable.subscribe(new CoreSubscriber<String>() {
                        private Subscription upstream;
                        private boolean done;
                        @Override public reactor.util.context.Context currentContext() { return actual.currentContext(); }
                        @Override public void onSubscribe(Subscription subscription) {
                            upstream = subscription;
                            actual.onSubscribe(subscription);
                        }
                        @Override public void onNext(String value) {
                            if (done) return;
                            try { release(snapshot, () -> actual.onNext(value)); }
                            catch (Invalidated error) { upstream.cancel(); onError(error); }
                        }
                        @Override public void onError(Throwable error) {
                            if (!done) { done = true; actual.onError(error); }
                        }
                        @Override public void onComplete() {
                            if (done) return;
                            try { release(snapshot, () -> { done = true; actual.onComplete(); }); }
                            catch (Invalidated error) { onError(error); }
                        }
                    });
                }
            };
        });
    }

    public static final class Invalidated extends IllegalStateException {
        public Invalidated() { super("CATALOG_AUTHORIZATION_INVALIDATED"); }
    }
}
