package com.zcx.chenstack.rag;

import com.zcx.chenstack.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 无自动重试；Embedding 成本计数独立于用户聊天额度。 */
public final class GuardedEmbeddingModel extends AbstractEmbeddingModel {
    private final EmbeddingModel delegate;
    private final RagProperties properties;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong inputChars = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong reportedTokens = new AtomicLong();
    private LocalDate day = LocalDate.now(ZoneOffset.UTC);
    private int dailyRequests;
    private volatile Runnable publicationReservation = () -> {};

    void publicationReservation(Runnable reservation) { publicationReservation = reservation; }

    public GuardedEmbeddingModel(EmbeddingModel delegate, RagProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
        properties.validate();
    }

    @Override public int dimensions() { return 1024; }
    @Override public float[] embed(Document document) { return embed(document.getText()); }

    private synchronized void reserve() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(day)) { day = today; dailyRequests = 0; }
        // shortcut: 每进程日上限，生产多实例或重启防绕过需要独立持久化成本账本。
        if (dailyRequests >= properties.getMaxEmbeddingRequestsPerDay()) throw new RagFailure(RagFailure.Code.COST_LIMIT);
        dailyRequests++;
    }

    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        if (request.getInstructions().isEmpty() || request.getInstructions().size() > 10
                || request.getInstructions().stream().anyMatch(s -> s == null || s.isBlank()
                    || s.length() > properties.getMaxEmbeddingInputChars())) {
            throw new RagFailure(RagFailure.Code.EMBEDDING_UNAVAILABLE);
        }
        reserve();
        publicationReservation.run();
        requests.incrementAndGet();
        inputChars.addAndGet(request.getInstructions().stream().mapToLong(String::length).sum());
        try {
            EmbeddingResponse response = delegate.call(new EmbeddingRequest(request.getInstructions(), null));
            var usage = response.getMetadata().getUsage();
            if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() >= 0) {
                reportedTokens.addAndGet(usage.getTotalTokens());
            }
            if (response.getResults().size() != request.getInstructions().size()
                    || response.getResults().stream().anyMatch(e -> e.getOutput().length != dimensions())) {
                throw new RagFailure(RagFailure.Code.DIMENSION_MISMATCH);
            }
            for (var result : response.getResults()) {
                double norm = 0;
                for (float value : result.getOutput()) {
                    if (!Float.isFinite(value)) throw new RagFailure(RagFailure.Code.DIMENSION_MISMATCH);
                    norm += value * (double) value;
                }
                if (norm == 0) throw new RagFailure(RagFailure.Code.DIMENSION_MISMATCH);
            }
            return response;
        } catch (RagFailure failure) { failures.incrementAndGet(); throw failure; }
        catch (Exception failure) { failures.incrementAndGet(); throw new RagFailure(RagFailure.Code.EMBEDDING_UNAVAILABLE); }
    }

    public Map<String, Long> counters() {
        return Map.of("requests", requests.get(), "inputChars", inputChars.get(), "failures", failures.get(), "reportedTokens", reportedTokens.get());
    }
}
