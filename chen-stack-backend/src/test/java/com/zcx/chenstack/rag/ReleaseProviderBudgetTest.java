package com.zcx.chenstack.rag;

import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.config.AiConfig;
import com.zcx.chenstack.config.RagConfig;
import com.zcx.chenstack.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
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
        assertThrows(SecurityException.class, () -> new ReleaseProviderBudget(file.toString(), "synthetic", 64, 21));
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
}
