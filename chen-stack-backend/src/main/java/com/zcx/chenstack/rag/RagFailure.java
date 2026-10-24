package com.zcx.chenstack.rag;

/** 只传播安全状态码，不携带 Provider 响应、凭据或网络地址。 */
public final class RagFailure extends RuntimeException {
    public enum Code { QDRANT_UNAVAILABLE, EMBEDDING_UNAVAILABLE, DIMENSION_MISMATCH, INDEX_MISMATCH, COST_LIMIT, DISABLED, MISSING_KEY, SOURCE_STILL_APPROVED, RECOVERY_REQUIRED }
    private final Code code;
    public RagFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
