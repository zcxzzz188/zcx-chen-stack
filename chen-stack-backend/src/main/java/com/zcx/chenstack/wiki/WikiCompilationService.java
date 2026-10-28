package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static com.zcx.chenstack.wiki.WikiFoundation.*;

/** 内部 admin 编译用例；独立 Provider 授权与预算，不自动调度或发布。 */
public final class WikiCompilationService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WikiCompilationService.class);
    private record Reserved(WikiCompilationStore.Attempt attempt, String manifestHash) { }
    public record Options(int maxCalls, long maxUnits, int outputTokens, Duration timeout, Set<String> topics) {
        public Options {
            if (maxCalls < 1 || maxCalls > 64 || maxUnits < 1 || maxUnits > 2_000_000
                    || outputTokens < 64 || outputTokens > 4096 || timeout == null
                    || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofMinutes(2)) > 0
                    || topics == null || topics.size() > 16) throw new IllegalArgumentException("WIKI_OPTIONS_INVALID");
            topics = Collections.unmodifiableSet(new TreeSet<>(topics.stream().map(WikiDraftCompiler::topic).toList()));
        }
        public static Options defaults() { return new Options(32, 500_000, 2048, Duration.ofSeconds(60), Set.of()); }
    }
    public record RecompileTarget(String previousRequestId, String topic, List<Long> previousSnapshots) {}
    private final WikiFoundation foundation;
    private final WikiCompilationStore store;
    private final WikiDraftCompiler compiler;
    private final WikiModel model;

    public WikiCompilationService(WikiFoundation foundation, JdbcTemplate jdbc, ObjectMapper json, WikiModel model) {
        this.foundation = Objects.requireNonNull(foundation);
        this.store = new WikiCompilationStore(jdbc);
        this.compiler = new WikiDraftCompiler(json);
        this.model = Objects.requireNonNull(model);
        if (model.id() == null || !model.id().matches("[A-Za-z0-9_.:-]{1,100}")) throw new IllegalArgumentException("WIKI_MODEL_ID_INVALID");
    }

    public Draft compile(String requestId, List<Long> snapshotIds, Options options) {
        Objects.requireNonNull(options);
        // 防止调用方外层长事务被挂起后仍在模型阶段持有其它业务锁。
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("WIKI_COMPILE_OUTER_TRANSACTION");
        Job job = queue(requestId, snapshotIds, options);
        if ("DRAFT_READY".equals(job.state())) return foundation.readDraft(requestId);
        CompilationInput input = foundation.start(requestId, current -> {
            int size = current.sources().stream().mapToInt(s -> WikiDraftCompiler.bytes(s.material().text())).sum();
            if (current.sources().size() > 16 || size > 200_000) throw new IllegalArgumentException("WIKI_INPUT_LIMIT");
            store.open(requestId, model.id(), options);
        });
        try {
            WikiDraftCompiler.Result output = compiler.compile(input, options.topics(), store.knownTopics(),
                    (phase, system, data) -> call(requestId, options, phase, system, data));
            check(requestId);
            return foundation.saveDraft(requestId, output.markdown(), () -> {
                if (store.expired(requestId)) throw new IllegalStateException("WIKI_COMPILE_TIMEOUT");
                store.topics(requestId, output);
            });
        } catch (RuntimeException failure) {
            String reason = reason(failure);
            store.stop(requestId, reason);
            foundation.stop(requestId, failure instanceof SecurityException ? "INVALIDATED" : "FAILED");
            throw failure;
        }
    }

    /** Persist the same P2 contract before handing an admin request to the bounded worker. */
    Job queue(String requestId, List<Long> snapshotIds, Options options) {
        Objects.requireNonNull(options);
        model.authorize(requestId, null);
        String contract = KnowledgeCatalog.hash(WikiDraftCompiler.VERSION + "|" + model.id() + "|" + options);
        Job job = foundation.prepare(requestId, snapshotIds, contract);
        model.authorize(requestId, job.manifestHash());
        return job;
    }

    /** 来源编辑/撤回后，返回最小受影响主题；新快照须重新显式授权，不能复用旧正文。 */
    public List<RecompileTarget> affectedTopics(SourceKey source) {
        foundation.affectedJobs(source); // 使用 P1 的 admin 权限边界与反向依赖。
        return store.affected(source).stream().map(t -> new RecompileTarget(t.requestId(), t.topic(), t.snapshots())).toList();
    }

    public void cancel(String requestId) {
        foundation.cancel(requestId, () -> store.stop(requestId, "CANCELLED"));
    }

    /** 仅终止超时遗留任务；不重放任何可能已经发送的调用。 */
    public void recover(String requestId) {
        foundation.recover(requestId, () -> {
            if (!store.expired(requestId)) throw new IllegalStateException("WIKI_LEASE_NOT_EXPIRED");
            store.abandon(requestId);
        });
    }

    private WikiModel.Reply call(String requestId, Options options, String phase, String system, String data) {
        int bytes = Math.addExact(WikiDraftCompiler.bytes(system), WikiDraftCompiler.bytes(data));
        if (bytes > 100_000) throw new IllegalArgumentException("WIKI_PROMPT_LIMIT");
        Reserved reservation = foundation.withCurrentInputs(requestId, input -> {
            running(input);
            return new Reserved(store.reserve(requestId, phase, bytes), input.job().manifestHash());
        });
        WikiCompilationStore.Attempt attempt = reservation.attempt();
        var dispatching = new java.util.concurrent.atomic.AtomicBoolean();
        WikiModel.SendContext context = new WikiModel.SendContext(requestId, reservation.manifestHash(), () -> {
            model.authorize(requestId, reservation.manifestHash());
            foundation.withCurrentInputs(requestId, input -> {
                running(input);
                if (store.expired(requestId)) throw new IllegalStateException("WIKI_COMPILE_TIMEOUT");
                if (!reservation.manifestHash().equals(input.job().manifestHash())) throw new SecurityException("WIKI_MANIFEST_CHANGED");
                store.dispatch(requestId, attempt.number());
                return null;
            });
            dispatching.set(true);
        });
        FutureTask<WikiModel.Reply> task = null;
        boolean knownUsage = false;
        try {
            task = new FutureTask<>(() -> {
                if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("WIKI_SEND_IN_TRANSACTION");
                return model.generate(system, data, options.outputTokens(), context);
            });
            Thread.ofVirtual().name("wiki-model-" + requestId).start(task);
            while (true) {
                check(requestId);
                try {
                    WikiModel.Reply reply = task.get(50, TimeUnit.MILLISECONDS);
                    if (reply == null || reply.usageTokens() != null && reply.usageTokens() < 0) throw new IllegalStateException("WIKI_USAGE_INVALID");
                    if (model.external() && (reply.usageTokens() == null || reply.usageTokens() == 0)) throw new IllegalStateException("WIKI_USAGE_UNKNOWN");
                    store.complete(requestId, attempt.number(), "SUCCEEDED", reply.usageTokens());
                    knownUsage = true;
                    if (reply.usageTokens() != null && reply.usageTokens() > (long) bytes + options.outputTokens()) {
                        throw new IllegalStateException("WIKI_USAGE_EXCEEDS_RESERVATION");
                    }
                    check(requestId);
                    return reply;
                } catch (TimeoutException polling) {
                    // 每 50ms 重验来源与取消；已发送给第三方的内容无法撤回。
                }
            }
        } catch (InterruptedException interrupted) {
            diagnostic(requestId, attempt.number(), phase, context, interrupted);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WIKI_COMPILE_INTERRUPTED");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof WikiModel.KnownUsageFailure known && known.reply().usageTokens() != null
                    && known.reply().usageTokens() > 0) {
                store.complete(requestId, attempt.number(), "SUCCEEDED", known.reply().usageTokens());
                knownUsage = true;
            }
            diagnostic(requestId, attempt.number(), phase, context, failure.getCause());
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                String code = cause.getMessage();
                if (code != null && code.matches("WIKI_[A-Z_]{1,35}")) {
                    if (cause instanceof SecurityException) throw new SecurityException(code);
                    throw new IllegalStateException(code);
                }
            }
            throw new IllegalStateException("WIKI_MODEL_FAILED");
        } catch (RuntimeException failure) {
            diagnostic(requestId, attempt.number(), phase, context, failure);
            throw failure;
        } finally {
            context.cancel();
            if (task != null && !task.isDone()) task.cancel(true);
            try { store.complete(requestId, attempt.number(), dispatching.get() ? "UNKNOWN" : "NOT_SENT", null); }
            finally { if (dispatching.get() && !knownUsage) model.outcomeUnknown(); }
        }
    }

    private static void diagnostic(String requestId, int attempt, String phase, WikiModel.SendContext context, Throwable failure) {
        // 仅输出白名单字段；不可传入 Throwable，否则 SDK 的错误消息可能包含敏感响应正文。
        LOG.warn("WIKI_MODEL_DIAGNOSTIC requestId={} attempt={} phase={} details={}",
                requestId, attempt, phase, context.diagnostics().failure(failure));
    }

    private void check(String requestId) {
        foundation.withCurrentInputs(requestId, input -> {
            running(input);
            if (store.expired(requestId)) throw new IllegalStateException("WIKI_COMPILE_TIMEOUT");
            return null;
        });
    }

    private static void running(CompilationInput input) {
        if (!"RUNNING".equals(input.job().state())) throw new IllegalStateException("WIKI_JOB_NOT_RUNNING");
    }

    private static String reason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message != null && message.startsWith("WIKI_") && message.matches("[A-Z_]{1,40}")) return message;
        return failure instanceof SecurityException ? "SOURCE_INVALIDATED" : "COMPILER_FAILED";
    }
}
