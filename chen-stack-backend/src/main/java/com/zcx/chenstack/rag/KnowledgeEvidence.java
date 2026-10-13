package com.zcx.chenstack.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

import java.util.List;

/** 引用仅从已验证的服务端目录生成，模型无权提供引用 URL。 */
public record KnowledgeEvidence(List<Document> chunks, KnowledgeCatalog catalog) {
    public static final String SOURCE_MARKER = "已核实来源（服务端）";
    private static final ObjectMapper JSON = new ObjectMapper();

    public String context() {
        try {
            return JSON.writeValueAsString(chunks.stream().map(d -> java.util.Map.of(
                    "source_id", d.getMetadata().get("source_id"), "version", d.getMetadata().get("version"),
                    "text", d.getText())).toList());
        } catch (JsonProcessingException error) { throw new IllegalStateException("证据编码失败"); }
    }

    public String citations() {
        List<String> verifiedIds = chunks.stream().map(catalog::verified).filter(java.util.Objects::nonNull)
                .map(d -> (String) d.getMetadata().get("source_id")).distinct().toList();
        if (verifiedIds.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n\n模型正文不属于来源认证；以下仅列出本次使用的已审校资料。\n" + SOURCE_MARKER + "：");
        verifiedIds.forEach(id -> {
            KnowledgeDocument source = catalog.source(id);
            String title = source.title().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("[", "&#91;").replace("]", "&#93;");
            text.append("\n- [").append(title).append("](").append(source.sourceUrl()).append(")")
                    .append("（版本 ").append(source.version()).append("）");
        });
        return text.toString();
    }
}
