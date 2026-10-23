package com.zcx.chenstack.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

import java.util.List;

/** 只将当前目录中验证过的公开资料编码为模型输入。 */
public record KnowledgeEvidence(List<Document> chunks, KnowledgeCatalog catalog) {
    public KnowledgeEvidence { chunks = List.copyOf(chunks); java.util.Objects.requireNonNull(catalog); }
    private static final ObjectMapper JSON = new ObjectMapper();

    public String context() {
        catalog.requireCurrent();
        try {
            return JSON.writeValueAsString(chunks.stream().map(d -> java.util.Map.of(
                    "source_id", d.getMetadata().get("source_id"), "version", d.getMetadata().get("version"),
                    "title", catalog.source((String) d.getMetadata().get("source_id")).title(),
                    "text", d.getText())).toList());
        } catch (JsonProcessingException error) { throw new IllegalStateException("证据编码失败"); }
    }

}
