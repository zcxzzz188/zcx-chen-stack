package com.zcx.chenstack.rag;

import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.config.AiConfig;
import com.zcx.chenstack.config.RagConfig;
import com.zcx.chenstack.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseProviderBudgetTest {
    @TempDir Path directory;

    private ReleaseProviderBudget budget(int embedding, int chat) throws Exception {
        Path path = directory.resolve("attempts.ledger");
        Files.writeString(path, "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic|" + embedding + "|" + chat + "\n");
        return new ReleaseProviderBudget(path.toString(), "synthetic", embedding, chat);
    }

    @Test void persistsBothKindsAcrossNewInstancesAndNeverResetsUnknownSends() throws Exception {
        var budget = budget(2, 1);
        budget.reserve(ReleaseProviderBudget.Kind.EMBEDDING);
        budget.reserve(ReleaseProviderBudget.Kind.CHAT);
        var reopened = new ReleaseProviderBudget(directory.resolve("attempts.ledger").toString(), "synthetic", 2, 1);
        assertEquals(Map.of("embedding", 1L, "chat", 1L), reopened.snapshot());
        assertThrows(SecurityException.class, () -> reopened.reserve(ReleaseProviderBudget.Kind.CHAT));
        reopened.reserve(ReleaseProviderBudget.Kind.EMBEDDING);
        assertThrows(SecurityException.class, () -> budget.reserve(ReleaseProviderBudget.Kind.EMBEDDING));
        assertEquals(Map.of("embedding", 2L, "chat", 1L), budget.snapshot());
    }

    @Test void refusesMissingCorruptTruncatedWrongIdentityAndChangedLimits() throws Exception {
        Path file = directory.resolve("attempts.ledger");
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 2, 1));
        var budget = budget(2, 1);
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "other", 2, 1));
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 64, 20));
        for (String record : new String[]{"1|CHAT|0|1", "2|CHAT|0|1\n", "1|CHAT|0|0\n", "1|OTHER|0|1\n", "1|CHAT|0|9\n"}) {
            Files.writeString(file, "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic|2|1\n" + record);
            assertThrows(SecurityException.class, budget::snapshot);
        }
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget("relative", "synthetic", 64, 20));
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 65, 20));
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 64, 101));
    }

    private String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test void exhaustedLegacyBudgetRejectsPreflightWithoutChangingLedger() throws Exception {
        var budget = budget(64, 20);
        for (int i = 0; i < 20; i++) budget.reserve(ReleaseProviderBudget.Kind.CHAT);
        Path file = directory.resolve("attempts.ledger");
        byte[] original = Files.readAllBytes(file);
        assertEquals("RELEASE_PROVIDER_HTTP_BUDGET_EXHAUSTED",
                assertThrows(SecurityException.class, () -> budget.checkChatCapacity(1)).getMessage());
        assertThrows(SecurityException.class, () -> budget.reserve(ReleaseProviderBudget.Kind.CHAT));
        assertThrows(SecurityException.class, () -> budget.checkChatCapacity(0));
        assertThrows(SecurityException.class, () -> budget.checkChatCapacity(101));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void explicitExtensionPreservesHistoryCountsAndEmbeddingLimitAcrossRestart() throws Exception {
        var legacy = budget(64, 20);
        for (int i = 0; i < 6; i++) legacy.reserve(ReleaseProviderBudget.Kind.EMBEDDING);
        for (int i = 0; i < 20; i++) legacy.reserve(ReleaseProviderBudget.Kind.CHAT);
        Path file = directory.resolve("attempts.ledger");
        byte[] original = Files.readAllBytes(file);
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100));

        assertEquals(Map.of("embedding", 6L, "chat", 20L), ReleaseProviderBudget.extendChatLimit(
                file.toString(), "synthetic", 64, 20, 100, "synthetic-operator", sha256(file)));
        byte[] expanded = Files.readAllBytes(file);
        assertArrayEquals(original, Arrays.copyOf(expanded, original.length));
        String[] audit = new String(expanded, original.length, expanded.length - original.length,
                StandardCharsets.US_ASCII).stripTrailing().split("\\|", -1);
        assertArrayEquals(new String[]{"27", "CHAT_LIMIT", "6", "20", "20", "100"}, Arrays.copyOf(audit, 6));
        assertDoesNotThrow(() -> Instant.parse(audit[6]));
        assertEquals("synthetic-operator", audit[7]);

        var reopened = new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100);
        reopened.checkChatCapacity(80);
        assertThrows(SecurityException.class, () -> reopened.checkChatCapacity(81));
        assertArrayEquals(expanded, Files.readAllBytes(file));
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 64, 20));
        reopened.reserve(ReleaseProviderBudget.Kind.CHAT);
        for (int i = 6; i < 64; i++) reopened.reserve(ReleaseProviderBudget.Kind.EMBEDDING);
        assertThrows(SecurityException.class, () -> reopened.reserve(ReleaseProviderBudget.Kind.EMBEDDING));
        assertEquals(Map.of("embedding", 64L, "chat", 21L),
                new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100).snapshot());
    }

    @Test void extensionRejectsChangedHashInvalidAuthorizationAndRepeatWithoutWriting() throws Exception {
        budget(64, 20);
        Path file = directory.resolve("attempts.ledger");
        String expectedHash = sha256(file);
        Files.writeString(file, "1|CHAT|0|1\n", java.nio.file.StandardOpenOption.APPEND);
        byte[] original = Files.readAllBytes(file);
        assertEquals("RELEASE_BUDGET_LEDGER_HASH_MISMATCH", assertThrows(SecurityException.class,
                () -> ReleaseProviderBudget.extendChatLimit(file.toString(), "synthetic", 64, 20, 100,
                        "synthetic-operator", expectedHash)).getMessage());
        String currentHash = sha256(file);
        assertThrows(SecurityException.class, () -> ReleaseProviderBudget.extendChatLimit(
                file.toString(), "synthetic", 64, 19, 100, "synthetic-operator", currentHash));
        assertThrows(SecurityException.class, () -> ReleaseProviderBudget.extendChatLimit(
                file.toString(), "synthetic", 64, 20, 99, "synthetic-operator", currentHash));
        assertThrows(SecurityException.class, () -> ReleaseProviderBudget.extendChatLimit(
                file.toString(), "synthetic", 64, 20, 100, "operator|injection", currentHash));
        assertThrows(SecurityException.class, () -> ReleaseProviderBudget.extendChatLimit(
                file.toString(), "synthetic", 63, 20, 100, "synthetic-operator", currentHash));
        assertArrayEquals(original, Files.readAllBytes(file));

        ReleaseProviderBudget.main(new String[]{"extend-chat", file.toString(), "synthetic", "64", "20", "100",
                "synthetic-operator", currentHash});
        byte[] expanded = Files.readAllBytes(file);
        String expandedHash = sha256(file);
        assertThrows(SecurityException.class, () -> ReleaseProviderBudget.extendChatLimit(
                file.toString(), "synthetic", 64, 20, 100, "synthetic-operator", expandedHash));
        assertArrayEquals(expanded, Files.readAllBytes(file));
    }

    @Test void replayRejectsTamperedAuditAndOverBudgetRecordsBeforeExtension() throws Exception {
        Path file = directory.resolve("attempts.ledger");
        String header = "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic|64|20\n";
        String audit = "1|CHAT_LIMIT|0|0|20|100|2026-10-25T00:00:00Z|synthetic-operator\n";
        for (String invalid : new String[]{
                audit.replace("|20|100|", "|19|100|"), audit.replace("|20|100|", "|20|99|"),
                audit.replace("CHAT_LIMIT|0|0", "CHAT_LIMIT|0|1"),
                audit.replace("2026-10-25T00:00:00Z", "invalid"),
                audit.replace("synthetic-operator", ""), audit + audit.replace("1|CHAT_LIMIT", "2|CHAT_LIMIT")}) {
            Files.writeString(file, header + invalid);
            assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100));
        }
        StringBuilder records = new StringBuilder(header);
        for (int i = 1; i <= 21; i++) records.append(i).append("|CHAT|0|").append(i).append('\n');
        records.append("22|CHAT_LIMIT|0|21|20|100|2026-10-25T00:00:00Z|synthetic-operator\n");
        Files.writeString(file, records);
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100));
        Files.writeString(file, header.replace("|64|20", "|64|100"));
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100));
    }

    @Test void singleInstanceExpandedBudgetAllowsExactlyOneHundredCumulativeRequests() throws Exception {
        var legacy = budget(64, 20);
        for (int i = 0; i < 20; i++) legacy.reserve(ReleaseProviderBudget.Kind.CHAT);
        Path file = directory.resolve("attempts.ledger");
        ReleaseProviderBudget.extendChatLimit(file.toString(), "synthetic", 64, 20, 100, "synthetic-operator", sha256(file));
        var expanded = new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100);
        for (int i = 20; i < 100; i++) expanded.reserve(ReleaseProviderBudget.Kind.CHAT);
        assertEquals(Map.of("embedding", 0L, "chat", 100L), expanded.snapshot());
        assertThrows(SecurityException.class, () -> expanded.checkChatCapacity(1));
        assertThrows(SecurityException.class, () -> expanded.reserve(ReleaseProviderBudget.Kind.CHAT));
        assertEquals(100L, new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100).snapshot().get("chat"));
    }

    @Test void contendingInstancesFailClosedWithoutWritingOrExtending() throws Exception {
        var first = budget(64, 20);
        Path file = directory.resolve("attempts.ledger");
        var second = new ReleaseProviderBudget(file.toString(), "synthetic", 64, 20);
        byte[] original = Files.readAllBytes(file);
        String expectedHash = sha256(file);
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertEquals("RELEASE_BUDGET_PERSISTENCE_FAILED_CLOSED", assertThrows(SecurityException.class,
                    () -> first.reserve(ReleaseProviderBudget.Kind.CHAT)).getMessage());
            assertThrows(SecurityException.class, () -> second.reserve(ReleaseProviderBudget.Kind.CHAT));
            assertThrows(SecurityException.class, () -> second.checkChatCapacity(1));
            assertThrows(SecurityException.class, () -> ReleaseProviderBudget.extendChatLimit(
                    file.toString(), "synthetic", 64, 20, 100, "synthetic-operator", expectedHash));
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals(Map.of("embedding", 0L, "chat", 0L), second.snapshot());
    }

    @Test void concurrentInstancesCannotReserveBeyondExpandedCumulativeLimit() throws Exception {
        var legacy = budget(64, 20);
        for (int i = 0; i < 20; i++) legacy.reserve(ReleaseProviderBudget.Kind.CHAT);
        Path file = directory.resolve("attempts.ledger");
        ReleaseProviderBudget.extendChatLimit(file.toString(), "synthetic", 64, 20, 100, "synthetic-operator", sha256(file));
        ReleaseProviderBudget[] budgets = {
                new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100),
                new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100)};
        AtomicInteger reserved = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                ReleaseProviderBudget budget = budgets[worker % budgets.length];
                tasks.add(executor.submit(() -> {
                    try { start.await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                    for (int i = 0; i < 100; i++) {
                        try { budget.reserve(ReleaseProviderBudget.Kind.CHAT); reserved.incrementAndGet(); }
                        catch (SecurityException error) {
                            assertTrue(error.getMessage().equals("RELEASE_PROVIDER_HTTP_BUDGET_EXHAUSTED")
                                    || error.getMessage().equals("RELEASE_BUDGET_PERSISTENCE_FAILED_CLOSED"));
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get();
        }
        assertTrue(reserved.get() <= 80);
        assertEquals(20L + reserved.get(), budgets[0].snapshot().get("chat"));
        while (reserved.get() < 80) { budgets[0].reserve(ReleaseProviderBudget.Kind.CHAT); reserved.incrementAndGet(); }
        assertEquals(100L, new ReleaseProviderBudget(file.toString(), "synthetic", 64, 100).snapshot().get("chat"));
        assertThrows(SecurityException.class, () -> budgets[1].reserve(ReleaseProviderBudget.Kind.CHAT));
        assertEquals(0L, budgets[0].snapshot().get("embedding"));
    }

    @Test void zeroBudgetBlocksTransportAndDisabledDefaultHasNoFileDependency() throws Exception {
        var disabled = new ReleaseProviderBudget("", "", 64, 20);
        assertFalse(disabled.configured());
        disabled.reserve(ReleaseProviderBudget.Kind.EMBEDDING);
        var zero = budget(0, 0);
        assertTrue(zero.configured());
        assertThrows(SecurityException.class, () -> zero.reserve(ReleaseProviderBudget.Kind.EMBEDDING));
        assertThrows(SecurityException.class, () -> zero.reserve(ReleaseProviderBudget.Kind.CHAT));
    }

    @Test void realSyncAndSseChatHttpReserveBeforeSendAndFailuresAreNotRetriedOrRefunded() throws Exception {
        var budget = budget(1, 3);
        AtomicInteger received = new AtomicInteger();
        AtomicInteger reserved = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int attempt = received.incrementAndGet();
            if (budget.snapshot().get("chat") == attempt) reserved.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            boolean stream = request.contains("\"stream\":true");
            if (attempt == 3) { response = "{\"error\":{\"message\":\"synthetic\"}}"; exchange.sendResponseHeaders(503, response.length()); }
            else {
                response = stream ? "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"synthetic\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        : "{\"id\":\"s\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"synthetic\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
                exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            }
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8)); exchange.close();
        });
        server.start();
        try {
            AiConfig config = new AiConfig();
            ReflectionTestUtils.setField(config, "apiKey", "synthetic-not-real");
            ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(config, "completionsPath", "/chat/completions");
            ReflectionTestUtils.setField(config, "model", "synthetic");
            ReflectionTestUtils.setField(config, "temperature", 0.0);
            ReflectionTestUtils.setField(config, "maxTokens", 16);
            var model = config.openAiChatModel(budget);
            assertEquals("ok", model.call("synthetic"));
            assertFalse(model.stream(new org.springframework.ai.chat.prompt.Prompt("synthetic")).collectList().block(Duration.ofSeconds(10)).isEmpty());
            assertThrows(RuntimeException.class, () -> model.call("synthetic failure"));
            assertEquals(3, received.get()); assertEquals(3, reserved.get());
            assertThrows(RuntimeException.class, () -> model.call("over budget"));
            assertEquals(3, received.get()); assertEquals(3L, budget.snapshot().get("chat"));
        } finally { server.stop(0); }
    }

    @Test void actualEmbeddingHttpUsesSameLedgerAndRefusesSecondAttempt() throws Exception {
        var budget = budget(1, 1);
        AtomicInteger received = new AtomicInteger(); AtomicInteger reserved = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            received.incrementAndGet();
            if (budget.snapshot().get("embedding") == 1L) reserved.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String vector = "1," + "0,".repeat(1022) + "0";
            byte[] response = ("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[" + vector + "]}],\"model\":\"synthetic\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            RagProperties properties = new RagProperties(); properties.setEmbeddingApiKey("synthetic-not-real");
            properties.setEmbeddingBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var model = RagConfig.embedding(properties, budget);
            assertEquals(1024, model.embed("synthetic").length);
            assertThrows(RuntimeException.class, () -> model.embed("over budget"));
            assertEquals(1, received.get()); assertEquals(1, reserved.get());
        } finally { server.stop(0); }
    }

    @Test void streamFailureAndUnknownPostDisconnectDoNotRetryOrRefund() throws Exception {
        var budget = budget(0, 3);
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int call = received.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            if (call == 1) {
                byte[] response = "{\"error\":{\"message\":\"synthetic\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(503, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            AiConfig config = new AiConfig();
            ReflectionTestUtils.setField(config, "apiKey", "synthetic-not-real");
            ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(config, "completionsPath", "/chat/completions");
            ReflectionTestUtils.setField(config, "model", "synthetic"); ReflectionTestUtils.setField(config, "temperature", 0.0);
            ReflectionTestUtils.setField(config, "maxTokens", 16);
            var model = config.openAiChatModel(budget);
            assertThrows(RuntimeException.class, () -> model.stream(new org.springframework.ai.chat.prompt.Prompt("stream failure"))
                    .collectList().block(Duration.ofSeconds(10)));
            assertEquals(1, received.get());
            assertThrows(RuntimeException.class, () -> model.call("unknown disconnect"));
            assertEquals(2, received.get()); assertEquals(2L, budget.snapshot().get("chat"));
        } finally { server.stop(0); }
    }
    @Test void actualSdkCancellationClosesStreamingConnectionWithoutAnotherAttempt() throws Exception {
        var budget = budget(0, 1);
        AtomicInteger received = new AtomicInteger();
        CountDownLatch disconnected = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            received.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(("data: {\"id\":\"cancel\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"first\"},\"finish_reason\":null}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                for (int i = 0; i < 100; i++) {
                    Thread.sleep(30); exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
            } catch (java.io.IOException closed) { disconnected.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var model = sdkFixture(server, budget);
            assertNotNull(model.stream(new org.springframework.ai.chat.prompt.Prompt("cancel fixture"))
                    .take(1).blockLast(Duration.ofSeconds(8)));
            assertTrue(disconnected.await(5, java.util.concurrent.TimeUnit.SECONDS), "SDK cancellation must close the transport");
            assertEquals(1, received.get()); assertEquals(1L, budget.snapshot().get("chat"));
        } finally { server.stop(0); }
    }

    @Test void actualSdkDoesNotFollowRedirectsOrSpendAnUnrecordedAttempt() throws Exception {
        var budget = budget(0, 2);
        AtomicInteger received = new AtomicInteger(); AtomicInteger redirected = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            received.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/redirected"); exchange.sendResponseHeaders(307, -1); exchange.close();
        });
        server.createContext("/redirected", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            assertThrows(RuntimeException.class, () -> sdkFixture(server, budget).call("redirect fixture"));
            assertEquals(1, received.get()); assertEquals(0, redirected.get()); assertEquals(1L, budget.snapshot().get("chat"));
        } finally { server.stop(0); }
    }

    @Test void retryAfterZeroCannotSendTwiceButANewCallHasItsOwnPermit() throws Exception {
        var budget = budget(0, 2);
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int attempt = received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] response;
            if (attempt == 1) {
                response = "{\"error\":{\"message\":\"fixture unavailable\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(503, response.length);
            } else {
                response = ("{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
            }
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = sdkFixture(server, budget);
            assertThrows(RuntimeException.class, () -> model.call("first call"));
            assertEquals(1, received.get());
            assertEquals(1L, budget.snapshot().get("chat"));

            assertEquals("ok", model.call("independent second call"));
            assertEquals(2, received.get());
            assertEquals(2L, budget.snapshot().get("chat"));
        } finally { server.stop(0); }
    }

    private org.springframework.ai.openai.OpenAiChatModel sdkFixture(HttpServer server, ReleaseProviderBudget budget) {
        AiConfig config = new AiConfig();
        ReflectionTestUtils.setField(config, "apiKey", "fixture-only");
        ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(config, "completionsPath", "/chat/completions");
        ReflectionTestUtils.setField(config, "model", "fixture"); ReflectionTestUtils.setField(config, "temperature", 0.0);
        ReflectionTestUtils.setField(config, "maxTokens", 16);
        return config.openAiChatModel(budget);
    }

    @Test void badRequestDiagnosticsPreserveWireBodyAndNeverEchoPrivateText() throws Exception {
        var budget = budget(0, 1);
        var received = new AtomicInteger();
        String requestBody = "{\"model\":\"deepseek-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"PRIVATE_REQUEST_CANARY\"}]}";
        String errorBody = "{\"error\":{\"type\":\"invalid_request_error\",\"param\":\"model\",\"message\":\"invalid model PRIVATE_ERROR_CANARY\"}}";
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            if (requestBody.equals(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))) received.incrementAndGet();
            byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("x-request-id", "PRIVATE_TRACE_CANARY");
            exchange.sendResponseHeaders(400, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReleaseProviderBudget.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        var client = budget.httpTransport(ReleaseProviderBudget.Kind.CHAT, Duration.ofSeconds(5), endpoint);
        try {
            var request = new okhttp3.Request.Builder().url(endpoint).header("Authorization", "Bearer PRIVATE_HEADER_CANARY")
                    .post(okhttp3.RequestBody.create(requestBody, okhttp3.MediaType.get("application/json"))).build();
            try (var response = client.newCall(request).execute()) { assertEquals(400, response.code()); assertEquals(errorBody, response.body().string()); }
            assertEquals(1, received.get()); assertEquals(1L, budget.snapshot().get("chat"));
            var lines = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("PROVIDER_REQUEST_SCHEMA ") && line.contains("\"model\":\"deepseek-flash\"")));
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("PROVIDER_BAD_REQUEST ") && line.contains("status=400") && line.contains("\"param\":\"model\"")));
            assertTrue(lines.stream().noneMatch(line -> line.contains("CANARY")));
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally { logger.detachAppender(appender); appender.stop(); client.connectionPool().evictAll(); server.stop(0); }
    }

    @Test void wireAuditCorrelatesCallsWithoutHeadersBodiesQueryOrFailureMessages() throws Exception {
        var budget = budget(0, 3);
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            received.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = "RESPONSE_BODY_CANARY".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReleaseProviderBudget.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        var endpoint = "http://USERINFO_CANARY:USERINFO_PASSWORD_CANARY@127.0.0.1:" + server.getAddress().getPort()
                + "/chat/completions?key=QUERY_CANARY";
        var client = budget.httpTransport(ReleaseProviderBudget.Kind.CHAT, Duration.ofSeconds(5), endpoint);
        var request = new okhttp3.Request.Builder().url(endpoint).header("Authorization", "Bearer HEADER_CANARY")
                .post(okhttp3.RequestBody.create("REQUEST_BODY_CANARY", okhttp3.MediaType.get("application/json"))).build();
        try {
            for (int i = 0; i < 2; i++) {
                try (var response = client.newCall(request).execute()) {
                    assertEquals(200, response.code()); assertEquals("RESPONSE_BODY_CANARY", response.body().string());
                }
            }
            var failing = client.newBuilder().addInterceptor(chain -> { throw new java.io.IOException("FAILURE_MESSAGE_CANARY"); }).build();
            assertThrows(java.io.IOException.class, () -> failing.newCall(request).execute());
            assertEquals(2, received.get()); assertEquals(3L, budget.snapshot().get("chat"));
            var audit = appender.list.stream().filter(event -> event.getFormattedMessage().startsWith("RELEASE_HTTP_AUDIT ")).toList();
            var sent = audit.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("event=request_headers_end ")).toList();
            var responded = audit.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("event=response_headers_end ")).toList();
            assertEquals(received.get(), sent.size()); assertEquals(received.get(), responded.size());
            var ids = sent.stream().map(line -> line.split("audit_id=")[1].split(" ")[0]).collect(java.util.stream.Collectors.toSet());
            assertEquals(2, ids.size());
            assertTrue(sent.stream().allMatch(line -> line.contains("budget_id=synthetic kind=CHAT method=POST host=127.0.0.1 port=")));
            assertTrue(sent.stream().allMatch(line -> line.endsWith("path=/chat/completions")));
            assertTrue(responded.stream().allMatch(line -> line.endsWith("status=200")));
            assertEquals(ids, responded.stream().map(line -> line.split("audit_id=")[1].split(" ")[0]).collect(java.util.stream.Collectors.toSet()));
            assertEquals(1, audit.stream().filter(event -> event.getFormattedMessage().contains("event=call_failed ")).count());
            assertTrue(audit.stream().anyMatch(event -> event.getFormattedMessage().endsWith("exception_class=java.io.IOException")));
            for (var event : audit) { assertFalse(event.getFormattedMessage().contains("CANARY")); assertNull(event.getThrowableProxy()); }
        } finally { logger.detachAppender(appender); appender.stop(); client.connectionPool().evictAll(); server.stop(0); }
    }
}
