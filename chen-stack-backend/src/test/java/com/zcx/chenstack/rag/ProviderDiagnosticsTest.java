package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderDiagnosticsTest {
    private final ObjectMapper json = new ObjectMapper();
    private byte[] bytes(Object value) throws Exception { return json.writeValueAsBytes(value); }

    @Test void retainsProtocolStructureWithoutContentOrSchemaValues() throws Exception {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("title", Map.of("type", "string",
                "description", "synthetic-private-description", "enum", List.of("synthetic-private-enum"))), "required", List.of("title"));
        String summary = ProviderDiagnostics.request(bytes(Map.of("model", "deepseek-flash", "stream", true,
                "stream_options", Map.of("include_usage", true), "max_tokens", 2048,
                "thinking", Map.of("type", "disabled"), "tool_choice", "auto",
                "messages", List.of(Map.of("role", "user", "content", "synthetic-private-question")),
                "tools", List.of(Map.of("type", "function", "function", Map.of("name", "readPublishedWiki", "strict", false, "parameters", schema))))));
        JsonNode result = json.readTree(summary);
        assertEquals("deepseek-flash", result.path("model").asText());
        assertEquals("disabled", result.path("thinkingType").asText());
        assertEquals("auto", result.path("tool_choice").asText());
        assertTrue(result.path("stream").asBoolean());
        assertEquals(2048, result.path("max_tokens").asInt());
        assertEquals(1, result.path("messageCount").asInt());
        assertEquals(26, result.path("messages").get(0).path("textLength").asInt());
        assertEquals("readPublishedWiki", result.path("tools").get(0).path("name").asText());
        assertFalse(result.path("tools").get(0).path("strict").asBoolean());
        assertTrue(result.path("tools").get(0).path("schemaSha256").asText().matches("[a-f0-9]{64}"));
        assertFalse(summary.contains("synthetic-private"));
    }

    @Test void neverEchoesSyntheticSecretsFromAnyFreeTextPosition() throws Exception {
        List<String> secrets = List.of("synthetic-password-sentinel", "sk-synthetic-api-key-sentinel",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzeW50aGV0aWMifQ.synthetic-signature",
                "Cookie: synthetic-session=synthetic-cookie-sentinel");
        for (String secret : secrets) {
            Map<String, Object> schema = Map.of("type", secret, "description", secret, "enum", List.of(secret),
                    "properties", Map.of(secret, Map.of("type", "string", "default", secret)));
            String request = ProviderDiagnostics.request(bytes(Map.of("model", secret, secret, secret,
                    "thinking", Map.of("type", secret), "reasoning_effort", secret, "tool_choice", secret,
                    "stream", secret, "max_tokens", secret, "messages", List.of(Map.of("role", secret, "content", secret)),
                    "tools", List.of(Map.of("type", secret, "function", Map.of("name", secret, "description", secret, "strict", secret, "parameters", schema))))));
            String error = ProviderDiagnostics.error(bytes(Map.of("error", Map.of("type", secret, "code", secret, "param", secret, "message", secret))));
            assertFalse(request.contains(secret)); assertFalse(error.contains(secret));
            assertEquals("REDACTED", json.readTree(request).path("model").asText());
            assertEquals(1, json.readTree(request).path("unknownFieldCount").asInt());
            assertEquals("REDACTED", json.readTree(error).path("param").asText());
        }
    }

    @Test void errorKeepsOnlyKnownTypeAndExplicitProtocolFieldPath() throws Exception {
        String summary = ProviderDiagnostics.error(bytes(Map.of("error", Map.of("type", "invalid_request_error", "code", "invalid_parameter",
                "param", "tools[0].function.parameters.properties.title.type", "message", "Invalid tool schema; synthetic-secret-value"))));
        JsonNode result = json.readTree(summary);
        assertEquals("invalid_request_error", result.path("type").asText());
        assertEquals("tools[0].function.parameters.properties.title.type", result.path("param").asText());
        assertEquals("INVALID_TOOL_SCHEMA", result.path("hint").asText());
        assertFalse(summary.contains("synthetic-secret-value"));
        for (String badPath : List.of("tools[0].function.name.synthetic-secret", "messages[0].content.PasswordSecret", "model?secret=value", "model\nsecret")) {
            String denied = ProviderDiagnostics.error(bytes(Map.of("error", Map.of("param", badPath, "message", "synthetic-secret"))));
            assertEquals("REDACTED", json.readTree(denied).path("param").asText());
            assertFalse(denied.contains("synthetic-secret"));
        }
    }

    @Test void malformedOversizedDuplicateAndDeepInputReturnFixedSafeMarker() {
        for (byte[] input : new byte[][] { null, new byte[0], new byte[65537], "synthetic-secret".getBytes(StandardCharsets.UTF_8),
                "{} trailing-secret".getBytes(StandardCharsets.UTF_8), "[]".getBytes(StandardCharsets.UTF_8),
                "{\"model\":\"one\",\"model\":\"synthetic-secret\"}".getBytes(StandardCharsets.UTF_8),
                ("{\"secret\":".repeat(40) + "{}" + "}".repeat(40)).getBytes(StandardCharsets.UTF_8) }) {
            assertEquals("{\"diagnostic\":\"MALFORMED_OR_OVERSIZED\"}", ProviderDiagnostics.request(input));
            assertEquals("{\"diagnostic\":\"MALFORMED_OR_OVERSIZED\"}", ProviderDiagnostics.error(input));
        }
    }
}
