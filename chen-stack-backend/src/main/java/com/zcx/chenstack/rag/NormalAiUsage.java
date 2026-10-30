package com.zcx.chenstack.rag;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 正式 AI 的追加计量，不是临时费用授权；不保存提示词、回答或凭据。 */
@Component
public final class NormalAiUsage {
    public enum Purpose { CHAT, COMPILATION, RETRIEVAL }
    private static final byte[] HEADER = "{\"version\":1,\"event\":\"NORMAL_AI_USAGE\"}\n".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ConcurrentHashMap<Path, Object> MONITORS = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final Path path;
    private final Object monitor;

    public NormalAiUsage(@Value("${chen.ai.normal-usage-enabled:${NORMAL_AI_USAGE:false}}") boolean enabled,
                         @Value("${chen.ai.usage-ledger-path:}") String ledgerPath) {
        this.enabled = enabled;
        if (!enabled) { path = null; monitor = this; return; }
        if (ledgerPath == null || ledgerPath.isBlank() || !Path.of(ledgerPath).isAbsolute()) {
            throw new SecurityException("NORMAL_AI_USAGE_PATH_REQUIRED");
        }
        path = Path.of(ledgerPath).normalize();
        monitor = MONITORS.computeIfAbsent(path, ignored -> new Object());
        synchronized (monitor) {
            try {
                if (!Files.isDirectory(path.getParent()) || Files.isSymbolicLink(path)) {
                    throw new SecurityException("NORMAL_AI_USAGE_PATH_INVALID");
                }
                if (!Files.exists(path)) {
                    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        write(channel, HEADER);
                    }
                }
                checkReady();
            } catch (IOException failure) { throw closed(); }
        }
    }

    public boolean enabled() { return enabled; }

    public void checkReady() { access(null); }

    String begin(Purpose purpose, ReleaseProviderBudget.Kind kind) {
        String id = UUID.randomUUID().toString();
        event(id, purpose, "RESERVED", Map.of("kind", kind.name()));
        return id;
    }

    void sent(String id, Purpose purpose, ReleaseProviderBudget.Kind kind) { event(id, purpose, "HTTP_SENT", Map.of("kind", kind.name())); }
    void status(String id, Purpose purpose, ReleaseProviderBudget.Kind kind, int status) {
        event(id, purpose, "HTTP_STATUS", Map.of("kind", kind.name(), "httpStatus", status));
    }
    void failed(String id, Purpose purpose, ReleaseProviderBudget.Kind kind, IOException error) {
        event(id, purpose, "HTTP_FAILED", Map.of("kind", kind.name(), "exceptionClass", error.getClass().getName()));
    }

    okhttp3.ResponseBody response(String id, Purpose purpose, ReleaseProviderBudget.Kind kind, okhttp3.ResponseBody body) {
        if (body == null) return null;
        return new okhttp3.ResponseBody() {
            private final okio.Buffer capture = new okio.Buffer();
            private final AtomicBoolean recorded = new AtomicBoolean();
            private boolean oversized;
            private okio.BufferedSource source;
            @Override public okhttp3.MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return body.contentLength(); }
            @Override public okio.BufferedSource source() {
                if (source == null) source = okio.Okio.buffer(new okio.ForwardingSource(body.source()) {
                    @Override public long read(okio.Buffer sink, long byteCount) throws IOException {
                        long before = sink.size();
                        long read;
                        try { read = super.read(sink, byteCount); }
                        catch (IOException failure) { failed(id, purpose, kind, failure); complete(false); throw failure; }
                        if (read == -1) complete(true);
                        else if (!oversized && capture.size() + read <= 262_144) sink.copyTo(capture, before, read);
                        else { oversized = true; capture.clear(); }
                        return read;
                    }
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { complete(false); }
                    }
                });
                return source;
            }
            private void complete(boolean eof) {
                if (!recorded.compareAndSet(false, true)) return;
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("responseComplete", eof);
                values.put("kind", kind.name());
                values.put("inputTokens", null); values.put("outputTokens", null); values.put("totalTokens", null);
                values.put("providerCost", null); values.put("providerCostStatus", "NOT_REPORTED");
                values.put("usageStatus", eof ? "NOT_REPORTED" : "INCOMPLETE_RESPONSE");
                // SDK 可能读到 SSE DONE 后关闭而不再读取 TCP EOF；已收到的 usage 仍需计量。
                if (!oversized) {
                    String text = capture.readUtf8();
                    for (String line : text.startsWith("data:") ? text.split("\\r?\\n") : new String[]{text}) {
                        String payload = line.startsWith("data:") ? line.substring(5).strip() : line;
                        if (payload.isBlank() || "[DONE]".equals(payload)) continue;
                        try {
                            JsonNode root = JSON.readTree(payload);
                            JsonNode usage = root == null ? null : root.get("usage");
                            if (usage == null || !usage.isObject()) continue;
                            Long input = token(usage.get("prompt_tokens")), output = token(usage.get("completion_tokens"));
                            if (kind == ReleaseProviderBudget.Kind.EMBEDDING && output == null) output = 0L;
                            Long total = token(usage.get("total_tokens"));
                            values.put("inputTokens", input); values.put("outputTokens", output); values.put("totalTokens", total);
                            values.put("usageStatus", input != null && output != null && total != null ? "REPORTED" : "PARTIAL");
                        } catch (IOException ignored) { /* Provider 原始正文不进入计量或异常日志。 */ }
                    }
                } else if (oversized) values.put("usageStatus", "RESPONSE_CAPTURE_LIMIT");
                capture.clear();
                event(id, purpose, "USAGE", values);
            }
        };
    }

    private static Long token(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }

    private void event(String id, Purpose purpose, String event, Map<String, ?> values) {
        if (!enabled || id == null || purpose == null) throw new SecurityException("NORMAL_AI_USAGE_NOT_ENABLED");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("version", 1); record.put("time", Instant.now().toString()); record.put("requestId", id);
        record.put("purpose", purpose.name()); record.put("kind", "CHAT"); record.put("event", event); record.putAll(values);
        try { access((JSON.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8)); }
        catch (IOException failure) { throw closed(); }
    }

    private void access(byte[] record) {
        if (!enabled) throw new SecurityException("NORMAL_AI_USAGE_NOT_ENABLED");
        synchronized (monitor) {
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) throw closed();
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null || channel.size() < HEADER.length) throw closed();
                ByteBuffer first = ByteBuffer.allocate(HEADER.length);
                while (first.hasRemaining()) if (channel.read(first) < 0) throw closed();
                if (!java.util.Arrays.equals(HEADER, first.array())) throw closed();
                ByteBuffer last = ByteBuffer.allocate(1); channel.position(channel.size() - 1); channel.read(last);
                if (last.array()[0] != '\n') throw closed();
                if (record != null) { channel.position(channel.size()); write(channel, record); }
            } catch (IOException | java.nio.channels.OverlappingFileLockException failure) { throw closed(); }
        }
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
    }

    private static SecurityException closed() { return new SecurityException("NORMAL_AI_USAGE_PERSISTENCE_FAILED_CLOSED"); }
}
