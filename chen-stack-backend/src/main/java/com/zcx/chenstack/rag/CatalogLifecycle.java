package com.zcx.chenstack.rag;

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 单实例权威拥有者。可信宿主显式提交事件；不监听文件，也不以索引删除代替撤权。 */
public final class CatalogLifecycle implements AutoCloseable {
    private final String authorityId = UUID.randomUUID().toString();
    private KnowledgeCatalog current;
    private boolean available = true;
    private final JdbcCatalogStore store;
    private final Map<KnowledgeCatalog, Map<Thread, Integer>> releasing = new IdentityHashMap<>();

    CatalogLifecycle() { this(null); }

    CatalogLifecycle(JdbcCatalogStore store) { this.store = store; }

    synchronized void initialize(KnowledgeCatalog initial) {
        if (current != null) throw new IllegalStateException("CATALOG_ALREADY_INITIALIZED");
        current = initial;
    }

    public String authorityId() { return authorityId; }

    public synchronized KnowledgeCatalog snapshot() {
        if (!available || current == null) throw new Invalidated();
        if (store != null) store.requireOwner();
        return current;
    }

    public synchronized void requireCurrent(KnowledgeCatalog snapshot) {
        if (!available || snapshot == null || snapshot != current) throw new Invalidated();
        if (store != null) store.requireOwner();
    }

    public long add(KnowledgeDocument source, long expectedEpoch) { return change(source.sourceId(), source, "ADD", expectedEpoch); }
    public long update(KnowledgeDocument source, long expectedEpoch) { return change(source.sourceId(), source, "UPDATE", expectedEpoch); }
    public long revoke(String sourceId, long expectedEpoch) { return change(sourceId, null, "REVOKE", expectedEpoch); }
    public long delete(String sourceId, long expectedEpoch) { return change(sourceId, null, "DELETE", expectedEpoch); }
    public long markUnavailable(long expectedEpoch) { return change(null, null, "UNAVAILABLE", expectedEpoch); }

    @Override public void close() {
        try { change(null, null, "UNAVAILABLE", null); }
        catch (Invalidated alreadyUnavailable) { awaitReleased(Long.MAX_VALUE); }
        finally { if (store != null) store.close(); }
    }

    private long change(String id, KnowledgeDocument replacement, String event, Long expectedEpoch) {
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
                try { store.commit(id, event, next, previous.epoch()); }
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
