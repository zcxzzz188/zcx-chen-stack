package com.zcx.chenstack.service;

import com.zcx.chenstack.rag.RagFailure;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** 仅依据异常类型和 Provider HTTP 状态分类，对外不传播原始响应或异常消息。 */
public enum CustomerServiceError {
    LOCAL_BUDGET_EXHAUSTED("智能客服当前模型调用额度已用尽，请联系管理员增加额度后再试。"),
    MODEL_RATE_LIMITED("模型服务请求受到限流，暂时不可用，请稍后重试。"),
    MODEL_BALANCE_INSUFFICIENT("模型服务商余额不足，暂时不可用，请联系管理员处理。"),
    MODEL_TIMEOUT("智能客服响应超时，请稍后重试。"),
    MODEL_NETWORK_ERROR("模型服务网络连接异常，请稍后重试。"),
    MODEL_SERVICE_ERROR("模型服务暂时不可用，请稍后重试；具体原因请联系管理员检查。"),
    KNOWLEDGE_UNAVAILABLE("知识查询服务暂时不可用，当前无法确认相关知识。"),
    SSE_INCOMPLETE("智能客服响应异常，回答未完成，请稍后重试。"),
    SERVICE_UNAVAILABLE("智能客服服务暂时不可用，请稍后重试。");

    private final String description;

    CustomerServiceError(String description) { this.description = description; }

    public String description() { return description; }

    public Failure exception() { return new Failure(this); }

    public static CustomerServiceError classify(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        CustomerServiceError fallback = SERVICE_UNAVAILABLE;
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof Failure failure) return failure.code;
            if (cause instanceof RagFailure) return KNOWLEDGE_UNAVAILABLE;
            if (cause instanceof SecurityException
                    && "RELEASE_PROVIDER_HTTP_BUDGET_EXHAUSTED".equals(cause.getMessage())) return LOCAL_BUDGET_EXHAUSTED;
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) return MODEL_TIMEOUT;
            if (cause instanceof WebClientResponseException response) return providerStatus(response.getStatusCode().value());
            if (cause instanceof RestClientResponseException response) return providerStatus(response.getStatusCode().value());
            if (cause instanceof com.openai.errors.OpenAIServiceException response) return providerStatus(response.statusCode());
            if (cause instanceof com.openai.errors.OpenAIInvalidDataException) return SSE_INCOMPLETE;
            if (cause instanceof NonTransientAiException || cause instanceof TransientAiException) {
                // Spring AI 1.1.8 RetryUtils 的状态前缀为 "<HTTP status> - <body>"。
                String message = cause.getMessage();
                if (message != null && message.matches("(?s)^[0-9]{3} - .*")) {
                    return providerStatus(Integer.parseInt(message.substring(0, 3)));
                }
                return MODEL_SERVICE_ERROR;
            }
            if (cause instanceof DecodingException) return SSE_INCOMPLETE;
            if (cause instanceof IOException) fallback = MODEL_NETWORK_ERROR;
            if (cause instanceof WebClientRequestException && cause.getCause() == null) return MODEL_NETWORK_ERROR;
        }
        return fallback;
    }

    private static CustomerServiceError providerStatus(int status) {
        // 当前正式 Provider DeepSeek 文档明确约定 402=余额不足、429=请求速率/并发上限。
        return switch (status) {
            case 402 -> MODEL_BALANCE_INSUFFICIENT;
            case 429 -> MODEL_RATE_LIMITED;
            case 408, 504 -> MODEL_TIMEOUT;
            default -> MODEL_SERVICE_ERROR;
        };
    }

    public static final class Failure extends RuntimeException {
        private final CustomerServiceError code;
        private Failure(CustomerServiceError code) { super(code.name()); this.code = code; }
    }
}
