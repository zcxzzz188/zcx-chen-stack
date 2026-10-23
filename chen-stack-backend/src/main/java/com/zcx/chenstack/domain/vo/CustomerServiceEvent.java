package com.zcx.chenstack.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerServiceEvent(String type, String text, String action,
                                   List<EditableArticle> articles, Integer page,
                                   Integer pageSize, Boolean hasMore, String supportStatus) {
    public record EditableArticle(Integer articleId, String title, Integer editStatus,
                                  Integer examineStatus, boolean canEdit) {}

    public static CustomerServiceEvent text(String text) {
        return new CustomerServiceEvent("TEXT", text, null, null, null, null, null, null);
    }

    public static CustomerServiceEvent state(String type, String text, String action, String supportStatus) {
        return new CustomerServiceEvent(type, text, action, null, null, null, null, supportStatus);
    }
}
