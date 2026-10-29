package com.zcx.chenstack.rag;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Provider 诊断只输出协议白名单及长度，绝不复制请求正文或错误原文。 */
final class ProviderDiagnostics {
    private static final int LIMIT = 65536;
    private static final String INVALID = "{\"diagnostic\":\"MALFORMED_OR_OVERSIZED\"}";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> MODELS = Set.of("deepseek-flash", "deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner", "gpt-5-mini");
    private static final Set<String> TOOLS = Set.of("readPublishedWiki", "searchPublishedWiki", "readPlatformHelp",
            "searchPlatformHelp", "listMyEditableArticles", "showNavigation");
    private static final Set<String> PROPERTIES = Set.of("query", "title", "page", "pageSize", "destination");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "integer", "number", "boolean", "null");
    private static final Set<String> FIELDS = Set.of("model", "messages", "tools", "tool_choice", "strict", "thinking",
            "reasoning_effort", "stream", "stream_options", "max_tokens", "max_completion_tokens", "temperature", "top_p",
            "stop", "response_format", "n", "parallel_tool_calls", "seed", "user", "metadata", "store", "verbosity",
            "logprobs", "top_logprobs", "frequency_penalty", "presence_penalty", "service_tier");
    private static final Set<String> ERRORS = Set.of("invalid_request_error", "invalid_request", "invalid_request_body",
            "invalid_parameter", "invalid_value", "invalid_json", "bad_request", "model_not_found", "invalid_model");
    private static final String PARAM = "(?:model|messages|tools|tool_choice|thinking|reasoning_effort|stream|stream_options|max_tokens|max_completion_tokens|temperature|top_p|response_format)"
            + "(?:\\[[0-9]{1,3}\\]|\\.[0-9]{1,3}|\\.(?:role|content|type|function|name|parameters|properties|required|additionalProperties|strict|include_usage|include_obfuscation|query|title|page|pageSize|destination|enum|items|tool_calls|tool_call_id))*";
    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                .maxStringLength(LIMIT).maxNumberLength(64).build());
    }
    private ProviderDiagnostics() { }

    static String request(byte[] body) {
        try {
            JsonNode root = parse(body);
            Map<String, Object> result = new LinkedHashMap<>();
            Set<String> fields = new TreeSet<>();
            int unknown = 0;
            var names = root.fieldNames();
            while (names.hasNext()) { String name = names.next(); if (FIELDS.contains(name)) fields.add(name); else unknown++; }
            result.put("fields", fields); result.put("unknownFieldCount", unknown);
            result.put("model", allowed(root.path("model"), MODELS));
            result.put("modelSha256", digest(root.path("model").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            for (String field : List.of("stream", "strict", "max_tokens", "max_completion_tokens", "temperature", "top_p")) {
                result.put(field, scalar(root.path(field)));
            }
            result.put("thinkingType", allowed(root.path("thinking").path("type"), Set.of("enabled", "disabled")));
            result.put("reasoning_effort", allowed(root.path("reasoning_effort"), Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max")));
            JsonNode choice = root.path("tool_choice");
            result.put("tool_choice", choice.isObject() ? Map.of("type", allowed(choice.path("type"), Set.of("function")),
                    "name", allowed(choice.path("function").path("name"), TOOLS)) : allowed(choice, Set.of("auto", "none", "required")));
            result.put("stream_options", Map.of("include_usage", scalar(root.path("stream_options").path("include_usage")),
                    "include_obfuscation", scalar(root.path("stream_options").path("include_obfuscation"))));
            List<Object> messages = new ArrayList<>();
            JsonNode input = root.path("messages");
            result.put("messageCount", input.isArray() ? input.size() : -1);
            for (int i = 0; input.isArray() && i < Math.min(input.size(), 32); i++) {
                JsonNode message = input.get(i); JsonNode content = message.path("content");
                messages.add(Map.of("role", allowed(message.path("role"), Set.of("system", "developer", "user", "assistant", "tool", "function")),
                        "contentType", content.getNodeType().name(), "textLength", content.isTextual() ? content.textValue().length() : 0,
                        "toolCallCount", message.path("tool_calls").isArray() ? message.path("tool_calls").size() : 0));
            }
            result.put("messages", messages);
            List<Object> tools = new ArrayList<>(); JsonNode definitions = root.path("tools");
            result.put("toolCount", definitions.isArray() ? definitions.size() : 0);
            for (int i = 0; definitions.isArray() && i < Math.min(definitions.size(), 32); i++) {
                JsonNode tool = definitions.get(i); JsonNode function = tool.path("function"); JsonNode schema = function.path("parameters");
                Set<String> types = new TreeSet<>(); Set<String> properties = new TreeSet<>();
                int unknownProperties = schema(schema, types, properties);
                tools.add(Map.of("type", allowed(tool.path("type"), Set.of("function")), "name", allowed(function.path("name"), TOOLS),
                        "strict", scalar(function.path("strict")), "schemaSha256", digest(JSON.writeValueAsBytes(schema)),
                        "schemaTypes", types, "properties", properties, "unknownPropertyCount", unknownProperties));
            }
            result.put("tools", tools);
            return encode(result);
        } catch (Exception ignored) { return INVALID; }
    }

    static String error(byte[] body) {
        try {
            JsonNode root = parse(body).path("error");
            if (!root.isObject()) return INVALID;
            String param = root.path("param").asText("");
            String safeParam = !param.isEmpty() && param.length() <= 256 && param.matches(PARAM) ? param : "REDACTED";
            String message = root.path("message").asText(""); String lower = message.toLowerCase(Locale.ROOT);
            String hint = safeParam.startsWith("messages") ? "INVALID_MESSAGE"
                    : safeParam.startsWith("model") || lower.contains("model not found") || lower.contains("invalid model") ? "INVALID_MODEL"
                    : lower.contains("schema") && lower.contains("tool") || safeParam.startsWith("tools") && safeParam.contains("parameters")
                    ? "INVALID_TOOL_SCHEMA" : "UNCLASSIFIED";
            return encode(Map.of("type", allowed(root.path("type"), ERRORS), "code", allowed(root.path("code"), ERRORS),
                    "param", safeParam, "hint", hint, "messageLength", message.length()));
        } catch (Exception ignored) { return INVALID; }
    }

    private static JsonNode parse(byte[] body) throws Exception {
        if (body == null || body.length == 0 || body.length > LIMIT) throw new IllegalArgumentException();
        JsonNode root = JSON.readTree(body);
        if (root == null || !root.isObject()) throw new IllegalArgumentException();
        return root;
    }
    private static Object scalar(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return "ABSENT";
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber() && Double.isFinite(node.doubleValue()) && Math.abs(node.doubleValue()) <= 1000000) return node.numberValue();
        return "INVALID";
    }
    private static String allowed(JsonNode node, Set<String> values) {
        if (node.isMissingNode() || node.isNull()) return "ABSENT";
        return node.isTextual() && values.contains(node.textValue()) ? node.textValue() : "REDACTED";
    }
    private static int schema(JsonNode node, Set<String> types, Set<String> properties) {
        int unknown = 0;
        if (node.isObject()) {
            JsonNode type = node.path("type");
            if (type.isTextual() && TYPES.contains(type.textValue())) types.add(type.textValue());
            var fields = node.path("properties").fields();
            while (fields.hasNext()) {
                var field = fields.next(); if (PROPERTIES.contains(field.getKey())) properties.add(field.getKey()); else unknown++;
                unknown += schema(field.getValue(), types, properties);
            }
            for (String child : List.of("items", "anyOf", "allOf", "oneOf", "additionalProperties")) unknown += schema(node.path(child), types, properties);
        } else if (node.isArray()) { for (JsonNode child : node) unknown += schema(child, types, properties); }
        return unknown;
    }
    private static String digest(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String encode(Object value) throws Exception { String result = JSON.writeValueAsString(value); return result.length() <= LIMIT ? result : INVALID; }
}
