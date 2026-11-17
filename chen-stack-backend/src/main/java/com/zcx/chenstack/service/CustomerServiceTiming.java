package com.zcx.chenstack.service;

import org.springframework.ai.chat.metadata.Usage;
import reactor.util.context.ContextView;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 客服请求内时序，仅记录系统生成的关联 ID、阶段与数值。 */
@lombok.extern.slf4j.Slf4j
public final class CustomerServiceTiming {
    public static final String ATTRIBUTE = CustomerServiceTiming.class.getName();
    private static final CustomerServiceTiming NONE = new CustomerServiceTiming(null);
    private static final ThreadLocal<CustomerServiceTiming> CURRENT = new ThreadLocal<>();
    private final String requestId;
    private final long started = System.nanoTime();
    private final AtomicBoolean firstSseText = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger usageCount = new AtomicInteger();
    private final AtomicInteger input = new AtomicInteger();
    private final AtomicInteger output = new AtomicInteger();
    private volatile String mode = "unresolved";
    private volatile String route = "unresolved";

    private CustomerServiceTiming(String requestId) { this.requestId = requestId; }

    public static CustomerServiceTiming start() { return new CustomerServiceTiming(UUID.randomUUID().toString()); }
    public static CustomerServiceTiming disabled() { return NONE; }
    public static CustomerServiceTiming from(ContextView context) { return context.getOrDefault(CustomerServiceTiming.class, NONE); }
    public static CustomerServiceTiming current() { return CURRENT.get() == null ? NONE : CURRENT.get(); }

    public <T> T within(Supplier<T> work) {
        CustomerServiceTiming previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public void mode(String mode) { if (requestId != null) this.mode = mode; }

    public void route(String route, long start) {
        if (requestId == null) return;
        this.route = route;
        stage("ROUTE", start);
    }

    public void stage(String name, long from) { stage(name, from, 0); }

    private void stage(String name, long from, int modelRound) {
        if (requestId == null) return;
        long end = System.nanoTime();
        log.info("CUSTOMER_SERVICE_TIMING request_id={} stage={} mode={} route={} model_round={} start_us={} end_us={}",
                requestId, name, mode, route, modelRound, (from - started) / 1_000, (end - started) / 1_000);
    }

    public ModelRequest modelRequest() {
        return new ModelRequest(requestId == null ? 0 : calls.incrementAndGet());
    }

    public void firstSseText() {
        if (requestId != null && firstSseText.compareAndSet(false, true)) stage("SSE_FIRST_TEXT", started);
    }

    public void finish(String terminal) {
        if (requestId == null) return;
        log.info("CUSTOMER_SERVICE_TIMING request_id={} stage=STREAM_COMPLETE mode={} route={} terminal={} start_us=0 end_us={} model_calls={} usage_rounds={} input_tokens={} output_tokens={}",
                requestId, mode, route, terminal, (System.nanoTime() - started) / 1_000, calls.get(), usageCount.get(),
                usageCount.get() == calls.get() ? input.get() : null, usageCount.get() == calls.get() ? output.get() : null);
    }

    public final class ModelRequest {
        private final int round;
        private final long started = System.nanoTime();
        private final AtomicBoolean first = new AtomicBoolean();
        private final AtomicBoolean measuredUsage = new AtomicBoolean();

        private ModelRequest(int round) { this.round = round; }

        public void text(String text) {
            if (requestId != null && text != null && !text.isBlank() && first.compareAndSet(false, true)) {
                stage("PROVIDER_FIRST_TEXT", started, round);
            }
        }

        public void usage(Usage usage) {
            if (requestId == null || usage == null || usage.getPromptTokens() == null || usage.getCompletionTokens() == null
                    || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0 || !measuredUsage.compareAndSet(false, true)) return;
            input.addAndGet(usage.getPromptTokens());
            output.addAndGet(usage.getCompletionTokens());
            usageCount.incrementAndGet();
        }

        public void finish() { stage("MODEL_REQUEST", started, round); }
    }
}
