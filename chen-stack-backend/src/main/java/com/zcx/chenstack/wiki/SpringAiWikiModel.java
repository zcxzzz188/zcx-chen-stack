package com.zcx.chenstack.wiki;

import com.openai.core.ClientOptions;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立 Wiki 调用；复用 SDK/HTTP 安全机制，不复用 G2 的模型、连接或账本实例。 */
public final class SpringAiWikiModel implements WikiModel, AutoCloseable {
    private final String id;
    private final URI endpoint;
    private final String apiKey;
    private final WikiProviderAuthorization authorization;
    private final Set<okhttp3.Call> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private SpringAiWikiModel(String id, URI endpoint, String apiKey, WikiProviderAuthorization authorization) {
        this.id = id; this.endpoint = endpoint; this.apiKey = apiKey; this.authorization = authorization;
    }

    public static SpringAiWikiModel loopback(URI base, String modelId) {
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
                || base.getUserInfo() != null || base.getRawQuery() != null || base.getFragment() != null
                || !"/v1".equals(base.getPath()) || modelId == null || !modelId.matches("[A-Za-z0-9_.:-]{1,100}")) {
            throw new IllegalArgumentException("WIKI_LOOPBACK_MODEL_REQUIRED");
        }
        return new SpringAiWikiModel(modelId, URI.create(base + "/chat/completions"), "synthetic-loopback-only", null);
    }

    public static SpringAiWikiModel deepseek(URI endpoint, String modelId, String apiKey, WikiProviderAuthorization authorization) {
        boolean local = "http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()) && endpoint.getPort() > 0;
        boolean remote = "https".equals(endpoint.getScheme()) && "api.deepseek.com".equals(endpoint.getHost())
                && (endpoint.getPort() == -1 || endpoint.getPort() == 443);
        if ((!local && !remote) || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getFragment() != null
                || !("/chat/completions".equals(endpoint.getPath()) || "/v1/chat/completions".equals(endpoint.getPath()))
                || !"deepseek-flash".equals(modelId) || apiKey == null || apiKey.isBlank() || authorization == null) {
            throw new IllegalArgumentException("WIKI_DEEPSEEK_CONFIGURATION_INVALID");
        }
        return new SpringAiWikiModel(modelId, endpoint, apiKey, authorization);
    }

    @Override public String id() { return id; }
    @Override public boolean external() { return authorization != null; }

    @Override public void authorize(String requestId, String manifestHash) {
        if (external()) authorization.require(requestId, manifestHash);
    }

    @Override public void outcomeUnknown() {
        if (external()) authorization.halt();
    }

    @Override public Reply generate(String system, String data, int maxOutputTokens) {
        if (external()) throw new SecurityException("WIKI_SEND_CONTEXT_REQUIRED");
        return invoke(system, data, maxOutputTokens, new SendContext(() -> {}), new ReleaseProviderBudget("", "", 0, 0));
    }

    @Override public Reply generate(String system, String data, int maxOutputTokens, SendContext context) {
        if (external() && (context.requestId() == null || context.manifestHash() == null)) throw new SecurityException("WIKI_SEND_CONTEXT_REQUIRED");
        ReleaseProviderBudget budget = external() ? authorization.budget(context.requestId(), context.manifestHash()) : new ReleaseProviderBudget("", "", 0, 0);
        if (external()) {
            try { budget.checkChatCapacity(1); }
            catch (SecurityException exhausted) { throw new SecurityException("WIKI_PROVIDER_BUDGET_EXHAUSTED"); }
        }
        return invoke(system, data, maxOutputTokens, context, budget);
    }

    private Reply invoke(String system, String data, int maxOutputTokens, SendContext context, ReleaseProviderBudget budget) {
        if (closed || maxOutputTokens < 1 || maxOutputTokens > (external() ? WikiProviderAuthorization.MAX_OUTPUT_TOKENS : 4096)) {
            throw new IllegalStateException("WIKI_OUTPUT_LIMIT_INVALID");
        }
        AtomicBoolean dispatched = new AtomicBoolean();
        WikiCallDiagnostics diagnostics = context.diagnostics();
        var wireUsage = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        okhttp3.OkHttpClient.Builder transportBuilder = budget.httpTransport(ReleaseProviderBudget.Kind.CHAT, Duration.ofSeconds(30), endpoint.toString())
                .newBuilder().proxy(java.net.Proxy.NO_PROXY);
        transportBuilder.eventListener(new okhttp3.EventListener() {
            @Override public void requestHeadersEnd(okhttp3.Call call, okhttp3.Request request) {
                diagnostics.requestHeadersSent = true;
            }
            @Override public void requestBodyEnd(okhttp3.Call call, long byteCount) {
                diagnostics.requestBodySent = true;
            }
            @Override public void responseHeadersEnd(okhttp3.Call call, okhttp3.Response response) {
                diagnostics.responseHeaders(response, apiKey);
            }
            @Override public void callFailed(okhttp3.Call call, java.io.IOException failure) {
                diagnostics.transportFailure(failure, call.timeout().timeoutNanos());
            }
        });
        // 请求预检在计费预留之前；network interceptor 的一次性门禁还拦截 OkHttp 的503隐式follow-up。
        transportBuilder.interceptors().addFirst(chain -> {
            context.onCancel(chain.call()::cancel); context.requireActive();
            active.add(chain.call());
            try {
                if (closed) { chain.call().cancel(); throw new java.io.IOException("WIKI_MODEL_CLOSED"); }
                if (external()) validateWire(chain.request(), maxOutputTokens);
                okhttp3.Response response = chain.proceed(chain.request());
                // BridgeInterceptor 在返回 application 层前透明解压；network 层仍是压缩字节。
                try {
                    byte[] body = response.peekBody(1_048_577).bytes();
                    if (body.length > 1_048_576) throw new IllegalStateException("WIKI_RESPONSE_LIMIT");
                    if (response.isSuccessful()) {
                        var wire = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                        var reasoning = wire.path("usage").path("completion_tokens_details").path("reasoning_tokens");
                        if (external() && (!id.equals(wire.path("model").asText()) || !reasoning.isMissingNode()
                                && (!reasoning.isIntegralNumber() || !reasoning.canConvertToLong() || reasoning.longValue() != 0))) {
                            throw new IllegalStateException("WIKI_PROVIDER_RESPONSE_INVALID");
                        }
                        wireUsage.set(wire.path("usage"));
                        diagnostics.reliableUsage = reliableUsage(wireUsage.get(), maxOutputTokens);
                    }
                    return response;
                } catch (java.io.IOException | RuntimeException failure) { response.close(); throw failure; }
            } finally { active.remove(chain.call()); }
        });
        transportBuilder.addNetworkInterceptor(chain -> {
            try {
                if (closed) { chain.call().cancel(); throw new IllegalStateException("WIKI_MODEL_CLOSED"); }
                if (external()) authorize(context.requestId(), context.manifestHash());
                context.beforeSend(); dispatched.set(true); diagnostics.dispatchBoundary = true;
                return chain.proceed(chain.request());
            } catch (RuntimeException denied) { throw new java.io.IOException("WIKI_SEND_DENIED", denied); }
        });
        okhttp3.OkHttpClient transport = transportBuilder.build();
        String base = endpoint.toString().substring(0, endpoint.toString().length() - "/chat/completions".length());
        // SDK 的 RequestOptions 会覆盖 OkHttp timeout；两层都显式限定同一发送期限。
        ClientOptions client = ClientOptions.builder().baseUrl(base).apiKey(apiKey).maxRetries(0).timeout(Duration.ofSeconds(30))
                .httpClient(new com.openai.client.okhttp.OkHttpClient(transport)).build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(new com.openai.client.OpenAIClientImpl(client))
                .openAiClientAsync(new com.openai.client.OpenAIClientAsyncImpl(client))
                .options(OpenAiChatOptions.builder().model(id).maxRetries(0).build()).build();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(id).maxRetries(0).maxTokens(maxOutputTokens)
                .temperature(0.0).toolChoice("none").toolCallbacks(List.of())
                .extraBody(Map.of("thinking", Map.of("type", "disabled"))).build();
        try {
        var response = model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(data)), options));
        Long input = token(wireUsage.get(), "prompt_tokens");
        Long output = token(wireUsage.get(), "completion_tokens");
        Long total = token(wireUsage.get(), "total_tokens");
        String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
        if (external() && (input == null || output == null || total == null || input <= 0 || output <= 0
                || total != input + output || input > WikiProviderAuthorization.INPUT_TOKEN_CEILING || output > maxOutputTokens)) {
            diagnostics.failure(new IllegalStateException("WIKI_USAGE_UNKNOWN"));
            authorization.halt();
            return new Reply(text, null, input, output);
        }
        Reply reply = new Reply(text, total != null && total > 0 ? total : null, input, output);
        if (response == null || response.getResult() == null || response.getResult().getOutput().hasToolCalls()
                || !"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) {
            if (reply.usageTokens() != null) throw new KnownUsageFailure("WIKI_MODEL_TEXT_REQUIRED", reply);
            throw new IllegalStateException("WIKI_MODEL_TEXT_REQUIRED");
        }
        return reply;
        } catch (RuntimeException failure) {
            diagnostics.failure(failure);
            if (external() && dispatched.get()) authorization.halt();
            if (!(failure instanceof KnownUsageFailure) && diagnostics.reliableUsage) {
                throw new KnownUsageFailure("WIKI_MODEL_FAILED", new Reply(null, token(wireUsage.get(), "total_tokens"),
                        token(wireUsage.get(), "prompt_tokens"), token(wireUsage.get(), "completion_tokens")));
            }
            throw failure;
        } finally {
            context.cancel(); transport.dispatcher().cancelAll(); transport.connectionPool().evictAll();
            transport.dispatcher().executorService().shutdownNow();
        }
    }

    private static boolean reliableUsage(com.fasterxml.jackson.databind.JsonNode usage, int maxOutputTokens) {
        Long input = token(usage, "prompt_tokens"), output = token(usage, "completion_tokens"), total = token(usage, "total_tokens");
        return input != null && output != null && total != null && input > 0 && output > 0
                && input <= WikiProviderAuthorization.INPUT_TOKEN_CEILING && output <= maxOutputTokens && total == input + output;
    }

    private void validateWire(okhttp3.Request request, int maxTokens) throws java.io.IOException {
        okio.Buffer buffer = new okio.Buffer();
        if (request.body() == null || request.body().contentLength() > WikiProviderAuthorization.MAX_WIRE_BYTES) throw new SecurityException("WIKI_WIRE_LIMIT");
        request.body().writeTo(buffer);
        if (buffer.size() > WikiProviderAuthorization.MAX_WIRE_BYTES) throw new SecurityException("WIKI_WIRE_LIMIT");
        var wire = new com.fasterxml.jackson.databind.ObjectMapper().readTree(buffer.readByteArray());
        if (!"POST".equals(request.method()) || !id.equals(wire.path("model").asText())
                || wire.path("max_tokens").asInt() != maxTokens || !"disabled".equals(wire.path("thinking").path("type").asText())
                || !"none".equals(wire.path("tool_choice").asText()) || wire.path("tools").size() != 0 || wire.path("stream").asBoolean()
                || wire.path("messages").size() != 2 || !"system".equals(wire.path("messages").path(0).path("role").asText())
                || !"user".equals(wire.path("messages").path(1).path("role").asText())) throw new SecurityException("WIKI_WIRE_INVALID");
    }

    private static Long token(com.fasterxml.jackson.databind.JsonNode usage, String name) {
        if (usage == null || !usage.path(name).isIntegralNumber() || !usage.path(name).canConvertToLong()) return null;
        return usage.path(name).asLong();
    }

    @Override public void close() {
        closed = true;
        active.forEach(okhttp3.Call::cancel);
    }
}
