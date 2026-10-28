package com.zcx.chenstack.wiki;

/** 模型仅返回不可信 JSON 建议；没有来源、状态、工具或发布权限。 */
public interface WikiModel {
    record Reply(String json, Long usageTokens, Long inputTokens, Long outputTokens) {
        public Reply(String json, Long usageTokens) { this(json, usageTokens, null, null); }
    }

    /** 编译失败与已知用量分别记录，不能因输出截断丢弃可靠usage。 */
    final class KnownUsageFailure extends IllegalStateException {
        private final Reply reply;
        public KnownUsageFailure(String code, Reply reply) { super(code); this.reply = reply; }
        public Reply reply() { return reply; }
    }

    String id();

    /** 未实现显式授权的外部模型默认拒绝。 */
    default boolean external() { return true; }

    default void authorize(String requestId, String manifestHash) {
        if (external()) throw new SecurityException("WIKI_REAL_PROVIDER_NOT_AUTHORIZED");
    }

    default void outcomeUnknown() { }

    Reply generate(String system, String data, int maxOutputTokens);

    default Reply generate(String system, String data, int maxOutputTokens, SendContext context) {
        context.beforeSend();
        return generate(system, data, maxOutputTokens);
    }

    /** 每次调用独占；取消先于 Call 登记也必须阻止该 Call，不能影响其他任务。 */
    final class SendContext {
        private final Runnable admission;
        private final String requestId;
        private final String manifestHash;
        private final WikiCallDiagnostics diagnostics = new WikiCallDiagnostics();
        private final java.util.concurrent.atomic.AtomicBoolean used = new java.util.concurrent.atomic.AtomicBoolean();
        private boolean cancelled;
        private Runnable cancelCall;

        public SendContext(Runnable admission) { this(null, null, admission); }
        public SendContext(String requestId, String manifestHash, Runnable admission) {
            this.requestId = requestId; this.manifestHash = manifestHash;
            this.admission = java.util.Objects.requireNonNull(admission);
        }
        public String requestId() { return requestId; }
        public String manifestHash() { return manifestHash; }
        WikiCallDiagnostics diagnostics() { return diagnostics; }
        public synchronized void onCancel(Runnable callback) {
            cancelCall = callback;
            if (cancelled) callback.run();
        }
        public synchronized void cancel() {
            diagnostics.cancelledByCaller = true;
            cancelled = true;
            if (cancelCall != null) cancelCall.run();
        }
        public synchronized void requireActive() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IllegalStateException("WIKI_CALL_CANCELLED");
        }
        public void beforeSend() {
            requireActive();
            if (!used.compareAndSet(false, true)) throw new SecurityException("WIKI_SEND_ALREADY_CLAIMED");
            admission.run();
            requireActive();
        }
    }
}
