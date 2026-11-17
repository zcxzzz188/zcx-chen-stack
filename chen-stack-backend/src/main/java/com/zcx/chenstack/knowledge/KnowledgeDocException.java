package com.zcx.chenstack.knowledge;

/** 知识文档通道的业务异常；{@code code} 为稳定的机器可读原因码。 */
public class KnowledgeDocException extends RuntimeException {
    private final int status;
    private final String code;

    public KnowledgeDocException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static KnowledgeDocException bad(String code, String message) {
        return new KnowledgeDocException(400, code, message);
    }

    public static KnowledgeDocException denied() {
        return new KnowledgeDocException(403, "KNOWLEDGE_FORBIDDEN", "没有此知识文档操作权限");
    }

    public static KnowledgeDocException missing() {
        return new KnowledgeDocException(404, "KNOWLEDGE_NOT_FOUND", "知识文档不存在");
    }

    public static KnowledgeDocException conflict(String code, String message) {
        return new KnowledgeDocException(409, code, message);
    }

    public static KnowledgeDocException unavailable(String code, String message) {
        return new KnowledgeDocException(503, code, message);
    }
}
