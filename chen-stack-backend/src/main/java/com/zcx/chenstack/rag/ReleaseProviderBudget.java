package com.zcx.chenstack.rag;

import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** 本轮运维费用账本；发送前落盘，失败或UNKNOWN不退款，目录ACL由可信运维账户维护。 */
public final class ReleaseProviderBudget {
    public enum Kind { EMBEDDING, CHAT }
    private final Path path;
    private final String header;
    private final int embeddingLimit;
    private final int chatLimit;

    public ReleaseProviderBudget(String ledgerPath, String budgetId, int embeddingLimit, int chatLimit) {
        if ((ledgerPath == null || ledgerPath.isBlank()) && (budgetId == null || budgetId.isBlank())) {
            path = null; header = null; this.embeddingLimit = 0; this.chatLimit = 0;
            return;
        }
        if (ledgerPath == null || !Path.of(ledgerPath).isAbsolute() || budgetId == null
                || !budgetId.matches("[A-Za-z0-9._-]{1,80}") || embeddingLimit < 0 || embeddingLimit > 64
                || chatLimit < 0 || chatLimit > 20) throw new SecurityException("RELEASE_BUDGET_CONFIGURATION_INVALID");
        path = Path.of(ledgerPath);
        this.embeddingLimit = embeddingLimit; this.chatLimit = chatLimit;
        header = "CHEN_RELEASE_HTTP_BUDGET_V1|" + budgetId + "|" + embeddingLimit + "|" + chatLimit;
        snapshot(); // 不自动创建缺失账本，避免重启/路径拼错重置费用。
    }

    public boolean configured() { return path != null; }

    public ClientHttpRequestInterceptor restInterceptor(Kind kind) {
        return (request, body, execution) -> { reserve(kind); return execution.execute(request, body); };
    }

    public ExchangeFilterFunction streamFilter(Kind kind) {
        return (request, next) -> Mono.defer(() -> { reserve(kind); return next.exchange(request); });
    }

    public synchronized void reserve(Kind kind) { access(kind); }

    public synchronized Map<String, Long> snapshot() { return access(null); }

    private Map<String, Long> access(Kind kind) {
        if (path == null) return Map.of("embedding", 0L, "chat", 0L);
        if (!Files.isRegularFile(path)) throw new SecurityException("RELEASE_BUDGET_LEDGER_REQUIRED");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null || channel.size() < 1 || channel.size() > 65_536) throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
            ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(channel.size()));
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
            String text = new String(bytes.array(), StandardCharsets.US_ASCII);
            if (!text.endsWith("\n") || text.indexOf('\r') >= 0) throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
            String[] lines = text.split("\n", -1);
            if (!header.equals(lines[0])) throw new SecurityException("RELEASE_BUDGET_ID_OR_LIMIT_MISMATCH");
            long embedding = 0, chat = 0, sequence = 0;
            for (int i = 1; i < lines.length - 1; i++) {
                String[] fields = lines[i].split("\\|", -1);
                if (fields.length != 4 || !fields[0].matches("[1-9][0-9]{0,2}")
                        || !fields[2].matches("[0-9]{1,2}") || !fields[3].matches("[0-9]{1,2}")) {
                    throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
                }
                sequence++;
                switch (fields[1]) {
                    case "EMBEDDING" -> embedding++;
                    case "CHAT" -> chat++;
                    default -> throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
                }
                if (Long.parseLong(fields[0]) != sequence || Long.parseLong(fields[2]) != embedding
                        || Long.parseLong(fields[3]) != chat || embedding > embeddingLimit || chat > chatLimit) {
                    throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
                }
            }
            if (kind != null) {
                if (kind == Kind.EMBEDDING && embedding >= embeddingLimit || kind == Kind.CHAT && chat >= chatLimit) {
                    throw new SecurityException("RELEASE_PROVIDER_HTTP_BUDGET_EXHAUSTED");
                }
                if (kind == Kind.EMBEDDING) embedding++; else chat++;
                byte[] record = ((sequence + 1) + "|" + kind + "|" + embedding + "|" + chat + "\n").getBytes(StandardCharsets.US_ASCII);
                channel.position(channel.size());
                ByteBuffer append = ByteBuffer.wrap(record);
                while (append.hasRemaining()) channel.write(append);
                channel.force(true);
            }
            return Map.of("embedding", embedding, "chat", chat);
        } catch (IOException | java.nio.channels.OverlappingFileLockException error) {
            throw new SecurityException("RELEASE_BUDGET_PERSISTENCE_FAILED_CLOSED");
        }
    }
}