package com.zcx.chenstack.wiki;

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 每次发送独占的安全观测；不保存异常消息、正文、请求头或原始 Throwable。 */
final class WikiCallDiagnostics {
    record Snapshot(Instant occurredAt, String category, String exceptionType, String causeType, String transportExceptionType,
                    Integer httpStatus, String providerRequestId, String contentEncoding, String contentType, boolean dispatchBoundary,
                    boolean requestHeadersSent, boolean requestBodySent, boolean responseHeadersReceived,
                    boolean reliableUsage) { }

    volatile boolean dispatchBoundary;
    volatile boolean requestHeadersSent;
    volatile boolean requestBodySent;
    volatile boolean reliableUsage;
    volatile boolean cancelledByCaller;
    private final long startedAtNanos = System.nanoTime();
    private volatile boolean responseHeadersReceived;
    private volatile Integer httpStatus;
    private volatile String providerRequestId;
    private volatile String contentEncoding;
    private volatile String contentType;
    private volatile String transportExceptionType;
    private volatile boolean transportTimeout;
    private Snapshot failure;

    void responseHeaders(okhttp3.Response response, String apiKey) {
        httpStatus = response.code();
        contentEncoding = safeHeader(response.header("Content-Encoding"), false);
        contentType = safeHeader(response.header("Content-Type"), true);
        String value = response.header("x-request-id");
        if (value == null) value = response.header("request-id");
        if (value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")
                && !value.startsWith("sk-") && !value.contains(apiKey)) providerRequestId = value;
        responseHeadersReceived = true;
    }

    private static String safeHeader(String value, boolean mediaType) {
        if (value == null) return "absent";
        String normalized = (mediaType ? value.split(";", 2)[0] : value).trim().toLowerCase(java.util.Locale.ROOT);
        Set<String> allowed = mediaType
                ? Set.of("application/json", "application/problem+json", "text/event-stream", "text/html", "text/plain", "application/octet-stream")
                : Set.of("gzip", "identity", "deflate", "br", "zstd");
        return allowed.contains(normalized) ? normalized : "other";
    }

    synchronized Snapshot failure(Throwable error) {
        if (failure == null) failure = snapshot(error);
        return failure;
    }

    synchronized Snapshot current() { return failure == null ? snapshot(null) : failure; }

    void transportFailure(java.io.IOException error, long timeoutNanos) {
        transportExceptionType = error.getClass().getName();
        transportTimeout = error instanceof java.net.SocketTimeoutException
                || error instanceof java.io.InterruptedIOException && timeoutNanos > 0
                && System.nanoTime() - startedAtNanos >= timeoutNanos;
    }

    Snapshot snapshot(Throwable error) {
        String category = error == null ? "SUCCESS" : "MODEL_FAILURE";
        String causeType = null;
        boolean timeout = false, tls = false, parse = false, io = false, denied = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.size() < 16 && seen.add(cause); cause = cause.getCause()) {
            causeType = cause.getClass().getName();
            timeout |= cause instanceof java.net.SocketTimeoutException;
            tls |= cause instanceof javax.net.ssl.SSLException;
            parse |= cause instanceof com.fasterxml.jackson.core.JacksonException
                    || cause instanceof com.openai.errors.OpenAIInvalidDataException
                    || cause instanceof WikiModel.KnownUsageFailure;
            io |= cause instanceof java.io.IOException;
            denied |= cause instanceof SecurityException;
        }
        boolean cancelled = error instanceof InterruptedException || error instanceof java.util.concurrent.CancellationException
                || error != null && "WIKI_CALL_CANCELLED".equals(error.getMessage()) || cancelledByCaller && io;
        if (denied) category = "ADMISSION_DENIED";
        else if (cancelled) category = "CANCELLED";
        else if (httpStatus != null && httpStatus >= 400) category = "HTTP_ERROR";
        else if (timeout || transportTimeout) category = "TIMEOUT";
        else if (tls) category = "TLS_FAILURE";
        else if (parse) category = "RESPONSE_FORMAT";
        else if (io) category = "CONNECTION_IO";
        else if (error != null && "WIKI_USAGE_UNKNOWN".equals(error.getMessage())) category = "USAGE_UNKNOWN";
        else if (error != null && "WIKI_COMPILE_TIMEOUT".equals(error.getMessage())) category = "TIMEOUT";
        return new Snapshot(Instant.now(), category, error == null ? null : error.getClass().getName(), causeType, transportExceptionType,
                httpStatus, providerRequestId, contentEncoding, contentType, dispatchBoundary, requestHeadersSent, requestBodySent,
                responseHeadersReceived, reliableUsage);
    }
}
