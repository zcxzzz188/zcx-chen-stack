package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP and independent synthetic ledgers; never contacts a paid provider. */
class WikiProviderProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "deepseek-flash";
    private static final String MANIFEST = "a".repeat(64);
    private static final String VALID_USAGE = "{\"prompt_tokens\":20,\"completion_tokens\":3,\"total_tokens\":23}";
    @TempDir Path temporary;

    @Test void absentExpiredOrMismatchedAuthorizationCannotSend() throws Exception {
        try (Provider provider = new Provider()) {
            var denied = new WikiProviderAuthorization("", "", "", "", "", provider.endpoint(), MODEL);
            try (var model = model(provider, denied)) {
                assertThrows(SecurityException.class, () -> invoke(model, MANIFEST));
                assertThrows(SecurityException.class, () -> model.generate("system", "data", 2048));
            }
            GrantFiles expired = grant(provider, Instant.now().minusSeconds(1));
            try (var model = model(provider, expired.authorization())) {
                assertThrows(SecurityException.class, () -> invoke(model, MANIFEST));
            }
            GrantFiles valid = grant(provider, Instant.now().plusSeconds(300));
            try (var model = model(provider, valid.authorization())) {
                assertThrows(SecurityException.class, () -> invoke(model, "b".repeat(64)));
                var cancelled = context(model, MANIFEST);
                cancelled.cancel();
                assertThrows(RuntimeException.class, () -> model.generate("system", "data", 2048, cancelled));
            }
            assertEquals(0, provider.calls.get());
            assertEquals(0L, used(valid));
        }
    }

    @Test void actualWireLimitsThinkingAndToolsAreFixedAndUsageIsParsed() throws Exception {
        try (Provider provider = new Provider()) {
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            try (var model = model(provider, files.authorization())) {
                WikiModel.Reply reply = invoke(model, MANIFEST);
                assertEquals("{\"facts\":[]}", reply.json());
                assertEquals(20L, reply.inputTokens());
                assertEquals(3L, reply.outputTokens());
                assertEquals(23L, reply.usageTokens());
            }
            JsonNode wire = provider.requests.getFirst();
            assertEquals(MODEL, wire.path("model").asText());
            assertEquals(2048, wire.path("max_tokens").asInt());
            assertEquals("disabled", wire.path("thinking").path("type").asText());
            assertEquals("none", wire.path("tool_choice").asText());
            assertEquals(0, wire.path("tools").size());
            assertFalse(wire.path("stream").asBoolean());
            assertEquals(List.of("system", "user"), List.of(wire.path("messages").get(0).path("role").asText(),
                    wire.path("messages").get(1).path("role").asText()));
            assertTrue(provider.wireBytes.getFirst() <= WikiProviderAuthorization.MAX_WIRE_BYTES);
            assertEquals(1L, used(files));
            assertEquals(0, WikiProviderAuthorization.reservedUsd(16).compareTo(new BigDecimal("0.196608")));
            assertTrue(WikiProviderAuthorization.reservedUsd(16).compareTo(new BigDecimal("0.20")) <= 0);
        }
    }

    @Test void oversizedWireAndOutputAreRejectedBeforeBudgetReservation() throws Exception {
        try (Provider provider = new Provider()) {
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            try (var model = model(provider, files.authorization())) {
                assertThrows(RuntimeException.class, () -> model.generate("system", "data", 2049, context(model, MANIFEST)));
                assertThrows(RuntimeException.class, () -> model.generate("system", "x".repeat(16_384), 2048, context(model, MANIFEST)));
            }
            assertEquals(0, provider.calls.get());
            assertEquals(0L, used(files));
        }
    }

    @Test void missingInconsistentOrExcessUsageRemainsUnknownAndHaltsAcrossInstances() throws Exception {
        for (String usage : new String[] {null,
                "{\"prompt_tokens\":20,\"completion_tokens\":3,\"total_tokens\":999}",
                "{\"prompt_tokens\":20,\"completion_tokens\":2049,\"total_tokens\":2069}"}) {
            try (Provider provider = new Provider()) {
                provider.usage = usage;
                GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
                try (var model = model(provider, files.authorization())) {
                    assertNull(invoke(model, MANIFEST).usageTokens());
                }
                assertTrue(Files.isRegularFile(files.ledger().resolveSibling(files.ledger().getFileName() + ".halt")));
                try (var model = model(provider, files.authorization())) {
                    assertThrows(SecurityException.class, () -> invoke(model, MANIFEST));
                }
                assertEquals(1, provider.calls.get());
                assertEquals(1L, used(files));
            }
        }
    }

    @Test void missingOrUnexpectedResponseModelIsRejectedAndPersistentlyHalts() throws Exception {
        for (String responseModel : new String[] {null, "unexpected-model"}) {
            try (Provider provider = new Provider()) {
                provider.responseModel = responseModel;
                GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
                try (var model = model(provider, files.authorization())) {
                    assertThrows(RuntimeException.class, () -> invoke(model, MANIFEST));
                }
                assertTrue(Files.isRegularFile(files.ledger().resolveSibling(files.ledger().getFileName() + ".halt")));
                try (var model = model(provider, files.authorization())) {
                    assertThrows(SecurityException.class, () -> invoke(model, MANIFEST));
                }
                assertEquals(1, provider.calls.get());
                assertEquals(1L, used(files));
            }
        }
    }

    @Test void reasoningTokensDespiteDisabledThinkingAreRejectedAndPersistentlyHalt() throws Exception {
        try (Provider provider = new Provider()) {
            provider.usage = "{\"prompt_tokens\":20,\"completion_tokens\":3,\"total_tokens\":23,"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":1}}";
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            try (var model = model(provider, files.authorization())) {
                assertThrows(RuntimeException.class, () -> invoke(model, MANIFEST));
            }
            assertTrue(Files.isRegularFile(files.ledger().resolveSibling(files.ledger().getFileName() + ".halt")));
            try (var model = model(provider, files.authorization())) {
                assertThrows(SecurityException.class, () -> invoke(model, MANIFEST));
            }
            assertEquals(1, provider.calls.get());
            assertEquals(1L, used(files));
        }
    }

    @Test void truncatedOutputRetainsReliableUsageButNeverRetries() throws Exception {
        try (Provider provider = new Provider()) {
            provider.finishReason = "length";
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            try (var model = model(provider, files.authorization())) {
                var failure = assertThrows(WikiModel.KnownUsageFailure.class, () -> invoke(model, MANIFEST));
                assertEquals("WIKI_MODEL_TEXT_REQUIRED", failure.getMessage());
                assertEquals(23L, failure.reply().usageTokens());
                assertThrows(SecurityException.class, () -> invoke(model, MANIFEST));
            }
            assertEquals(1, provider.calls.get());
            assertEquals(1L, used(files));
        }
    }

    @Test void sixteenAttemptsAccumulateAcrossInstancesAndSeventeenthCannotSend() throws Exception {
        try (Provider provider = new Provider()) {
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            for (int i = 0; i < 16; i++) {
                try (var model = model(provider, files.authorization())) { assertEquals(23L, invoke(model, MANIFEST).usageTokens()); }
            }
            try (var model = model(provider, files.authorization())) {
                assertThrows(RuntimeException.class, () -> invoke(model, MANIFEST));
            }
            assertEquals(16, provider.calls.get());
            assertEquals(16L, used(files));
            assertEquals(17, Files.readAllLines(files.ledger()).size());
        }
    }

    @Test void g2LedgerAndItsHardLinkCannotBecomeWikiBudget() throws Exception {
        try (Provider provider = new Provider()) {
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            byte[] original = Files.readAllBytes(files.ledger());
            Path alias = files.ledger().resolveSibling("g2-hardlink.ledger");
            Files.createLink(alias, files.ledger());
            for (Path g2Path : List.of(files.ledger(), alias)) {
                var authorization = files.authorization(provider.endpoint(), g2Path);
                try (var model = model(provider, authorization)) {
                    assertThrows(SecurityException.class, () -> invoke(model, MANIFEST));
                }
            }
            assertEquals(0, provider.calls.get());
            assertArrayEquals(original, Files.readAllBytes(files.ledger()));
        }
    }

    @Test void retryAfterZeroCannotCauseASecondPhysicalPost() throws Exception {
        try (Provider provider = new Provider()) {
            provider.status = 503;
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            try (var model = model(provider, files.authorization())) {
                assertThrows(RuntimeException.class, () -> invoke(model, MANIFEST));
            }
            assertEquals(1, provider.calls.get());
            assertEquals(1L, used(files));
        }
    }

    @Test void redirectDoesNotForwardTheRequestOrCredential() throws Exception {
        try (Provider source = new Provider(); Provider target = new Provider()) {
            source.status = 307;
            source.redirect = target.endpoint();
            GrantFiles files = grant(source, Instant.now().plusSeconds(300));
            try (var model = model(source, files.authorization())) {
                assertThrows(RuntimeException.class, () -> invoke(model, MANIFEST));
            }
            assertEquals(1, source.calls.get());
            assertEquals(0, target.calls.get());
            assertEquals(1L, used(files));
        }
    }

    @Test void callerTimeoutCancelsOnlyItsCallAndSealsUnknownBudget() throws Exception {
        try (Provider provider = new Provider(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            try (var model = model(provider, files.authorization())) {
                var cancelled = context(model, MANIFEST);
                Future<WikiModel.Reply> first = workers.submit(() -> model.generate("system", "hold-cancel", 2048, cancelled));
                assertTrue(provider.cancelRequest.await(5, TimeUnit.SECONDS));
                Future<WikiModel.Reply> second = workers.submit(() -> model.generate("system", "hold-success", 2048, context(model, MANIFEST)));
                assertTrue(provider.successRequest.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> first.get(100, TimeUnit.MILLISECONDS));
                cancelled.cancel();
                assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
                assertTrue(provider.disconnected.await(5, TimeUnit.SECONDS), "Call.cancel must close the actual client connection");
                provider.releaseSuccess.countDown();
                assertEquals(23L, second.get(5, TimeUnit.SECONDS).usageTokens());
            } finally { provider.releaseSuccess.countDown(); }
            assertEquals(2, provider.calls.get());
            assertEquals(2L, used(files));
            assertTrue(Files.isRegularFile(files.ledger().resolveSibling(files.ledger().getFileName() + ".halt")));
        }
    }

    @Test void changedAuthorizationHashIsRecheckedAtTheLastSendAdmission() throws Exception {
        try (Provider provider = new Provider()) {
            GrantFiles files = grant(provider, Instant.now().plusSeconds(300));
            try (var model = model(provider, files.authorization())) {
                var revoked = new WikiModel.SendContext("case", MANIFEST, () -> {
                    try { Files.writeString(files.grant(), "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND); }
                    catch (IOException failure) { throw new IllegalStateException(failure); }
                    model.authorize("case", MANIFEST);
                });
                assertThrows(RuntimeException.class, () -> model.generate("system", "data", 2048, revoked));
            }
            assertEquals(0, provider.calls.get());
            assertEquals(1L, used(files), "An already reserved attempt is never refunded");
        }
    }

    private static SpringAiWikiModel model(Provider provider, WikiProviderAuthorization authorization) {
        return SpringAiWikiModel.deepseek(provider.endpoint(), MODEL, "synthetic", authorization);
    }

    private static WikiModel.SendContext context(SpringAiWikiModel model, String manifest) {
        return new WikiModel.SendContext("case", manifest, () -> model.authorize("case", manifest));
    }

    private static WikiModel.Reply invoke(SpringAiWikiModel model, String manifest) {
        return model.generate("system", "data", 2048, context(model, manifest));
    }

    private GrantFiles grant(Provider provider, Instant expiry) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Path ledger = directory.resolve("wiki.ledger");
        Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|wiki-test|0|16\n", StandardCharsets.US_ASCII);
        Path grant = directory.resolve("authorization.json");
        var approved = new WikiProviderAuthorization.Grant(1, "wiki-test", ledger.toString(), provider.endpoint().toString(), MODEL,
                expiry.toString(), 16, new BigDecimal("0.20"), Map.of("case", MANIFEST));
        String json = JSON.writeValueAsString(approved);
        Files.writeString(grant, json, StandardCharsets.UTF_8);
        return new GrantFiles(grant, ledger, KnowledgeCatalog.hash(json), provider.endpoint());
    }

    private record GrantFiles(Path grant, Path ledger, String hash, URI endpoint) {
        WikiProviderAuthorization authorization() { return authorization(endpoint, null); }
        WikiProviderAuthorization authorization(URI endpoint, Path g2Ledger) {
            return new WikiProviderAuthorization(grant.toString(), hash, ledger.toString(), "wiki-test",
                    g2Ledger == null ? "" : g2Ledger.toString(), endpoint, MODEL);
        }
    }

    private static long used(GrantFiles files) {
        return new ReleaseProviderBudget(files.ledger().toString(), "wiki-test", 0, 16).snapshot().get("chat");
    }

    private static final class Provider implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger calls = new AtomicInteger();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<Integer> wireBytes = new CopyOnWriteArrayList<>();
        final CountDownLatch cancelRequest = new CountDownLatch(1);
        final CountDownLatch successRequest = new CountDownLatch(1);
        final CountDownLatch releaseSuccess = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile String usage = VALID_USAGE;
        volatile String finishReason = "stop";
        volatile String responseModel = MODEL;
        volatile int status = 200;
        volatile URI redirect;

        Provider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    calls.incrementAndGet();
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    wireBytes.add(bytes.length);
                    JsonNode request = JSON.readTree(bytes); requests.add(request);
                    String data = request.path("messages").path(1).path("content").asText();
                    if (data.equals("hold-cancel")) {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, 0);
                        cancelRequest.countDown();
                        byte[] padding = " ".repeat(1024).getBytes(StandardCharsets.US_ASCII);
                        try {
                            for (int i = 0; i < 2000; i++) {
                                exchange.getResponseBody().write(padding); exchange.getResponseBody().flush();
                                Thread.sleep(10);
                            }
                        } catch (IOException closed) { disconnected.countDown(); }
                        return;
                    }
                    if (data.equals("hold-success")) {
                        successRequest.countDown();
                        if (!releaseSuccess.await(10, TimeUnit.SECONDS)) return;
                    }
                    if (redirect != null) exchange.getResponseHeaders().set("Location", redirect.toString());
                    if (status == 503) exchange.getResponseHeaders().set("Retry-After", "0");
                    String body = status == 200
                            ? "{\"id\":\"protocol\",\"object\":\"chat.completion\",\"created\":1"
                            + (responseModel == null ? "" : ",\"model\":" + JSON.writeValueAsString(responseModel))
                            + ",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"facts\\\":[]}\"},\"finish_reason\":\"" + finishReason + "\"}]"
                            + (usage == null ? "" : ",\"usage\":" + usage) + "}"
                            : "{\"error\":{\"message\":\"synthetic unavailable\",\"type\":\"fixture\",\"code\":\"fixture\"}}";
                    byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, encoded.length);
                    exchange.getResponseBody().write(encoded);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }

        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"); }

        @Override public void close() {
            releaseSuccess.countDown(); server.stop(0); executor.shutdownNow();
        }
    }
}
