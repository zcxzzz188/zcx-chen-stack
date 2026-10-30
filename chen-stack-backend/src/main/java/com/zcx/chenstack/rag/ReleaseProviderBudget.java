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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 本轮运维费用账本；发送前落盘，失败或UNKNOWN不退款，目录ACL由可信运维账户维护。 */
@lombok.extern.slf4j.Slf4j
public final class ReleaseProviderBudget {
    public enum Kind { EMBEDDING, CHAT }
    private record HttpSend(AtomicBoolean dispatched, String usageId) { }
    private final Path path;
    private final String header;
    private final String budgetId;
    private final int embeddingLimit;
    private final int chatLimit;
    private final NormalAiUsage normalUsage;
    private final NormalAiUsage.Purpose purpose;

    public ReleaseProviderBudget(String ledgerPath, String budgetId, int embeddingLimit, int chatLimit) {
        this(ledgerPath, budgetId, embeddingLimit, chatLimit, true);
    }

    private ReleaseProviderBudget(String ledgerPath, String budgetId, int embeddingLimit, int chatLimit, boolean checkLedger) {
        normalUsage = null; purpose = null;
        this.budgetId = budgetId == null || budgetId.isBlank() ? "UNCONFIGURED" : budgetId;
        if ((ledgerPath == null || ledgerPath.isBlank()) && (budgetId == null || budgetId.isBlank())) {
            path = null; header = null; this.embeddingLimit = 0; this.chatLimit = 0;
            return;
        }
        if (ledgerPath == null || !Path.of(ledgerPath).isAbsolute() || budgetId == null
                || !budgetId.matches("[A-Za-z0-9._-]{1,80}") || embeddingLimit < 0 || embeddingLimit > 64
                || chatLimit < 0 || chatLimit > 100) throw new SecurityException("RELEASE_BUDGET_CONFIGURATION_INVALID");
        path = Path.of(ledgerPath);
        this.embeddingLimit = embeddingLimit; this.chatLimit = chatLimit;
        header = "CHEN_RELEASE_HTTP_BUDGET_V1|" + budgetId + "|" + embeddingLimit + "|";
        if (checkLedger) snapshot(); // 不自动创建缺失账本，避免重启/路径拼错重置费用。
    }

    private ReleaseProviderBudget(NormalAiUsage usage, NormalAiUsage.Purpose purpose) {
        if (usage == null || !usage.enabled() || purpose == null) throw new SecurityException("NORMAL_AI_USAGE_NOT_ENABLED");
        usage.checkReady();
        normalUsage = usage; this.purpose = purpose;
        path = null; header = null; budgetId = "normal-" + purpose.name().toLowerCase(java.util.Locale.ROOT);
        embeddingLimit = 0; chatLimit = 0;
    }

    public static ReleaseProviderBudget continuous(NormalAiUsage usage, NormalAiUsage.Purpose purpose) {
        return new ReleaseProviderBudget(usage, purpose);
    }

    public boolean configured() { return path != null || normalUsage != null; }

    /** SDK 的同步与流式发送共用门禁；禁止底层重发和跨地址重定向。 */
    public com.openai.core.http.HttpClient httpClient(Kind kind, Duration timeout, String endpoint) {
        return new com.openai.client.okhttp.OkHttpClient(httpTransport(kind, timeout, endpoint));
    }

    /** 独立调用可在同一安全 transport 上附加来源门禁和取消；不共享 G2 的账本实例。 */
    public okhttp3.OkHttpClient httpTransport(Kind kind, Duration timeout, String endpoint) {
        var url = okhttp3.HttpUrl.get(endpoint);
        NormalAiUsage.Purpose requestPurpose = kind == Kind.EMBEDDING ? NormalAiUsage.Purpose.RETRIEVAL : purpose;
        return new okhttp3.OkHttpClient.Builder().connectTimeout(timeout).readTimeout(timeout).callTimeout(timeout)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .eventListenerFactory(call -> new okhttp3.EventListener() {
                    private final String auditId = UUID.randomUUID().toString();
                    @Override public void requestHeadersEnd(okhttp3.Call current, okhttp3.Request request) {
                        HttpSend send = request.tag(HttpSend.class);
                        if (normalUsage != null && send != null) normalUsage.sent(send.usageId(), requestPurpose, kind);
                        var target = request.url();
                        String path = java.util.Set.of("/chat/completions", "/v1/chat/completions", "/embeddings", "/v1/embeddings")
                                .contains(target.encodedPath()) ? target.encodedPath() : "<redacted>";
                        log.info("RELEASE_HTTP_AUDIT event=request_headers_end audit_id={} budget_id={} kind={} method={} host={} port={} path={}",
                                auditId, budgetId, kind, request.method(), target.host(), target.port(), path);
                    }
                    @Override public void responseHeadersEnd(okhttp3.Call current, okhttp3.Response response) {
                        HttpSend send = response.request().tag(HttpSend.class);
                        if (normalUsage != null && send != null) normalUsage.status(send.usageId(), requestPurpose, kind, response.code());
                        log.info("RELEASE_HTTP_AUDIT event=response_headers_end audit_id={} status={}", auditId, response.code());
                    }
                    @Override public void callFailed(okhttp3.Call current, IOException failure) {
                        HttpSend send = current.request().tag(HttpSend.class);
                        if (normalUsage != null && send != null) normalUsage.failed(send.usageId(), requestPurpose, kind, failure);
                        log.info("RELEASE_HTTP_AUDIT event=call_failed audit_id={} exception_class={}", auditId, failure.getClass().getName());
                    }
                })
                .addInterceptor(chain -> {
                    try { reserve(kind); }
                    catch (SecurityException denied) { throw new IOException("RELEASE_PROVIDER_SEND_BLOCKED", denied); }
                    String usageId = normalUsage == null ? null : normalUsage.begin(requestPurpose, kind);
                    try {
                        okhttp3.Response response = chain.proceed(chain.request().newBuilder().url(url)
                                .tag(HttpSend.class, new HttpSend(new AtomicBoolean(), usageId)).build());
                        // 应用拦截器读取透明解压后的正文；网络层仍只负责阻止重复发送。
                        return normalUsage == null ? response : response.newBuilder()
                                .body(normalUsage.response(usageId, requestPurpose, kind, response.body())).build();
                    } catch (IOException failure) {
                        if (normalUsage != null) normalUsage.failed(usageId, requestPurpose, kind, failure);
                        throw failure;
                    }
                })
                .addNetworkInterceptor(chain -> {
                    // 503 Retry-After:0 仍可能触发内部 follow-up；每个 Call 只放行一次网络发送。
                    HttpSend send = chain.request().tag(HttpSend.class);
                    if (send == null || !send.dispatched().compareAndSet(false, true)) {
                        throw new IOException("RELEASE_PROVIDER_RETRY_BLOCKED");
                    }
                    if (kind != Kind.CHAT) {
                        return chain.proceed(chain.request());
                    }
                    String traceId = UUID.randomUUID().toString();
                    var captured = new okio.Buffer();
                    var oversized = new AtomicBoolean();
                    var request = chain.request();
                    if (request.body() != null) request = request.newBuilder()
                            .method(request.method(), diagnosticBody(request.body(), captured, oversized)).build();
                    okhttp3.Response response;
                    try { response = chain.proceed(request); }
                    finally {
                        log.info("PROVIDER_REQUEST_SCHEMA budget_id={} trace_id={} summary={}", budgetId, traceId,
                                ProviderDiagnostics.request(oversized.get() ? null : captured.readByteArray()));
                    }
                    if (response.code() == 400) {
                        String summary;
                        try { summary = ProviderDiagnostics.error(response.peekBody(65_537).bytes()); }
                        catch (IOException unavailable) { summary = "{\"diagnostic\":\"ERROR_BODY_UNAVAILABLE\"}"; }
                        String requestId = response.header("x-request-id", response.header("x-ds-request-id", ""));
                        String requestIdHash;
                        try { requestIdHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(requestId.getBytes(StandardCharsets.UTF_8))); }
                        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                        log.warn("PROVIDER_BAD_REQUEST budget_id={} trace_id={} status=400 request_id_sha256={} summary={}",
                                budgetId, traceId, requestIdHash, summary);
                    }
                    return response;
                }).build();
    }

    private static okhttp3.RequestBody diagnosticBody(okhttp3.RequestBody body, okio.Buffer captured, AtomicBoolean oversized) {
        return new okhttp3.RequestBody() {
            @Override public okhttp3.MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() throws IOException { return body.contentLength(); }
            @Override public boolean isOneShot() { return body.isOneShot(); }
            @Override public boolean isDuplex() { return body.isDuplex(); }
            @Override public void writeTo(okio.BufferedSink sink) throws IOException {
                var tee = okio.Okio.buffer(new okio.ForwardingSink(sink) {
                    @Override public void write(okio.Buffer source, long byteCount) throws IOException {
                        if (!oversized.get() && captured.size() + byteCount <= 65_536) source.copyTo(captured, 0, byteCount);
                        else { oversized.set(true); captured.clear(); }
                        super.write(source, byteCount);
                    }
                });
                body.writeTo(tee);
                tee.flush();
            }
        };
    }

    public ClientHttpRequestInterceptor restInterceptor(Kind kind) {
        return (request, body, execution) -> {
            if (normalUsage != null) throw new SecurityException("NORMAL_AI_USAGE_REQUIRES_GUARDED_TRANSPORT");
            reserve(kind); return execution.execute(request, body);
        };
    }

    public ExchangeFilterFunction streamFilter(Kind kind) {
        return (request, next) -> Mono.defer(() -> {
            if (normalUsage != null) throw new SecurityException("NORMAL_AI_USAGE_REQUIRES_GUARDED_TRANSPORT");
            reserve(kind); return next.exchange(request);
        });
    }

    public synchronized void reserve(Kind kind) {
        if (normalUsage != null) { normalUsage.checkReady(); return; }
        access(kind, 0, null, null);
    }

    public synchronized Map<String, Long> snapshot() {
        if (normalUsage != null) throw new IllegalStateException("NORMAL_AI_USAGE_REQUIRES_EVENT_ACCOUNTING");
        return access(null, 0, null, null);
    }

    /** 只检查发送所需余量，不预扣；真正发送仍须逐次 reserve。 */
    public synchronized void checkChatCapacity(int minimumAttempts) {
        if (minimumAttempts < 1 || minimumAttempts > 100) {
            throw new SecurityException("RELEASE_BUDGET_CAPACITY_CHECK_INVALID");
        }
        if (normalUsage != null) { normalUsage.checkReady(); return; }
        access(null, minimumAttempts, null, null);
    }

    /** 运维显式扩容；原头、请求字节及累计计数不变，目录 ACL 仍是操作授权边界。 */
    public static Map<String, Long> extendChatLimit(String ledgerPath, String budgetId, int embeddingLimit,
            int expectedOldLimit, int newLimit, String operator, String expectedLedgerSha256) {
        if (expectedOldLimit != 20 || newLimit != 100 || !validOperator(operator)
                || expectedLedgerSha256 == null || !expectedLedgerSha256.matches("[a-fA-F0-9]{64}")) {
            throw new SecurityException("RELEASE_BUDGET_EXTENSION_INVALID");
        }
        ReleaseProviderBudget budget = new ReleaseProviderBudget(ledgerPath, budgetId, embeddingLimit, expectedOldLimit, false);
        if (!budget.configured()) throw new SecurityException("RELEASE_BUDGET_LEDGER_REQUIRED");
        return budget.access(null, 0, operator, expectedLedgerSha256);
    }

    public static void main(String[] args) {
        if (args.length == 5 && "inspect".equals(args[0])) {
            int embeddingLimit = Integer.parseInt(args[3]);
            int chatLimit = Integer.parseInt(args[4]);
            Map<String, Long> counts = new ReleaseProviderBudget(args[1], args[2], embeddingLimit, chatLimit).snapshot();
            System.out.println("CHAT_USED=" + counts.get("chat") + " CHAT_LIMIT=" + chatLimit
                    + " CHAT_REMAINING=" + (chatLimit - counts.get("chat"))
                    + " EMBEDDING_USED=" + counts.get("embedding") + " EMBEDDING_LIMIT=" + embeddingLimit);
            return;
        }
        if (args.length != 8 || !"extend-chat".equals(args[0])) {
            throw new IllegalArgumentException("extend-chat <absoluteLedgerPath> <budgetId> <embeddingLimit> <expectedOldLimit> <newLimit> <operator> <expectedLedgerSha256>");
        }
        Map<String, Long> counts = extendChatLimit(args[1], args[2], Integer.parseInt(args[3]),
                Integer.parseInt(args[4]), Integer.parseInt(args[5]), args[6], args[7]);
        System.out.println("RELEASE_CHAT_BUDGET_EXTENDED chatLimit=100 embeddingUsed=" + counts.get("embedding") + " chatUsed=" + counts.get("chat"));
    }

    private static boolean validOperator(String operator) {
        return operator != null && operator.matches("[A-Za-z0-9._-]{1,80}");
    }

    private Map<String, Long> access(Kind kind, int minimumChatAttempts, String operator, String expectedLedgerSha256) {
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
            if (!lines[0].startsWith(header)) throw new SecurityException("RELEASE_BUDGET_ID_OR_LIMIT_MISMATCH");
            String initialLimit = lines[0].substring(header.length());
            if (!initialLimit.matches("0|[1-9][0-9]?") || Integer.parseInt(initialLimit) > 20) {
                throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
            }
            int effectiveChatLimit = Integer.parseInt(initialLimit);
            long embedding = 0, chat = 0, sequence = 0;
            for (int i = 1; i < lines.length - 1; i++) {
                String[] fields = lines[i].split("\\|", -1);
                if ((fields.length != 4 && fields.length != 8) || !fields[0].matches("[1-9][0-9]{0,2}")
                        || !fields[2].matches("[0-9]{1,2}") || !fields[3].matches("[0-9]{1,3}")) {
                    throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
                }
                sequence++;
                if (fields.length == 8) {
                    if (!"CHAT_LIMIT".equals(fields[1]) || effectiveChatLimit != 20
                            || !"20".equals(fields[4]) || !"100".equals(fields[5]) || !validOperator(fields[7])) {
                        throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
                    }
                    Instant.parse(fields[6]);
                    effectiveChatLimit = 100;
                } else {
                    switch (fields[1]) {
                        case "EMBEDDING" -> embedding++;
                        case "CHAT" -> chat++;
                        default -> throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
                    }
                }
                if (Long.parseLong(fields[0]) != sequence || Long.parseLong(fields[2]) != embedding
                        || Long.parseLong(fields[3]) != chat || embedding > embeddingLimit || chat > effectiveChatLimit) {
                    throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
                }
            }
            if (effectiveChatLimit != chatLimit) throw new SecurityException("RELEASE_BUDGET_ID_OR_LIMIT_MISMATCH");
            if (chatLimit - chat < minimumChatAttempts) throw new SecurityException("RELEASE_PROVIDER_HTTP_BUDGET_EXHAUSTED");
            if (operator != null) {
                String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
                if (!actualHash.equalsIgnoreCase(expectedLedgerSha256)) throw new SecurityException("RELEASE_BUDGET_LEDGER_HASH_MISMATCH");
                append(channel, (sequence + 1) + "|CHAT_LIMIT|" + embedding + "|" + chat + "|20|100|" + Instant.now() + "|" + operator + "\n");
            }
            if (kind != null) {
                if (kind == Kind.EMBEDDING && embedding >= embeddingLimit || kind == Kind.CHAT && chat >= chatLimit) {
                    throw new SecurityException("RELEASE_PROVIDER_HTTP_BUDGET_EXHAUSTED");
                }
                if (kind == Kind.EMBEDDING) embedding++; else chat++;
                append(channel, (sequence + 1) + "|" + kind + "|" + embedding + "|" + chat + "\n");
            }
            return Map.of("embedding", embedding, "chat", chat);
        } catch (DateTimeParseException error) {
            throw new SecurityException("RELEASE_BUDGET_LEDGER_INVALID");
        } catch (IOException | NoSuchAlgorithmException | java.nio.channels.OverlappingFileLockException error) {
            throw new SecurityException("RELEASE_BUDGET_PERSISTENCE_FAILED_CLOSED");
        }
    }

    private static void append(FileChannel channel, String record) throws IOException {
        channel.position(channel.size());
        ByteBuffer bytes = ByteBuffer.wrap(record.getBytes(StandardCharsets.US_ASCII));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    }
}
