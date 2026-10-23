package com.zcx.chenstack.rag;

import java.time.Instant;
import java.util.List;

/** 只承载经过人工审校的公开帮助资料，不承载用户业务数据。 */
public record KnowledgeDocument(String sourceId, String title, String text, String version,
                                String sourceType, String sourceUrl, String visibility,
                                String reviewStatus, Instant updatedAt, List<String> keywords) {
    public KnowledgeDocument {
        keywords = keywords == null ? null : List.copyOf(keywords);
    }
}
