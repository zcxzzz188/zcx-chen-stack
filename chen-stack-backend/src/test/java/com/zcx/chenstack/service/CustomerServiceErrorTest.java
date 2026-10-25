package com.zcx.chenstack.service;

import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.config.AiConfig;
import com.zcx.chenstack.rag.RagFailure;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomerServiceErrorTest {
    @Test void localBudgetRequiresTheTrustedSecurityExceptionAndNeverGuessesFromText() {
        assertEquals(CustomerServiceError.LOCAL_BUDGET_EXHAUSTED, CustomerServiceError.classify(
                new IllegalStateException("SECRET", new SecurityException("RELEASE_PROVIDER_HTTP_BUDGET_EXHAUSTED"))));
        assertEquals(CustomerServiceError.SERVICE_UNAVAILABLE, CustomerServiceError.classify(
                new IllegalStateException("RELEASE_PROVIDER_HTTP_BUDGET_EXHAUSTED")));
        assertEquals(CustomerServiceError.SERVICE_UNAVAILABLE, CustomerServiceError.classify(new SecurityException("SECRET")));
        assertEquals("智能客服当前模型调用额度已用尽，请联系管理员增加额度后再试。",
                CustomerServiceError.LOCAL_BUDGET_EXHAUSTED.description());
    }

    @Test void providerHttpStatusIsDistinctFromLocalBudgetAndArbitraryErrorMessages() {
        Map<Integer, CustomerServiceError> expected = Map.of(402, CustomerServiceError.MODEL_BALANCE_INSUFFICIENT,
                429, CustomerServiceError.MODEL_RATE_LIMITED, 503, CustomerServiceError.MODEL_SERVICE_ERROR,
                401, CustomerServiceError.MODEL_SERVICE_ERROR, 504, CustomerServiceError.MODEL_TIMEOUT);
        expected.forEach((status, code) -> {
            assertEquals(code, CustomerServiceError.classify(WebClientResponseException.create(status, "SECRET",
                    HttpHeaders.EMPTY, "SECRET".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
            assertEquals(code, CustomerServiceError.classify(new NonTransientAiException(status + " - SECRET")));
        });
        assertEquals(CustomerServiceError.MODEL_RATE_LIMITED, CustomerServiceError.classify(
                new HttpClientErrorException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)));
        assertEquals(CustomerServiceError.MODEL_SERVICE_ERROR, CustomerServiceError.classify(new TransientAiException("unknown quota issue")));
        assertEquals(CustomerServiceError.SERVICE_UNAVAILABLE, CustomerServiceError.classify(new IllegalStateException("402 - SECRET")));
    }

    @Test void timeoutAndNetworkFailuresRemainSeparateAndDoNotLeakTransportDetails() {
        for (Throwable error : new Throwable[]{new TimeoutException("SECRET"), new java.net.SocketTimeoutException("SECRET"),
                new java.net.http.HttpTimeoutException("SECRET"), io.netty.handler.timeout.ReadTimeoutException.INSTANCE}) {
            assertEquals(CustomerServiceError.MODEL_TIMEOUT, CustomerServiceError.classify(new IllegalStateException(error)));
        }
        assertEquals("智能客服响应超时，请稍后重试。", CustomerServiceError.MODEL_TIMEOUT.description());
        var network = new WebClientRequestException(new ConnectException("SECRET"), org.springframework.http.HttpMethod.POST,
                URI.create("https://private.invalid/SECRET"), HttpHeaders.EMPTY);
        assertEquals(CustomerServiceError.MODEL_NETWORK_ERROR, CustomerServiceError.classify(network));
        assertFalse(CustomerServiceError.classify(network).description().contains("SECRET"));
    }

    @Test void knowledgeAndIncompleteResponsesCannotBeMisreportedAsQuota() {
        assertEquals(CustomerServiceError.KNOWLEDGE_UNAVAILABLE, CustomerServiceError.classify(
                new IllegalStateException(new RagFailure(RagFailure.Code.EMBEDDING_UNAVAILABLE))));
        assertEquals(CustomerServiceError.SSE_INCOMPLETE, CustomerServiceError.classify(new DecodingException("SECRET")));
        assertEquals(CustomerServiceError.SSE_INCOMPLETE, CustomerServiceError.classify(CustomerServiceError.SSE_INCOMPLETE.exception()));
    }

    @Test void actualSpringAiSyncAndStreamHttpErrorsKeepClassifiableStatusWithoutRetries() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger responseStatus = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"SECRET_INTERNAL_PATH_API_KEY\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            AiConfig config = new AiConfig();
            ReflectionTestUtils.setField(config, "apiKey", "fixture-only");
            ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(config, "completionsPath", "/chat/completions");
            ReflectionTestUtils.setField(config, "model", "fixture");
            ReflectionTestUtils.setField(config, "temperature", 0.0);
            ReflectionTestUtils.setField(config, "maxTokens", 16);
            var model = config.openAiChatModel(new ReleaseProviderBudget("", "", 64, 20));
            for (int status : new int[]{402, 429, 503}) {
                responseStatus.set(status);
                CustomerServiceError expected = switch (status) {
                    case 402 -> CustomerServiceError.MODEL_BALANCE_INSUFFICIENT;
                    case 429 -> CustomerServiceError.MODEL_RATE_LIMITED;
                    default -> CustomerServiceError.MODEL_SERVICE_ERROR;
                };
                RuntimeException sync = assertThrows(RuntimeException.class, () -> model.call("synthetic"));
                RuntimeException stream = assertThrows(RuntimeException.class, () -> model.stream(
                        new org.springframework.ai.chat.prompt.Prompt("synthetic")).collectList().block(Duration.ofSeconds(8)));
                assertEquals(expected, CustomerServiceError.classify(sync));
                assertEquals(expected, CustomerServiceError.classify(stream));
                assertFalse(expected.description().contains("SECRET"));
            }
            assertEquals(6, requests.get());
        } finally { server.stop(0); }
    }
}
