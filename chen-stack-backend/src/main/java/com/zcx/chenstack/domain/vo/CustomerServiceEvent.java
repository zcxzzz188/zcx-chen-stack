package com.zcx.chenstack.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerServiceEvent(String type, String text, String action,
                                   List<EditableArticle> articles, Integer page,
                                   Integer pageSize, Boolean hasMore, String supportStatus, String errorCode,
                                   List<KnowledgeSource> sources) {
    public CustomerServiceEvent(String type, String text, String action, List<EditableArticle> articles,
                                Integer page, Integer pageSize, Boolean hasMore, String supportStatus, String errorCode) {
        this(type, text, action, articles, page, pageSize, hasMore, supportStatus, errorCode, null);
    }
    public CustomerServiceEvent(String type, String text, String action, List<EditableArticle> articles,
                                Integer page, Integer pageSize, Boolean hasMore, String supportStatus) {
        this(type, text, action, articles, page, pageSize, hasMore, supportStatus, null);
    }
    public record EditableArticle(Integer articleId, String title, Integer editStatus,
                                  Integer examineStatus, boolean canEdit) {}
    public record KnowledgeSource(String title, String url, String version) {}

    public static CustomerServiceEvent knowledgeSources(List<KnowledgeSource> sources) {
        return new CustomerServiceEvent("KNOWLEDGE_SOURCES", null, null, null, null, null, null, null, null, sources);
    }

    public static CustomerServiceEvent text(String text) {
        return new CustomerServiceEvent("TEXT", text, null, null, null, null, null, null);
    }

    public static CustomerServiceEvent state(String type, String text, String action, String supportStatus) {
        return new CustomerServiceEvent(type, text, action, null, null, null, null, supportStatus);
    }

    public static CustomerServiceEvent failure(String errorCode, String text, String action) {
        return new CustomerServiceEvent("ERROR_STATE", text, action, null, null, null, null, null, errorCode);
    }
}
