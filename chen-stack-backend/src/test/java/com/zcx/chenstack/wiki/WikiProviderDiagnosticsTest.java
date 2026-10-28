package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Only numeric loopback, synthetic credentials and independent disposable ledgers. */
class WikiProviderDiagnosticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "synthetic-diagnostic-secret";
    private static final String PRIVATE_TEXT = "synthetic-private-body-sentinel";
    private static final String MANIFEST = "d".repeat(64);
    @TempDir Path temporary;

    enum Case { HTTP_400, HTTP_401, HTTP_429, HTTP_500, DISCONNECT, BODY_TIMEOUT,
        INVALID_JSON, INVALID_CHOICES, TRUNCATED_OUTPUT, MISSING_USAGE, BAD_REQUEST_ID, SUCCESS, GZIP_SUCCESS, GZIP_OVERSIZED }

    @Test void wrappedAdmissionCancellationAndUnprovenInterruptionAreNotConnectionOrTimeoutGuesses() {
        var denied = new WikiCallDiagnostics();
        assertEquals("ADMISSION_DENIED", denied.failure(new java.io.IOException("not logged", new SecurityException("private"))).category());
        var cancelled = new WikiCallDiagnostics(); cancelled.cancelledByCaller = true;
        assertEquals("CANCELLED", cancelled.failure(new java.io.InterruptedIOException("not logged")).category());
        assertEquals("CANCELLED", new WikiCallDiagnostics().failure(new InterruptedException("not logged")).category());
        assertEquals("CONNECTION_IO", new WikiCallDiagnostics().failure(new java.io.InterruptedIOException("not enough evidence")).category());
    }

    @Test void gzipResponseIsDecodedBeforeUsageValidation() throws Exception { runScenario(Case.GZIP_SUCCESS); }
    @Test void gzipDecodedResponseLimitStillRejectsExpansion() throws Exception { runScenario(Case.GZIP_OVERSIZED); }
    @Test void plainJsonResponseMetadataIsCaptured() throws Exception { runScenario(Case.SUCCESS); }

    @Test void metadataParametersAndUnlistedValuesCannotLeakSecrets() {
        var diagnostics = new WikiCallDiagnostics();
        var response = new okhttp3.Response.Builder().request(new okhttp3.Request.Builder().url("http://127.0.0.1/").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                .header("Content-Type", "Application/JSON; private=" + KEY)
                .header("Content-Encoding", KEY).header("x-request-id", KEY).build();
        diagnostics.responseHeaders(response, KEY);
        var snapshot = diagnostics.current();
        assertEquals("application/json", snapshot.contentType()); assertEquals("other", snapshot.contentEncoding());
        assertNull(snapshot.providerRequestId()); assertFalse(snapshot.toString().contains(KEY));
    }

    @ParameterizedTest @EnumSource(value = Case.class, names = {"GZIP_SUCCESS", "GZIP_OVERSIZED"}, mode = EnumSource.Mode.EXCLUDE)
    void safeDiagnosticsRetainTransportEvidenceWithoutRetryOrSecrets(Case scenario) throws Exception {
        runScenario(scenario);
    }

    private void runScenario(Case scenario) throws Exception {
        AtomicInteger received = new AtomicInteger();
        var requestedEncoding = new java.util.concurrent.atomic.AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes(); received.incrementAndGet();
                requestedEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
                if (scenario == Case.DISCONNECT) return;
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("x-request-id", scenario == Case.BAD_REQUEST_ID ? KEY : "fixture-request-42");
                exchange.getResponseHeaders().set("Retry-After", "0");
                int status = scenario.name().startsWith("HTTP_") ? Integer.parseInt(scenario.name().substring(5)) : 200;
                String body = response(scenario, status);
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (scenario == Case.GZIP_SUCCESS || scenario == Case.GZIP_OVERSIZED) {
                    var compressed = new java.io.ByteArrayOutputStream();
                    try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(bytes); }
                    bytes = compressed.toByteArray(); exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                }
                exchange.sendResponseHeaders(status, scenario == Case.BODY_TIMEOUT ? 2048 : bytes.length);
                if (scenario == Case.BODY_TIMEOUT) Thread.sleep(40_000);
                else exchange.getResponseBody().write(bytes);
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* fixture peer cancelled or timed out */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            Path ledger = temporary.resolve("attempts.ledger"), grant = temporary.resolve("authorization.json");
            String budgetId = "wiki-diagnostic-" + UUID.randomUUID();
            Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|" + budgetId + "|0|2\n");
            String body = JSON.writeValueAsString(new WikiProviderAuthorization.Grant(1, budgetId, ledger.toString(), endpoint.toString(),
                    "deepseek-flash", Instant.now().plusSeconds(300).toString(), 2, new BigDecimal("0.05"), Map.of("diagnostic", MANIFEST)));
            Files.writeString(grant, body);
            var authorization = new WikiProviderAuthorization(grant.toString(), KnowledgeCatalog.hash(body), ledger.toString(), budgetId, "", endpoint, "deepseek-flash");
            var budget = new ReleaseProviderBudget(ledger.toString(), budgetId, 0, 2);
            var context = new WikiModel.SendContext("diagnostic", MANIFEST, () -> {});
            RuntimeException failure = null;
            try (var model = SpringAiWikiModel.deepseek(endpoint, "deepseek-flash", KEY, authorization)) {
                if (scenario == Case.SUCCESS || scenario == Case.BAD_REQUEST_ID || scenario == Case.GZIP_SUCCESS) {
                    assertEquals(23L, model.generate("system", PRIVATE_TEXT, 2048, context).usageTokens());
                } else if (scenario == Case.MISSING_USAGE) {
                    assertNull(model.generate("system", PRIVATE_TEXT, 2048, context).usageTokens());
                } else {
                    failure = assertThrows(RuntimeException.class, () -> model.generate("system", PRIVATE_TEXT, 2048, context));
                }
            }
            WikiCallDiagnostics.Snapshot details = context.diagnostics().current();
            System.out.println("WIKI_PROTOCOL_DIAGNOSTIC scenario=" + scenario + " details=" + details);
            assertTrue(details.dispatchBoundary());
            assertTrue(details.requestHeadersSent()); assertTrue(details.requestBodySent());
            assertEquals(scenario != Case.DISCONNECT, details.responseHeadersReceived());
            assertEquals(scenario == Case.DISCONNECT ? null : "application/json", details.contentType());
            assertEquals(scenario == Case.DISCONNECT ? null : scenario == Case.GZIP_SUCCESS || scenario == Case.GZIP_OVERSIZED ? "gzip" : "absent", details.contentEncoding());
            assertEquals(scenario == Case.DISCONNECT ? null : scenario == Case.BAD_REQUEST_ID ? null : "fixture-request-42", details.providerRequestId());
            assertEquals(1, received.get(), "SDK and HTTP must not retry");
            if (scenario == Case.GZIP_SUCCESS || scenario == Case.GZIP_OVERSIZED) assertEquals("gzip", requestedEncoding.get());
            assertEquals(1L, budget.snapshot().get("chat"), "failed sends are never refunded");
            assertEquals(0L, budget.snapshot().get("embedding"));
            assertFalse(details.toString().contains(KEY)); assertFalse(details.toString().contains(PRIVATE_TEXT));
            assertNotNull(details.occurredAt());
            if (scenario.name().startsWith("HTTP_")) {
                assertEquals("HTTP_ERROR", details.category());
                assertEquals(Integer.parseInt(scenario.name().substring(5)), details.httpStatus());
                assertTrue(details.exceptionType().startsWith("com.openai.errors."));
                assertFalse(details.reliableUsage());
            } else {
                assertEquals(switch (scenario) {
                    case DISCONNECT -> "CONNECTION_IO";
                    case BODY_TIMEOUT -> "TIMEOUT";
                    case INVALID_JSON, INVALID_CHOICES, TRUNCATED_OUTPUT -> "RESPONSE_FORMAT";
                    case MISSING_USAGE -> "USAGE_UNKNOWN";
                    case GZIP_OVERSIZED -> "MODEL_FAILURE";
                    default -> "SUCCESS";
                }, details.category(), details.toString());
            }
            boolean known = scenario == Case.SUCCESS || scenario == Case.BAD_REQUEST_ID || scenario == Case.GZIP_SUCCESS
                    || scenario == Case.INVALID_CHOICES || scenario == Case.TRUNCATED_OUTPUT;
            assertEquals(known, details.reliableUsage());
            if (scenario == Case.INVALID_CHOICES || scenario == Case.TRUNCATED_OUTPUT) {
                assertInstanceOf(WikiModel.KnownUsageFailure.class, failure);
                assertEquals(23L, ((WikiModel.KnownUsageFailure) failure).reply().usageTokens());
            }
            boolean halted = scenario != Case.SUCCESS && scenario != Case.BAD_REQUEST_ID && scenario != Case.GZIP_SUCCESS;
            assertEquals(halted, Files.exists(ledger.resolveSibling("attempts.ledger.halt")));
            if (halted) {
                byte[] originalLedger = Files.readAllBytes(ledger), originalHalt = Files.readAllBytes(ledger.resolveSibling("attempts.ledger.halt"));
                try (var restarted = SpringAiWikiModel.deepseek(endpoint, "deepseek-flash", KEY, authorization)) {
                    assertThrows(SecurityException.class, () -> restarted.generate("system", PRIVATE_TEXT, 2048,
                            new WikiModel.SendContext("diagnostic", MANIFEST, () -> {})));
                }
                assertEquals(1, received.get());
                assertArrayEquals(originalLedger, Files.readAllBytes(ledger));
                assertArrayEquals(originalHalt, Files.readAllBytes(ledger.resolveSibling("attempts.ledger.halt")));
            }
        } finally { server.stop(0); workers.shutdownNow(); }
    }

    private static String response(Case scenario, int status) throws java.io.IOException {
        if (status != 200) return JSON.writeValueAsString(Map.of("error", Map.of("message", KEY + PRIVATE_TEXT, "type", "fixture")));
        if (scenario == Case.INVALID_JSON) return "{broken:" + PRIVATE_TEXT;
        Object choices = scenario == Case.INVALID_CHOICES ? "wrong-shape" : List.of(Map.of("index", 0,
                "finish_reason", scenario == Case.TRUNCATED_OUTPUT ? "length" : "stop", "message", Map.of("role", "assistant", "content", "{}")));
        var body = new java.util.LinkedHashMap<String, Object>(Map.of("id", "synthetic", "object", "chat.completion", "created", 1,
                "model", "deepseek-flash", "choices", choices));
        if (scenario != Case.MISSING_USAGE) body.put("usage", Map.of("prompt_tokens", 20, "completion_tokens", 3, "total_tokens", 23));
        return JSON.writeValueAsString(body) + (scenario == Case.GZIP_OVERSIZED ? " ".repeat(1_048_577) : "");
    }
}
