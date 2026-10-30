package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.config.RagPublicationConfiguration;
import com.zcx.chenstack.config.RagPublicationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NormalAiUsageTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private NormalAiUsage usage() { return new NormalAiUsage(true, directory.resolve("normal.jsonl").toString()); }
    private List<JsonNode> events() throws Exception {
        return Files.readAllLines(directory.resolve("normal.jsonl")).stream().map(line -> {
            try { return JSON.readTree(line); } catch (IOException failure) { throw new IllegalStateException(failure); }
        }).filter(node -> node.has("requestId")).toList();
    }
    private okhttp3.Request request(String endpoint) {
        return new okhttp3.Request.Builder().url(endpoint).post(okhttp3.RequestBody.create(
                "{\"model\":\"synthetic\",\"messages\":[{\"role\":\"user\",\"content\":\"private-prompt-not-logged\"}]}",
                okhttp3.MediaType.get("application/json"))).build();
    }

    @Test void streamsRealHttpUsageAndSeparatesChatCompilationAndRetrievalWithoutAnAcceptanceLimit() throws Exception {
        var usage = usage();
        var count = new AtomicInteger();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            count.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"private-answer-not-logged\"}}]}\n\n"
                    + "data: {\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/embeddings", exchange -> {
            count.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"data\":[],\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (var purpose : List.of(NormalAiUsage.Purpose.CHAT, NormalAiUsage.Purpose.COMPILATION)) {
                var budget = ReleaseProviderBudget.continuous(usage, purpose);
                for (int i = 0; i < 3; i++) {
                    budget.checkChatCapacity(100);
                    var client = budget.httpTransport(ReleaseProviderBudget.Kind.CHAT, Duration.ofSeconds(3), base + "/chat/completions");
                    try (var response = client.newCall(request(base + "/chat/completions")).execute()) {
                        assertTrue(response.body().string().contains("private-answer-not-logged"));
                    }
                }
            }
            var budget = ReleaseProviderBudget.continuous(usage, NormalAiUsage.Purpose.CHAT);
            try (var response = budget.httpTransport(ReleaseProviderBudget.Kind.EMBEDDING, Duration.ofSeconds(3), base + "/embeddings")
                    .newCall(request(base + "/embeddings")).execute()) { assertEquals(200, response.code()); response.body().string(); }
            assertEquals(7, count.get());
            List<JsonNode> rows = events();
            assertEquals(7, rows.stream().filter(row -> "HTTP_SENT".equals(row.path("event").asText())).count());
            assertEquals(3, rows.stream().filter(row -> "HTTP_SENT".equals(row.path("event").asText()) && "COMPILATION".equals(row.path("purpose").asText())).count());
            var usages = rows.stream().filter(row -> "USAGE".equals(row.path("event").asText())).toList();
            assertEquals(7, usages.size());
            assertTrue(usages.stream().allMatch(row -> row.path("responseComplete").asBoolean() && row.path("providerCost").isNull()));
            assertEquals(113, usages.stream().mapToLong(row -> row.path("totalTokens").asLong()).sum());
            assertEquals("RETRIEVAL", usages.getLast().path("purpose").asText());
            assertEquals("EMBEDDING", usages.getLast().path("kind").asText());
            String persisted = Files.readString(directory.resolve("normal.jsonl"));
            assertFalse(persisted.contains("private-prompt")); assertFalse(persisted.contains("private-answer"));
            assertDoesNotThrow(() -> ReleaseProviderBudget.continuous(usage(), NormalAiUsage.Purpose.CHAT).checkChatCapacity(100));
        } finally { server.stop(0); }
    }

    @Test void concurrentAppendAndRestartRetainHistoryAndMissingOrPartialLedgerFailsClosed() throws Exception {
        var first = usage();
        var second = usage();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 108; i++) tasks.add(pool.submit(() -> {
                String id = first.begin(NormalAiUsage.Purpose.CHAT, ReleaseProviderBudget.Kind.CHAT);
                second.sent(id, NormalAiUsage.Purpose.CHAT, ReleaseProviderBudget.Kind.CHAT);
            }));
            for (var task : tasks) task.get();
        }
        assertEquals(108, events().stream().filter(row -> "HTTP_SENT".equals(row.path("event").asText())).count());
        byte[] before = Files.readAllBytes(directory.resolve("normal.jsonl"));
        usage().checkReady(); assertArrayEquals(before, Files.readAllBytes(directory.resolve("normal.jsonl")));
        Files.writeString(directory.resolve("normal.jsonl"), "partial", java.nio.file.StandardOpenOption.APPEND);
        assertThrows(SecurityException.class, first::checkReady);
        assertThrows(SecurityException.class, this::usage);
        Files.delete(directory.resolve("normal.jsonl"));
        assertThrows(SecurityException.class, first::checkReady);
    }

    @Test void normalWebIgnoresOldBudgetAndHaltWhileLocalPublicationStillUsesLegacyGuard() throws Exception {
        Path legacy = directory.resolve("old.ledger");
        Files.writeString(legacy, "CHEN_RELEASE_HTTP_BUDGET_V1|old|0|1\n1|CHAT|0|1\n");
        Files.writeString(directory.resolve("old.ledger.halt"), "historical-unknown");
        byte[] before = Files.readAllBytes(legacy);
        var release = new RagPublicationProperties(); release.setBudgetLedgerPath(legacy.toString()); release.setBudgetId("old");
        release.setMaxEmbeddingHttpAttempts(0); release.setMaxChatHttpAttempts(1);
        var config = new RagPublicationConfiguration();
        var normal = new StaticListableBeanFactory(Map.of("normal", usage()));
        assertDoesNotThrow(() -> config.releaseProviderBudget(release, normal.getBeanProvider(NormalAiUsage.class)).checkChatCapacity(2));
        var command = new StaticListableBeanFactory();
        var bounded = config.releaseProviderBudget(release, command.getBeanProvider(NormalAiUsage.class));
        assertThrows(SecurityException.class, () -> bounded.reserve(ReleaseProviderBudget.Kind.CHAT));
        Files.writeString(legacy, "corrupt-old-record\n");
        assertDoesNotThrow(() -> config.releaseProviderBudget(release, normal.getBeanProvider(NormalAiUsage.class)).checkChatCapacity(2));
        assertThrows(SecurityException.class, () -> config.releaseProviderBudget(release, command.getBeanProvider(NormalAiUsage.class)));
        assertEquals("historical-unknown", Files.readString(directory.resolve("old.ledger.halt")));
        // 先前的旧记录只由本测试修改，用于证明连续模式完全不读取；生产不会修改。
        assertTrue(new String(before, StandardCharsets.UTF_8).contains("1|CHAT|0|1"));
    }

    @Test void provider503IsRecordedOnceWithoutAnImplicitNetworkRetry() throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            count.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
            var client = ReleaseProviderBudget.continuous(usage(), NormalAiUsage.Purpose.CHAT)
                    .httpTransport(ReleaseProviderBudget.Kind.CHAT, Duration.ofSeconds(3), endpoint);
            assertThrows(IOException.class, () -> client.newCall(request(endpoint)).execute());
            assertEquals(1, count.get());
            assertEquals(1, events().stream().filter(row -> "HTTP_SENT".equals(row.path("event").asText())).count());
            assertTrue(events().stream().anyMatch(row -> "HTTP_STATUS".equals(row.path("event").asText()) && row.path("httpStatus").asInt() == 503));
            assertTrue(events().stream().anyMatch(row -> "HTTP_FAILED".equals(row.path("event").asText())));
        } finally { server.stop(0); }
    }

    @Test void compressedJsonAndSseClosedAtDoneStillRecordProviderUsage() throws Exception {
        var meter = usage();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var buffer = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(buffer)) {
                gzip.write("{\"usage\":{\"prompt_tokens\":13,\"completion_tokens\":3,\"total_tokens\":16}}".getBytes(StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, buffer.size()); exchange.getResponseBody().write(buffer.toByteArray()); exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
            var client = ReleaseProviderBudget.continuous(meter, NormalAiUsage.Purpose.COMPILATION)
                    .httpTransport(ReleaseProviderBudget.Kind.CHAT, Duration.ofSeconds(3), endpoint);
            try (var response = client.newCall(request(endpoint)).execute()) { assertTrue(response.body().string().contains("usage")); }
            assertEquals(16, events().stream().filter(row -> "USAGE".equals(row.path("event").asText())).findFirst().orElseThrow().path("totalTokens").asLong());
            var body = meter.response("synthetic-done", NormalAiUsage.Purpose.CHAT, ReleaseProviderBudget.Kind.CHAT,
                    okhttp3.ResponseBody.create("data: {\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}\n\ndata: [DONE]\n\n",
                            okhttp3.MediaType.get("text/event-stream")));
            try (body) {
                assertTrue(body.source().readUtf8LineStrict().contains("usage"));
                assertEquals("", body.source().readUtf8LineStrict());
                assertEquals("data: [DONE]", body.source().readUtf8LineStrict());
            }
            var earlyClose = events().stream().filter(row -> "synthetic-done".equals(row.path("requestId").asText())).findFirst().orElseThrow();
            assertEquals(3, earlyClose.path("totalTokens").asLong());
            assertEquals("REPORTED", earlyClose.path("usageStatus").asText());
            assertFalse(earlyClose.path("responseComplete").asBoolean());
        } finally { server.stop(0); }
    }
}
